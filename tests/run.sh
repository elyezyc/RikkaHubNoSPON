#!/usr/bin/env bash
# 在普通 JVM 上跑单元测试（不需要设备、不需要 Xposed 运行环境）
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JDK_HOME="${JDK_HOME:-$(ls -d "$ROOT"/tools/jdk-* 2>/dev/null | head -1)}"
XPOSED_JAR="${XPOSED_JAR:-$ROOT/tools/xposed-api-82.jar}"
OUT="$ROOT/.work/test"

[ -x "$JDK_HOME/bin/javac" ] || { echo "找不到 JDK：$JDK_HOME" >&2; exit 1; }
[ -f "$XPOSED_JAR" ] || {
    echo "找不到 Xposed API：$XPOSED_JAR" >&2
    echo "先跑 ./tools/fetch_toolchain.sh" >&2
    exit 1
}

rm -rf "$OUT"
mkdir -p "$OUT"

# MainActivity / HookEntry 依赖 android.jar，这里不编它们；被测的是纯逻辑 + 反射层
SOURCES=(
    "$ROOT/module/src/com/elyezyc/rikkahubnospon/ProviderPolicy.java"
    "$ROOT/module/src/com/elyezyc/rikkahubnospon/Reflect.java"
    "$ROOT/module/src/com/elyezyc/rikkahubnospon/ProviderGuard.java"
)
while IFS= read -r file; do
    SOURCES+=("$file")
done < <(find "$ROOT/tests/src" -name '*.java' | sort)

"$JDK_HOME/bin/javac" -d "$OUT" -cp "$XPOSED_JAR" "${SOURCES[@]}"

echo "== ProviderPolicyTest =="
"$JDK_HOME/bin/java" -cp "$OUT:$XPOSED_JAR" com.elyezyc.rikkahubnospon.ProviderPolicyTest

echo
echo "== ProviderGuardTest（宿主替身） =="
"$JDK_HOME/bin/java" -cp "$OUT:$XPOSED_JAR" com.elyezyc.rikkahubnospon.ProviderGuardTest
