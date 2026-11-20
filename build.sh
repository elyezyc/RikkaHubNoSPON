#!/usr/bin/env bash
# RikkaHubNoSPON 构建脚本 —— 不用 Gradle，直接 aapt2 / javac / d8 / zipalign / apksigner
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

VERSION_NAME="${VERSION_NAME:-1.0.0}"
VERSION_CODE="${VERSION_CODE:-1}"
MIN_SDK="${MIN_SDK:-26}"
TARGET_SDK="${TARGET_SDK:-36}"

# ---- 工具位置（都可用环境变量覆盖） ----
# 工具链没下载时 ls 会失败，加 || true 免得 set -e 直接静默退出
JDK_HOME="${JDK_HOME:-$(ls -d "$ROOT"/tools/jdk-* 2>/dev/null | head -1 || true)}"
SDK_ROOT="${SDK_ROOT:-$ROOT/tools/android-sdk}"
BT="${BUILD_TOOLS:-$SDK_ROOT/build-tools/36.0.0}"
ANDROID_JAR="${ANDROID_JAR:-$SDK_ROOT/platforms/android-36/android.jar}"
XPOSED_JAR="${XPOSED_JAR:-$ROOT/tools/xposed-api-82.jar}"

KEYSTORE="${KEYSTORE:-$ROOT/Key/rikkahubnospon-key.jks}"
KEYSTORE_PROPS="${KEYSTORE_PROPS:-$ROOT/Key/keystore.properties}"

WORK="$ROOT/.work/build"
DIST="$ROOT/dist"
MANIFEST="$ROOT/module/AndroidManifest.xml"
RES_DIR="$ROOT/module/res"
SRC_DIR="$ROOT/module/src"
ASSETS_DIR="$ROOT/module/assets"

die() { echo "错误: $*" >&2; exit 1; }

missing=()
[ -x "$JDK_HOME/bin/javac" ] || missing+=("JDK 17（tools/jdk-*）")
[ -e "$BT/aapt2" ] || missing+=("Android build-tools 36（tools/android-sdk/build-tools/36.0.0）")
[ -e "$ANDROID_JAR" ] || missing+=("Android platform 36（tools/android-sdk/platforms/android-36/android.jar）")
[ -e "$XPOSED_JAR" ] || missing+=("Xposed API 82（tools/xposed-api-82.jar）")
if [ ${#missing[@]} -gt 0 ]; then
    printf '缺少构建依赖:\n' >&2
    printf '  %s\n' "${missing[@]}" >&2
    die "先跑 ./tools/fetch_toolchain.sh（可用 PROXY=http://ip:port 走代理）"
fi

export JAVA_HOME="$JDK_HOME"
export PATH="$JDK_HOME/bin:$PATH"

# ---- 签名信息 ----
if [ -n "${KS_PASS:-}" ] && [ -n "${KEY_ALIAS:-}" ] && [ -n "${KEY_PASS:-}" ]; then
    : # 走环境变量
elif [ -f "$KEYSTORE_PROPS" ]; then
    KS_PASS="$(sed -n 's/^storePassword *= *//p' "$KEYSTORE_PROPS" | tail -1)"
    KEY_ALIAS="$(sed -n 's/^keyAlias *= *//p' "$KEYSTORE_PROPS" | tail -1)"
    KEY_PASS="$(sed -n 's/^keyPassword *= *//p' "$KEYSTORE_PROPS" | tail -1)"
else
    die "没有签名信息：请提供 $KEYSTORE_PROPS，或设置 KS_PASS / KEY_ALIAS / KEY_PASS"
fi
[ -f "$KEYSTORE" ] || die "找不到密钥库 $KEYSTORE"
[ -n "$KS_PASS" ] && [ -n "$KEY_ALIAS" ] || die "签名信息不完整（storePassword / keyAlias）"
[ -n "${KEY_PASS:-}" ] || KEY_PASS="$KS_PASS"

# 信息页里写的版本号要跟着走
VERSION_IN_CODE="$(sed -n 's/.*String VERSION = "\(.*\)".*/\1/p' "$ROOT/module/src/com/elyezyc/rikkahubnospon/MainActivity.java" | head -1)"
if [ -n "$VERSION_IN_CODE" ] && [ "$VERSION_IN_CODE" != "$VERSION_NAME" ]; then
    echo "提示: MainActivity.VERSION=$VERSION_IN_CODE 与 VERSION_NAME=$VERSION_NAME 不一致，记得同步"
fi

echo "==> 清理"
rm -rf "$WORK"
mkdir -p "$WORK/gen" "$WORK/classes" "$WORK/dex" "$DIST"

echo "==> 编译资源"
"$BT/aapt2" compile --dir "$RES_DIR" -o "$WORK/res.zip"

echo "==> 链接资源与清单"
"$BT/aapt2" link \
    -o "$WORK/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$MANIFEST" \
    --java "$WORK/gen" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    -A "$ASSETS_DIR" \
    --auto-add-overlay \
    "$WORK/res.zip"

echo "==> 编译 Java"
find "$SRC_DIR" "$WORK/gen" -name '*.java' > "$WORK/sources.txt"
"$JDK_HOME/bin/javac" \
    -source 8 -target 8 -Xlint:-options \
    -bootclasspath "$ANDROID_JAR" \
    -classpath "$XPOSED_JAR" \
    -d "$WORK/classes" \
    @"$WORK/sources.txt"

echo "==> 转 dex"
find "$WORK/classes" -name '*.class' > "$WORK/classes.txt"
"$BT/d8" \
    --release \
    --min-api "$MIN_SDK" \
    --lib "$ANDROID_JAR" \
    --classpath "$XPOSED_JAR" \
    --output "$WORK/dex" \
    $(cat "$WORK/classes.txt")

echo "==> 打包"
cp "$WORK/base.apk" "$WORK/unaligned.apk"
(cd "$WORK/dex" && zip -q -X ../unaligned.apk classes.dex)

echo "==> 对齐"
"$BT/zipalign" -f -p 4 "$WORK/unaligned.apk" "$WORK/aligned.apk"

APK="$DIST/RikkaHubNoSPON-$VERSION_NAME.apk"
OUT_APK="$APK"
echo "==> 签名"
"$BT/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$KS_PASS" \
    --key-pass "pass:$KEY_PASS" \
    --min-sdk-version "$MIN_SDK" \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --out "$APK" \
    "$WORK/aligned.apk"

echo "==> 校验签名"
"$BT/apksigner" verify --print-certs -v "$APK"

echo "==> 校验清单"
"$BT/aapt2" dump badging "$APK" | sed -n '1p'
"$BT/aapt2" dump xmltree --file AndroidManifest.xml "$APK" | grep -E 'xposed(module|minversion|description|scope)' || true

echo
echo "产物: $APK ($(du -h "$APK" | cut -f1))"
