set -euo pipefail
OUT=android/app/build/outputs/apk/release
APK=$(ls "$OUT"/*-release.apk 2>/dev/null | grep -v unsigned | head -1 || true)
test -n "$APK" || APK=$(ls "$OUT"/*.apk | head -1)
echo "待校验 APK: $APK"

# 该 APK 只启用 v2 签名方案（无 v1），META-INF 里没有证书文件，
# 所以不能用 keytool 兜底，必须找到 apksigner 才具备校验能力。
find_apksigner() {
  local d
  for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" /usr/local/lib/android/sdk "$HOME/Android/Sdk"; do
    [ -n "$d" ] && [ -d "$d/build-tools" ] || continue
    ls "$d"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 && return 0
  done
  return 1
}
APKSIGNER=$(find_apksigner || true)
if [ -z "$APKSIGNER" ]; then
  echo "未找到 apksigner，尝试用 sdkmanager 安装 build-tools"
  for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" /usr/local/lib/android/sdk; do
    [ -x "$d/cmdline-tools/latest/bin/sdkmanager" ] || continue
    yes | "$d/cmdline-tools/latest/bin/sdkmanager" "build-tools;35.0.0" >/dev/null 2>&1 || true
    break
  done
  APKSIGNER=$(find_apksigner || true)
fi
if [ -z "$APKSIGNER" ] || [ ! -x "$APKSIGNER" ]; then
  echo "::error::拿不到 apksigner，无法验证签名，拒绝发布（宁可不发，也不能发错签名的包）"
  exit 1
fi
echo "使用 apksigner: $APKSIGNER"

FP=$("$APKSIGNER" verify --print-certs "$APK" | grep -m1 "SHA-256 digest" | awk '{print $NF}')
EXPECT="23e9a21b2af4e257ccb5db7051ccc8d314b518e4504aed33d40122eda51f0435"
echo "APK 证书 SHA-256 : $FP"
echo "期望正式证书      : $EXPECT"
if [ "$FP" != "$EXPECT" ]; then
  echo "::error::APK 签名证书不匹配（实际 $FP）。"
  echo "::error::非正式签名的包发出去，已装用户无法覆盖安装，已阻止发布。"
  exit 1
fi
echo "签名证书校验通过"
