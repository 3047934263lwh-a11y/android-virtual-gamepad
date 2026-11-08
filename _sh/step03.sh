TAG="${GITHUB_REF_NAME#v}"
GRADLE_VER=$(grep -oP 'versionName "\K[^"]+' android/app/build.gradle)
echo "标签=$TAG  versionName=$GRADLE_VER"
if [ "$TAG" != "$GRADLE_VER" ]; then
  echo "::error::标签 v$TAG 与 versionName $GRADLE_VER 不一致，请修正后重新打标签"
  exit 1
fi
