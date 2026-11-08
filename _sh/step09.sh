OUT=android/app/build/outputs/apk/release
APK=$(ls "$OUT"/*-release.apk "$OUT"/*-release-unsigned.apk 2>/dev/null | grep -v unsigned | head -1)
test -n "$APK" || APK=$(ls "$OUT"/*.apk | head -1)
NAME="VirtualGamepad-${GITHUB_REF_NAME}.apk"
mv "$APK" "$OUT/$NAME"
sha256sum "$OUT/$NAME" | awk '{print $1}' > "$OUT/$NAME.sha256"
echo "APK: $NAME"
echo "SHA-256: $(cat "$OUT/$NAME.sha256")"
