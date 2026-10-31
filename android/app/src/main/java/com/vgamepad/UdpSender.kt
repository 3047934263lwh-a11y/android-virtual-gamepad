package com.vgamepad

import android.os.Handler
import android.os.Looper
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.LockSupport

/**
 * 以固定频率把 GamepadState 序列化为 20 字节定长报文，通过 UDP 单播发往 PC。
 *
 * 选择 UDP 而非 TCP 的原因：
 * 1) 无重传、无队头阻塞，单帧丢失只会让某个状态晚 8ms 生效，下一帧立即纠正；
 * 2) TCP 的 Nagle 与拥塞控制会引入不可控抖动，对输入延迟是致命的；
 * 3) 报文本身就是"绝对状态"而非增量，天然幂等，丢包无需补发。
 */
class UdpSender(private val state: GamepadState) {

    interface Listener {
        fun onConnected(host: String, port: Int)
        fun onError(message: String)
        fun onStats(pps: Int)
        fun onStopped()

        /**
         * PC 端游戏震动回传。largeMotor / smallMotor 为 0..255 的马达强度，
         * 与 Xbox 360 手柄的两个马达一一对应。
         */
        fun onRumble(largeMotor: Int, smallMotor: Int)

        /** 本地多人：接收端为本手机分配的玩家编号（1..4） */
        fun onPlayerAssigned(playerIndex: Int) {}
    }

    companion object {
        const val PACKET_SIZE = 20
        const val MAGIC = 0x31504756 // "VGP1"
        const val VERSION = 1
        const val DEFAULT_PORT = 26760

        /** 回传监听固定端口（接收端按来源端口回发震动/编号，固定便于诊断） */
        const val REPLY_PORT = 26761

        /** 125Hz，与主流手柄/键鼠回报率对齐 */
        private const val SEND_HZ = 125
    }

    /** 创建收发共用的套接字：强制绑定 IPv4 0.0.0.0（部分设备 udp6 通配收不到
     *  IPv4 单播），优先固定回传端口，冲突时退回随机端口 */
    private fun openSocket(): DatagramSocket = try {
        DatagramSocket(InetSocketAddress(InetAddress.getByName("0.0.0.0"), REPLY_PORT))
    } catch (e: Exception) {
        DatagramSocket()
    }

    private val main = Handler(Looper.getMainLooper())
    private val buffer: ByteBuffer = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
    private val bytes = ByteArray(PACKET_SIZE)

    @Volatile
    private var running = false

    @Volatile
    private var socket: DatagramSocket? = null
    @Volatile
    private var worker: Thread? = null
    @Volatile
    private var rumbleThread: Thread? = null

    val isRunning: Boolean get() = running

    /** 启动发送。主机名解析与套接字创建都在工作线程内完成，
     *  避免主线程 DNS 触发 NetworkOnMainThreadException。 */
    fun start(host: String, port: Int, listener: Listener): Boolean {
        stop()

        val trimmed = host.trim()
        if (trimmed.isEmpty()) {
            listener.onError("请输入电脑的局域网 IP")
            return false
        }
        if (port !in 1..65535) {
            listener.onError("端口必须为 1-65535")
            return false
        }

        running = true
        worker = Thread({ runLoop(trimmed, port, listener) }, "vgp-udp-sender").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
        return true
    }

    fun stop() {
        running = false
        // 先关闭套接字，让可能阻塞在 send()/receive() 上的工作线程立即抛错退出
        runCatching { socket?.close() }
        socket = null
        val t = worker
        worker = null
        if (t != null && t !== Thread.currentThread()) {
            runCatching { t.join(400) }
        }
        val r = rumbleThread
        rumbleThread = null
        if (r != null && r !== Thread.currentThread()) {
            runCatching { r.join(200) }
        }
    }

    /**
     * 接收线程：处理 PC 端回传的 8 字节定长报文——
     * "VGPR" 游戏震动（左右马达 0–255）与 "VGPP" 玩家编号（多人模式 1P–4P）。
     * 手机端套接字绑定固定 IPv4 端口 REPLY_PORT（26761），PC 端向该端口回包；
     * 强制 IPv4 绑定是为了避免部分机型的 IPv6 双栈丢弃 IPv4 单播。
     */
    private fun rumbleLoop(sock: DatagramSocket, listener: Listener) {
        val buf = ByteArray(16)
        val packet = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                sock.receive(packet)
                if (packet.length >= 8 &&
                    buf[0] == 0x56.toByte() && buf[1] == 0x47.toByte() &&
                    buf[2] == 0x50.toByte()
                ) {
                    when (buf[3].toInt()) {
                        0x52 -> if (buf[4] == 1.toByte()) {           // "VGPR" 震动
                            val large = buf[5].toInt() and 0xFF
                            val small = buf[6].toInt() and 0xFF
                            main.post { listener.onRumble(large, small) }
                        }
                        0x50 -> if (buf[4] == 1.toByte()) {           // "VGPP" 玩家编号
                            val n = buf[5].toInt() and 0xFF
                            if (n in 1..4) main.post { listener.onPlayerAssigned(n) }
                        }
                    }
                }
            } catch (e: SocketException) {
                break
            } catch (e: Exception) {
                if (!running) break
            }
        }
    }

    private fun runLoop(
        host: String,
        port: Int,
        listener: Listener
    ) {
        // 主机名解析移到工作线程（原在主线程，输入主机名会崩溃）
        val address: InetAddress = try {
            InetAddress.getByName(host)
        } catch (e: Exception) {
            main.post { listener.onError("无法解析地址 $host：${e.message}") }
            running = false
            main.post { listener.onStopped() }
            return
        }

        val sock: DatagramSocket = openSocket()
        if (!running) {          // 解析期间被 stop()：立即收尾，避免套接字泄漏
            runCatching { sock.close() }
            main.post { listener.onStopped() }
            return
        }
        sock.broadcast = false
        socket = sock

        rumbleThread = Thread({ rumbleLoop(sock, listener) }, "vgp-rumble").apply {
            isDaemon = true
            start()
        }

        val periodNs = 1_000_000_000L / SEND_HZ
        var next = System.nanoTime()
        var count = 0
        var windowStart = System.nanoTime()
        var lastErrorAt = 0L

        main.post { listener.onConnected(address.hostAddress ?: "", port) }

        while (running) {
            try {
                serialize()
                sock.send(DatagramPacket(bytes, PACKET_SIZE, address, port))
                state.sequence = (state.sequence + 1) and 0xFF
                count++
            } catch (e: Exception) {
                if (!running) break
                val now = System.nanoTime()
                if (now - lastErrorAt > 1_000_000_000L) {
                    lastErrorAt = now
                    val msg = e.message ?: e.javaClass.simpleName
                    main.post { listener.onError("发送失败：$msg") }
                }
            }

            val now = System.nanoTime()
            if (now - windowStart >= 1_000_000_000L) {
                val pps = count
                count = 0
                windowStart = now
                main.post { listener.onStats(pps) }
            }

            next += periodNs
            val sleep = next - System.nanoTime()
            if (sleep > 0) {
                LockSupport.parkNanos(sleep)
            } else if (sleep < -periodNs * 8) {
                // 线程被长时间挂起（息屏/GC），重置基准，避免补偿性突发发送
                next = System.nanoTime()
            }
        }

        main.post { listener.onStopped() }
    }

    private fun serialize() {
        buffer.clear()
        buffer.putInt(MAGIC)
        buffer.put(VERSION.toByte())
        buffer.put((state.buttons and 0xFF).toByte())
        buffer.put((state.buttons ushr 8 and 0xFF).toByte())
        buffer.put((state.sequence and 0xFF).toByte())
        // 陀螺仪模式：右摇杆 = 触摸摇杆 + 陀螺仪 叠加（摇杆大幅转、陀螺仪微调），
        // 相加后限幅 ±32767；关闭陀螺仪时右摇杆恢复独占
        val orx = if (state.useGyro)
            (state.rx + state.gyroRx).coerceIn(-32767, 32767) else state.rx
        val ory = if (state.useGyro)
            (state.ry + state.gyroRy).coerceIn(-32767, 32767) else state.ry
        buffer.putShort(state.lx.toShort())
        buffer.putShort(state.ly.toShort())
        buffer.putShort(orx.toShort())
        buffer.putShort(ory.toShort())
        buffer.put((state.lt and 0xFF).toByte())
        buffer.put((state.rt and 0xFF).toByte())
        buffer.putShort(0)
        buffer.flip()
        buffer.get(bytes, 0, PACKET_SIZE)
    }
}
