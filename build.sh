#!/usr/bin/env bash
# Builds emberglass.apk without Gradle: NDK clang for the native USB reader, javac + d8, aapt, zipalign, apksigner.
#   SDK=<android sdk> ./build.sh      (default: $LOCALAPPDATA/Android/Sdk)
set -euo pipefail
cd "$(dirname "$0")"

SDK=${SDK:-${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}}
SDK=$(cd "$SDK" && pwd)
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
NDK=$(ls -d "$SDK"/ndk/* | sort -V | tail -1)
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
[ -f "$ANDROID_JAR" ] || ANDROID_JAR=$(ls "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)
# Windows JDK/tools need native paths
win() { if command -v cygpath >/dev/null; then cygpath -w "$1"; else echo "$1"; fi; }
HOST=$(ls "$NDK/toolchains/llvm/prebuilt" | head -1)
CLANG="$NDK/toolchains/llvm/prebuilt/$HOST/bin/clang"
[ -x "$CLANG" ] || CLANG="$CLANG.exe"
exe() { if [ -x "$BT/$1" ]; then echo "$BT/$1"; else echo "$BT/$1.exe"; fi; }
OUT=build

rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/apk/lib/arm64-v8a" "$OUT/apk/lib/armeabi-v7a"

# native USB/UVC reader (16 KB page alignment for Android 15+)
for abi in "arm64-v8a aarch64-linux-android26" "armeabi-v7a armv7a-linux-androideabi26"; do
    set -- $abi
    "$CLANG" --target="$2" -O2 -fPIC -shared -Wall -Wl,-z,max-page-size=16384 \
        -o "$OUT/apk/lib/$1/libuvcraw.so" app/jni/uvcraw.c
done

# R.java for the app's own resources (icons)
mkdir -p "$OUT/gen"
"$(exe aapt)" package -f -m -J "$OUT/gen" -M app/AndroidManifest.xml -S app/res -I "$(win "$ANDROID_JAR")"

javac --release 8 -nowarn -encoding UTF-8 -classpath "$(win "$ANDROID_JAR")" \
    -d "$OUT/classes" $(find app/src "$OUT/gen" -name '*.java')
java -cp "$(win "$BT/lib/d8.jar")" com.android.tools.r8.D8 --min-api 26 --lib "$(win "$ANDROID_JAR")" \
    --output "$OUT/apk" $(find "$OUT/classes" -name '*.class')

"$(exe aapt)" package -f -M app/AndroidManifest.xml -S app/res -A app/assets -I "$(win "$ANDROID_JAR")" -F "$OUT/unsigned.apk"
(cd "$OUT/apk" && "$(exe aapt)" add ../unsigned.apk classes.dex lib/arm64-v8a/libuvcraw.so lib/armeabi-v7a/libuvcraw.so >/dev/null)
# DEV=1: development build with the /debug channel (never publish it)
if [ "${DEV:-0}" = 1 ]; then
    mkdir -p "$OUT/apk/assets" && echo 1 > "$OUT/apk/assets/debug.on"
    (cd "$OUT/apk" && "$(exe aapt)" add ../unsigned.apk assets/debug.on >/dev/null)
    echo "DEV build: /debug channel enabled"
fi
"$(exe zipalign)" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# Signing: the shared release keystore (private homelab repo, ANDROID_KEYSTORE_B64 in .env), described by
# .signing.env (KEYSTORE, KEYSTORE_PASS, KEY_ALIAS; git-ignored). Without it, a local debug key is created.
[ -f .signing.env ] && . ./.signing.env
KEYSTORE=${KEYSTORE:-debug.keystore}
KEYSTORE_PASS=${KEYSTORE_PASS:-android}
KEY_ALIAS=${KEY_ALIAS:-debug}
[ -f "$KEYSTORE" ] || keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASS" -keypass "$KEYSTORE_PASS"     -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Debug" >/dev/null 2>&1
java -jar "$(win "$BT/lib/apksigner.jar")" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS"     --ks-pass "pass:$KEYSTORE_PASS" --key-pass "pass:$KEYSTORE_PASS" --out emberglass.apk "$OUT/aligned.apk"
echo "built emberglass.apk"
