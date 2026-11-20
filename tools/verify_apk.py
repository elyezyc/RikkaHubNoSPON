#!/usr/bin/env python3
"""检查构建出来的模块 APK 本身：入口、资源、dex、有没有把 Xposed API 打进包里。

用法： python3 tools/verify_apk.py dist/RikkaHubNoSPON-1.0.0.apk
SPDX-License-Identifier: GPL-3.0-or-later
"""
import os
import re
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEXDUMP = os.path.join(ROOT, "tools/android-sdk/build-tools/36.0.0/dexdump")
AAPT2 = os.path.join(ROOT, "tools/android-sdk/build-tools/36.0.0/aapt2")

ENTRY = "com.elyezyc.rikkahubnospon.HookEntry"
ENTRY_DESC = "Lcom/elyezyc/rikkahubnospon/HookEntry;"
EXPECTED_CLASSES = [
    "Lcom/elyezyc/rikkahubnospon/HookEntry;",
    "Lcom/elyezyc/rikkahubnospon/ProviderGuard;",
    "Lcom/elyezyc/rikkahubnospon/ProviderPolicy;",
    "Lcom/elyezyc/rikkahubnospon/Reflect;",
    "Lcom/elyezyc/rikkahubnospon/MainActivity;",
]
SECRET = "hXX0oTCLiY8do67xVjCwrAL4ywboMmCV"


def dex_class_descriptors(path):
    out = subprocess.run([DEXDUMP, path], capture_output=True, text=True, errors="replace")
    if out.returncode != 0:
        raise SystemExit("dexdump 失败：%s" % out.stderr[-2000:])
    return set(re.findall(r"^  Class descriptor\s+: '(.*)'$", out.stdout, re.M))


def dump_manifest(apk):
    """编译后的 AndroidManifest.xml 是二进制 XML（字符串存成 UTF-16），用 aapt2 读成文本。"""
    if os.path.isfile(AAPT2):
        out = subprocess.run(
            [AAPT2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk],
            capture_output=True, text=True, errors="replace")
        if out.returncode == 0 and out.stdout.strip():
            return out.stdout
    with zipfile.ZipFile(apk) as zf:
        raw = zf.read("AndroidManifest.xml")
    # 兜底：二进制 XML 的字符串池是 UTF-16LE
    return raw.decode("utf-16-le", "replace")


def dump_resources(apk):
    """xposedscope 指向的是 res 里的 string-array，值在 resources.arsc 里，不在清单二进制里。"""
    if not os.path.isfile(AAPT2):
        return ""
    out = subprocess.run([AAPT2, "dump", "resources", apk],
                         capture_output=True, text=True, errors="replace")
    return out.stdout if out.returncode == 0 else ""


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        ROOT, "dist", "RikkaHubNoSPON-1.0.0.apk")
    if not os.path.isfile(apk):
        raise SystemExit("找不到 APK: %s" % apk)
    if not os.path.isfile(DEXDUMP):
        raise SystemExit("找不到 dexdump：%s\n%s" % (DEXDUMP, "先跑 ./tools/fetch_toolchain.sh（可用 PROXY=http://ip:port 走代理）拉取构建工具链"))

    failures = []

    def check(ok, label, detail=""):
        print(("ok    " if ok else "FAIL  ") + label + (("  " + detail) if detail and not ok else ""))
        if not ok:
            failures.append(label)

    with zipfile.ZipFile(apk) as zf:
        names = zf.namelist()
        check("assets/xposed_init" in names, "assets/xposed_init 存在")
        if "assets/xposed_init" in names:
            init = zf.read("assets/xposed_init").decode("utf-8", "replace").strip()
            check(init == ENTRY, "xposed_init 指向 %s" % ENTRY, "实际: %r" % init)

        check("classes.dex" in names, "classes.dex 存在")
        manifest_text = dump_manifest(apk)
        check("xposedmodule" in manifest_text, "清单里有 xposedmodule 标记")
        check("xposeddescription" in manifest_text, "清单里有 xposeddescription")
        check("xposedminversion" in manifest_text, "清单里有 xposedminversion")
        check("me.rerere.rikkahub" in dump_resources(apk), "作用域数组里写了宿主包名")

        # 签名信息不应该出现在包里
        leaked = [n for n in names if n.endswith(".jks") or n.endswith(".properties")]
        check(not leaked, "没有把密钥库/口令文件打进 APK", str(leaked))

        blob = b"".join(zf.read(n) for n in names if n.endswith(".dex"))
        check(SECRET.encode() not in blob, "dex 里没有签名口令")

        with tempfile.TemporaryDirectory() as tmp:
            dex_path = os.path.join(tmp, "classes.dex")
            with open(dex_path, "wb") as fh:
                fh.write(zf.read("classes.dex"))
            defined = dex_class_descriptors(dex_path)

    for desc in EXPECTED_CLASSES:
        check(desc in defined, "定义了 %s" % desc)

    bundled = sorted(d for d in defined if d.startswith("Lde/robv/"))
    check(not bundled, "没有把 Xposed API 类打进 dex（应完全由框架提供）", str(bundled))

    print()
    if failures:
        print("APK 校验失败：%d 项" % len(failures))
        return 1
    print("APK 校验通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
