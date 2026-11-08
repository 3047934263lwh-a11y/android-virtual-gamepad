if [ -z "$KEYSTORE_BASE64" ]; then
  echo "::error::缺少 Secret KEYSTORE_BASE64，请配置正式签名 keystore"
  exit 1
fi
echo "$KEYSTORE_BASE64" | base64 -d > "$RUNNER_TEMP/vgamepad-release.keystore"
echo "KEYSTORE_FILE=$RUNNER_TEMP/vgamepad-release.keystore" >> "$GITHUB_ENV"
echo "KEYSTORE_PASSWORD=$KEYSTORE_PASSWORD" >> "$GITHUB_ENV"
echo "KEY_ALIAS=$KEY_ALIAS" >> "$GITHUB_ENV"
echo "KEY_PASSWORD=$KEY_PASSWORD" >> "$GITHUB_ENV"
echo "签名密钥已就位：$(ls -la "$RUNNER_TEMP/vgamepad-release.keystore")"
