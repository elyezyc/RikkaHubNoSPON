#!/usr/bin/env bash
# 下载构建所需工具链：JDK 17、Android build-tools 36、platform 36、Xposed API 82。
# 已存在的部分会跳过。
#
#   PROXY=http://192.168.25.1:10808 ./tools/fetch_toolchain.sh
#
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE="$ROOT/tools"
SDK="$BASE/android-sdk"
PROXY="${PROXY:-}"

CURL=(curl -fL --retry 3 --retry-delay 2)
if [ -n "$PROXY" ]; then
    CURL+=(-x "$PROXY")
    echo "使用代理: $PROXY"
fi

mkdir -p "$BASE" "$SDK/build-tools" "$SDK/platforms"

# ---- JDK 17 ----
if ls -d "$BASE"/jdk-* >/dev/null 2>&1; then
    echo "跳过 JDK（已存在）"
else
    echo "==> 下载 JDK 17"
    "${CURL[@]}" -o "$BASE/jdk17.tar.gz" \
        'https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse'
    tar xzf "$BASE/jdk17.tar.gz" -C "$BASE"
    rm -f "$BASE/jdk17.tar.gz"
fi

# ---- build-tools 36 ----
if [ -x "$SDK/build-tools/36.0.0/aapt2" ]; then
    echo "跳过 build-tools（已存在）"
else
    echo "==> 下载 build-tools 36.0.0"
    "${CURL[@]}" -o "$BASE/build-tools.zip" \
        'https://dl.google.com/android/repository/build-tools_r36_linux.zip'
    rm -rf "$BASE/.bt" && mkdir -p "$BASE/.bt"
    unzip -q "$BASE/build-tools.zip" -d "$BASE/.bt"
    # zip 里那层目录叫 android-16，内容就是 36.0.0 的 build-tools
    rm -rf "$SDK/build-tools/36.0.0"
    mv "$(find "$BASE/.bt" -maxdepth 1 -mindepth 1 -type d | head -1)" "$SDK/build-tools/36.0.0"
    rm -rf "$BASE/.bt" "$BASE/build-tools.zip"
fi

# ---- platform 36 ----
if [ -f "$SDK/platforms/android-36/android.jar" ]; then
    echo "跳过 platform（已存在）"
else
    echo "==> 下载 platform 36"
    "${CURL[@]}" -o "$BASE/platform-36.zip" \
        'https://dl.google.com/android/repository/platform-36_r02.zip'
    rm -rf "$BASE/.pf" && mkdir -p "$BASE/.pf"
    unzip -q "$BASE/platform-36.zip" -d "$BASE/.pf"
    rm -rf "$SDK/platforms/android-36"
    mv "$BASE/.pf/android-36" "$SDK/platforms/android-36"
    rm -rf "$BASE/.pf" "$BASE/platform-36.zip"
fi

# ---- Xposed API 82 ----
if [ -f "$BASE/xposed-api-82.jar" ]; then
    echo "跳过 Xposed API（已存在）"
else
    echo "==> 下载 Xposed API 82"
    "${CURL[@]}" -o "$BASE/xposed-api-82.jar" \
        'https://maven.aliyun.com/repository/public/de/robv/android/xposed/api/82/api-82.jar'
fi

echo
echo "完成："
ls -d "$BASE"/jdk-* 2>/dev/null || true
ls -d "$SDK/build-tools"/* "$SDK/platforms"/* 2>/dev/null || true
ls -l "$BASE/xposed-api-82.jar" 2>/dev/null || true
