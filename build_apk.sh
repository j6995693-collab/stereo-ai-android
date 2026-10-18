#!/bin/bash
# Stereo AI — 无 Gradle 构建脚本（aapt2 + javac + d8 + zipalign + apksigner）
# 用法：./build_apk.sh
# 需要环境变量（或按需修改下方默认值）：
#   ANDROID_SDK  Android SDK 根目录（含 build-tools/ 与 platforms/）
#   JAVA_HOME    JDK 目录（8 及以上，需含 javac/keytool）
#   CODEX_BIN    codex aarch64-linux-musl 可执行文件，构建时打包为 lib/arm64-v8a/libcodex.so
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/app"
BUILD="$ROOT/build"
OUT="$BUILD/out"
GEN_SRC="$BUILD/gen-src"

SDK="${ANDROID_SDK:-$HOME/android-build/sdk}"
JAVA_HOME="${JAVA_HOME:-$HOME/android-build/jdk/Contents/Home}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

BUILD_TOOLS_VER="${BUILD_TOOLS_VER:-$(ls "$SDK/build-tools" | sort -V | tail -1)}"
BT="$SDK/build-tools/$BUILD_TOOLS_VER"
PLATFORM="${PLATFORM:-android-34}"
ANDROID_JAR="$SDK/platforms/$PLATFORM/android.jar"
OUTPUT_NAME="${OUTPUT_NAME:-StereoAI-public.apk}"
CODEX_BIN="${CODEX_BIN:-}"
MIN_API="${MIN_API:-26}"

[ -x "$JAVA_HOME/bin/javac" ] || { echo "ERROR: JAVA_HOME 无效: $JAVA_HOME" >&2; exit 1; }
[ -f "$ANDROID_JAR" ] || { echo "ERROR: 找不到 $ANDROID_JAR（检查 ANDROID_SDK/PLATFORM）" >&2; exit 1; }
[ -d "$BT" ] || { echo "ERROR: 找不到 build-tools: $BT" >&2; exit 1; }
[ -n "$CODEX_BIN" ] && [ -f "$CODEX_BIN" ] || {
  echo "ERROR: 未设置 CODEX_BIN 或文件不存在: '$CODEX_BIN'" >&2
  echo "下载示例（选 aarch64-unknown-linux-musl）：" >&2
  echo "  https://github.com/openai/codex/releases/latest" >&2
  exit 1
}

rm -rf "$OUT" "$GEN_SRC"
mkdir -p "$OUT/classes" "$OUT/gen" "$OUT/dex" "$OUT/lib/arm64-v8a" "$GEN_SRC/com/stereo/ai"
cp "$CODEX_BIN" "$OUT/lib/arm64-v8a/libcodex.so"

# bundle 内容哈希（用于资源版本号）
if command -v shasum >/dev/null 2>&1; then HASH_CMD="shasum -a 256"; else HASH_CMD="sha256sum"; fi
BVER=$(find "$SRC/assets/bundle" -type f -print0 | LC_ALL=C sort -z \
  | xargs -0 cat | $HASH_CMD | cut -c1-32)
cat > "$GEN_SRC/com/stereo/ai/BundleVer.java" <<EOF
package com.stereo.ai;

public class BundleVer { public static final String VER = "b-$BVER"; }
EOF

echo "[1/6] aapt2 compile"
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/res.zip"

echo "[2/6] aapt2 link"
DBGFLAG=""
if [ -n "$DEBUG" ]; then DBGFLAG="--debug-mode"; echo "note: debug build (android:debuggable=true)"; fi
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$SRC/AndroidManifest.xml" -R "$OUT/res.zip" \
  -A "$SRC/assets" $DBGFLAG \
  --java "$OUT/gen" --auto-add-overlay

echo "[3/6] javac"
JAVA_FILES=()
while IFS= read -r -d '' f; do JAVA_FILES+=("$f"); done \
  < <(find "$OUT/gen" "$GEN_SRC" "$SRC" -name "*.java" -print0)
"$JAVA_HOME/bin/javac" -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR" \
  -d "$OUT/classes" "${JAVA_FILES[@]}"

echo "[4/6] d8"
CLASS_FILES=()
while IFS= read -r -d '' f; do CLASS_FILES+=("$f"); done \
  < <(find "$OUT/classes" -name "*.class" -print0)
"$BT/d8" --release --min-api "$MIN_API" --lib "$ANDROID_JAR" \
  --output "$OUT/dex" "${CLASS_FILES[@]}"

echo "[5/6] package"
cd "$OUT" && cp base.apk unsigned.apk \
  && zip -qj unsigned.apk dex/classes.dex \
  && zip -qr unsigned.apk lib && cd - >/dev/null
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[6/6] sign"
KS="${KEYSTORE:-$BUILD/release.keystore}"
KS_PASS="${KEYSTORE_PASS:-android}"
KEY_ALIAS="${KEY_ALIAS:-stereoai}"
if [ ! -f "$KS" ]; then
  echo "生成新 keystore: ${KS}（仅供本地测试；正式发布请换自己的 release keystore）"
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KS" -storepass "$KS_PASS" \
    -keypass "$KS_PASS" -alias "$KEY_ALIAS" -dname "CN=Stereo AI" \
    -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KS_PASS" --out "$BUILD/$OUTPUT_NAME" "$OUT/aligned.apk"
"$BT/apksigner" verify "$BUILD/$OUTPUT_NAME"

echo "APK-OK: $BUILD/$OUTPUT_NAME"
ls -la "$BUILD/$OUTPUT_NAME"
