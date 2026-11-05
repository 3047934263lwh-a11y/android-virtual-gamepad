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
import java.util.concurrent.atomic.AtomicInteger
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

        /** PC 端实测接收速率：每秒随 VGPP 回传，状态栏 PPS 的唯一数据源 */
        fun onLinkStats(pps: Int) {}

        /** PC 端点击 Stop：会话结束通知，手机端应立即切「未连接」并清空 pps/玩家号 */
        fun onDisconnected() {}
    }

    companion object {
        const val PACKET_SIZE = 20
        const val MAGIC = 0x31504756 // "VGP1"
        const val VERSION = 1
        const val DEFAULT_PORT = 26760

        /** 本地回传来源端口：优先固定，便于防火墙/诊断；PC 端实际按「报文来源端口」回发 */
        const val REPLY_PORT = 26761

        /** 250Hz：把发送排队延迟从平均 4ms 压到 2ms，20 字节小包仅 5KB/s，
         *  手机 CPU 与 Wi-Fi 均无压力；PC 端 select(4ms) 逐包消化，同样无积压 */
        private const val SEND_HZ = 250
    }

    /** 创建收发共用的套接字：强制绑定 IPv4 0.0.0.0（部分设备 udp6 通配收不到
     *  IPv4 单播），优先固定回传端口，冲突时退回随机端口。
     *  返回 (socket, 是否用了固定端口)。 */
    private fun openSocket(): Pair<DatagramSocket, Boolean> = try {
        DatagramSocket(InetSocketAddress(InetAddress.getByName("0.0.0.0"), REPLY_PORT)) to true
    } catch (e: Exception) {
        DatagramSocket() to false
    }

    private val main = Handler(Looper.getMainLooper())

    /** 发送代次：每次 start() 递增。旧线程醒来后发现代次不符立即自毁，
     *  彻底杜绝「双发送线程 + 共享缓冲区竞态 + socket 泄漏」。 */
    private val generation = AtomicInteger(0)

    /** 序列号只由发送线程递增；用 AtomicInteger 保证新旧线程交接期间不产生重复/错乱 */
    private val sequence = AtomicInteger(0)

    /** 当前监听器；stop() 置空后，工作线程不再向已销毁的 Activity 投递任何回调 */
    @Volatile
    private var listener: Listener? = null

    @Volatile
    private var running = false

    @Volatile
    private var socket: DatagramSocket? = null
    @Volatile
    private var worker: Thread? = null
    @Volatile
    private var rumbleThread: Thread? = null

    /** 只提示一次「回传端口被占用」，避免 watchdog 反复重连时刷屏 */
    @Volatile
    private var portWarned = false

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

        this.listener = listener
        running = true
        val gen = generation.incrementAndGet()
        worker = Thread({ runLoop(gen, trimmed, port) }, "vgp-udp-sender").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
        return true
    }

    fun stop() {
        running = false
        generation.incrementAndGet()   // 让所有旧线程的代次校验立即失效
        // 先关闭套接字，让可能阻塞在 send()/receive() 上的工作线程立即抛错退出
        runCatching { socket?.close() }
        socket = null
        val t = worker
        worker = null
        val r = rumbleThread
        rumbleThread = null
        if (t != null && t !== Thread.currentThread()) {
            t.interrupt()              // parkNanos 中会立即醒来
            runCatching { t.join(150) }
        }
        if (r != null && r !== Thread.currentThread()) {
            r.interrupt()
            runCatching { r.join(100) }
        }
        // 主动 stop 后不再投递任何回调（调用方自行刷新 UI），避免访问已销毁的 Activity
        listener = null
    }

    /**
     * 接收线程：处理 PC 端回传的 8 字节定长报文——
     * "VGPR" 游戏震动（左右马达 0–255）与 "VGPP" 玩家编号（多人模式 1P–4P）。
     * PC 端按「报文来源端口」回发（server.py / Program.cs 均如此），
     * 本端优先固定绑定 REPLY_PORT 便于诊断；冲突时退回随机端口同样能收到回包。
     * 强制 IPv4 绑定是为了避免部分机型的 IPv6 双栈丢弃 IPv4 单播。
     */
    private fun rumbleLoop(gen: Int, sock: DatagramSocket) {
        val buf = ByteArray(16)
        val packet = DatagramPacket(buf, buf.size)
        var consecutiveErrors = 0
        while (running && gen == generation.get()) {
            try {
                // DatagramSocket.receive 会把 packet.length 改写为本次实收字节数，
                // 并在下一轮以其为可写上限——必须每轮复位，否则收到一个短包后
                // 所有 8 字节回包都会被截断，震动/编号/链路探测从此静默失效。
                packet.length = buf.size
                sock.receive(packet)
                consecutiveErrors = 0
                // 回传报文是 8 字节定长；严格相等可挡掉被截断或构造的超长包
                if (packet.length == 8 &&
                    buf[0] == 0x56.toByte() && buf[1] == 0x47.toByte() &&
                    buf[2] == 0x50.toByte()
                ) {
                    when (buf[3].toInt()) {
                        0x52 -> if (buf[4] == 1.toByte()) {           // "VGPR" 震动
                            val large = buf[5].toInt() and 0xFF
                            val small = buf[6].toInt() and 0xFF
                            listener?.let { l -> main.post { l.onRumble(large, small) } }
                        }
                        0x50 -> if (buf[4] == 1.toByte()) {           // "VGPP" 玩家编号 + 实测 pps
                            val n = buf[5].toInt() and 0xFF
                            val pps = buf[6].toInt() and 0xFF
                            if (n in 1..4) listener?.let { l -> main.post { l.onPlayerAssigned(n) } }
                            listener?.let { l -> main.post { l.onLinkStats(pps) } }
                        }
                        0x51 -> if (buf[4] == 1.toByte()) {           // "VGPQ" PC 端会话结束
                            listener?.let { l -> main.post { l.onDisconnected() } }
                        }
                    }
                }
            } catch (e: SocketException) {
                // socket 被 stop() 关闭属正常退出路径
                break
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (!running || gen != generation.get()) break
                // 未知异常：退避，避免高频空转占满 CPU
                if (++consecutiveErrors >= 3) {
                    try { Thread.sleep(50) } catch (ie: InterruptedException) { break }
                }
                if (consecutiveErrors > 100) break
            }
        }
    }

    private fun runLoop(gen: Int, host: String, port: Int) {
        // 主机名解析移到工作线程（原在主线程，输入主机名会崩溃）
        val address: InetAddress = try {
            InetAddress.getByName(host)
        } catch (e: Exception) {
            listener?.let { l ->
                main.post { l.onError("无法解析地址 $host：${e.message}") }
                main.post { l.onStopped() }
            }
            running = false
            return
        }

        val (sock, fixedPort) = openSocket()
        // 解析/绑定期间被 stop()：立即收尾，避免套接字泄漏
        if (!running || gen != generation.get()) {
            runCatching { sock.close() }
            listener?.let { l -> main.post { l.onStopped() } }
            return
        }
        sock.broadcast = false
        socket = sock
        if (!fixedPort && !portWarned) {
            portWarned = true
            listener?.let { l ->
                main.post { l.onError("回传端口 $REPLY_PORT 被占用，已临时使用随机端口（功能不受影响）") }
            }
        }

        rumbleThread = Thread({ rumbleLoop(gen, sock) }, "vgp-rumble").apply {
            isDaemon = true
            start()
        }

        // 缓冲区为线程局部：彻底避免新旧线程交接期间的字节竞态（A-02）
        val buffer = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val bytes = ByteArray(PACKET_SIZE)

        val periodNs = 1_000_000_000L / SEND_HZ
        var next = System.nanoTime()
        var count = 0
        var windowStart = System.nanoTime()
        var lastErrorAt = 0L

        listener?.let { l -> main.post { l.onConnected(address.hostAddress ?: "", port) } }

        while (running && gen == generation.get()) {
            try {
                serialize(buffer, bytes)
                sock.send(DatagramPacket(bytes, PACKET_SIZE, address, port))
                sequence.incrementAndGet()
                count++
            } catch (e: Exception) {
                if (!running || gen != generation.get()) break
                val now = System.nanoTime()
                if (now - lastErrorAt > 1_000_000_000L) {
                    lastErrorAt = now
                    val msg = e.message ?: e.javaClass.simpleName
                    listener?.let { l -> main.post { l.onError("发送失败：$msg") } }
                }
            }

            val now = System.nanoTime()
            if (now - windowStart >= 1_000_000_000L) {
                val pps = count
                count = 0
                windowStart = now
                listener?.let { l -> main.post { l.onStats(pps) } }
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

        // 自己持有的 socket 由自己关闭：即使 stop() 时序交错也不会泄漏
        runCatching { sock.close() }
        if (socket === sock) socket = null
        listener?.let { l -> main.post { l.onStopped() } }
    }

    private fun serialize(buffer: ByteBuffer, bytes: ByteArray) {
        // 与 GamepadState.reset() 共用 ioLock：发送线程组装报文时不会撞上
        // 「释放全部按键」的跨字段清零，避免组装出 buttons 已清零而 lt 仍为 255 的半帧
        synchronized(state.ioLock) {
            buffer.clear()
            buffer.putInt(MAGIC)
            buffer.put(VERSION.toByte())
            buffer.put((state.buttons and 0xFF).toByte())
            buffer.put((state.buttons ushr 8 and 0xFF).toByte())
            buffer.put((sequence.get() and 0xFF).toByte())
            // 右摇杆灵敏度：只缩放"触摸摇杆"这一路，陀螺仪增益由 GyroAim 单独控制，
            // 因此调右摇杆灵敏度不会改变陀螺仪灵敏度，反之亦然。
            val sx = (state.rx * state.stickSens).toInt().coerceIn(-32767, 32767)
            val sy = (state.ry * state.stickSens).toInt().coerceIn(-32767, 32767)
            // 陀螺仪模式：右摇杆 = 触摸摇杆 + 陀螺仪 叠加（摇杆大幅转、陀螺仪微调），
            // 相加后限幅 ±32767；关闭陀螺仪时右摇杆恢复独占
            val orx = if (state.useGyro)
                (sx + state.gyroRx).coerceIn(-32767, 32767) else sx
            val ory = if (state.useGyro)
                (sy + state.gyroRy).coerceIn(-32767, 32767) else sy
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
}
