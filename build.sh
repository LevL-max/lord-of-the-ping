#!/usr/bin/env bash
# Builds Lord of the Ping without Gradle: aapt2 -> javac -> d8 -> aapt add -> zipalign -> apksigner.
# Usage: VERSION_CODE=2 VERSION_NAME=1.1 ./build.sh
set -euo pipefail
cd "$(dirname "$0")"
unset MSYS_NO_PATHCONV # the .exe tools below need Git Bash to turn /c/... into C:/...

SDK=/c/Claude/tools/android-sdk
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-34/android.jar
JDK=/c/Claude/tools/jdk/jdk-17.0.20.1+1
export JAVA_HOME="$(cygpath -w "$JDK")"
export PATH="$JDK/bin:$PATH"
VERSION_CODE=${VERSION_CODE:-1}
VERSION_NAME=${VERSION_NAME:-1.0}
OUT=build

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

"$BT/aapt2.exe" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2.exe" link -o "$OUT/unsigned.apk" -I "$JAR" --manifest AndroidManifest.xml --java "$OUT/gen" \
    --min-sdk-version 28 --target-sdk-version 31 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" "$OUT/res.zip"

javac -encoding UTF-8 --release 8 -Xlint:-options -classpath "$JAR" -d "$OUT/classes" \
    $(find src "$OUT/gen" -name '*.java')
jar cf "$OUT/classes.jar" -C "$OUT/classes" .
"$BT/d8.bat" --release --min-api 28 --lib "$JAR" --output "$OUT/dex" "$OUT/classes.jar"
(cd "$OUT/dex" && "$BT/aapt.exe" add ../unsigned.apk classes.dex >/dev/null)
"$BT/zipalign.exe" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# The signing key must stay the same for every update (adb install -r refuses a different key).
KS=keystore/lotp.p12
if [ ! -f "$KS" ]; then
    mkdir -p keystore
    head -c 24 /dev/urandom | base64 | tr -d '/+=' > keystore/password.txt
    keytool -genkeypair -keystore "$KS" -storetype PKCS12 -alias lotp -keyalg RSA -keysize 2048 \
        -validity 36500 -storepass "$(cat keystore/password.txt)" -dname "CN=Lord of the Ping, O=Home" >/dev/null
fi
APK="$OUT/lord-of-the-ping-$VERSION_NAME.apk"
"$BT/apksigner.bat" sign --ks "$KS" --ks-key-alias lotp --ks-pass file:keystore/password.txt --out "$APK" "$OUT/aligned.apk"
"$BT/apksigner.bat" verify "$APK"
ls -la "$APK"
