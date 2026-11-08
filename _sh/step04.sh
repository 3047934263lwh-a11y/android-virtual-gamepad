F=android/app/build.gradle
if ! grep -q "signingConfigs" "$F" || ! grep -q "signingConfig signingConfigs.release" "$F"; then
  echo "::error::$F 未声明正式签名（缺 signingConfigs.release 或未在 release 中引用）。"
  echo "::error::从该提交构建会得到 debug 签名的 APK，已装正式版的用户无法覆盖安装。"
  echo "::error::v1.1.1 及更早的标签就属于这种情况，禁止用它们重新发版。"
  echo "::error::请从包含正式签名配置的提交（v1.1.2 及以后）构建，或先补上签名配置。"
  exit 1
fi
echo "已确认 release 构建使用正式签名配置"
