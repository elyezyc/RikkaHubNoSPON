#!/usr/bin/env bash
# 一次性跑完三种校验：反射锚点（对照真机 APK）、JVM 单元测试、产物 APK 检查
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK="${1:-$ROOT/dist/RikkaHubNoSPON-1.0.0.apk}"
HOST_APK="${HOST_APK:-$ROOT/rikkahub-3.3.0.apk}"

echo "############ 1/3 反射锚点（对照 RikkaHub 发布包） ############"
python3 "$ROOT/tools/verify_anchors.py" "$HOST_APK"

echo
echo "############ 2/3 JVM 单元测试 ############"
bash "$ROOT/test/run.sh"

echo
echo "############ 3/3 产物 APK ############"
if [ -f "$APK" ]; then
    python3 "$ROOT/tools/verify_apk.py" "$APK"
else
    echo "跳过：没有 $APK（先 ./build.sh 构建，或从 Releases 下载后放到 dist/）"
fi

echo
echo "全部校验通过。"
