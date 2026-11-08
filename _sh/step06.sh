set -euo pipefail
cd "$RUNNER_TEMP"
urls=(
  "https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip"
  "https://mirrors.aliyun.com/gradle/gradle-8.9-bin.zip"
  "https://downloads.gradle-dn.com/distributions/gradle-8.9-bin.zip"
  "https://services.gradle.org/distributions/gradle-8.9-bin.zip"
)
ok=0
for u in "${urls[@]}"; do
  echo "::group::尝试下载 $u"
  if curl -fL --retry 2 --retry-delay 2 --connect-timeout 15 --max-time 240 -o gradle.zip "$u"; then
    if unzip -q -o gradle.zip && [ -d gradle-8.9 ]; then ok=1; echo "下载成功"; fi
  fi
  echo "::endgroup::"
  if [ "$ok" = "1" ]; then break; fi
done
if [ "$ok" != "1" ]; then echo "::error::所有镜像下载 Gradle 均失败"; exit 1; fi
echo "$RUNNER_TEMP/gradle-8.9/bin" >> "$GITHUB_PATH"
"$RUNNER_TEMP/gradle-8.9/bin/gradle" --version
