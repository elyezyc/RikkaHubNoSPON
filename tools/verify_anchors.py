#!/usr/bin/env python3
"""核对模块依赖的反射锚点，在指定 RikkaHub APK 里确实存在。

模块只按名字挂钩，不用编译期依赖，所以「锚点还在不在」必须单独验。
用法： python3 tools/verify_anchors.py [RikkaHub.apk] [dexdump 路径]

SPDX-License-Identifier: GPL-3.0-or-later
"""
import os
import re
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

DEFAULT_APK = os.path.join(ROOT, "rikkahub-3.3.0.apk")
RELEASE_URL = ("https://github.com/rikkahub/rikkahub/releases/download/3.3.0/"
               "rikkahub-3.3.0-arm64-v8a.apk")
APK = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_APK
DEXDUMP = (
    sys.argv[2]
    if len(sys.argv) > 2
    else os.path.join(ROOT, "tools/android-sdk/build-tools/36.0.0/dexdump")
)

SECTION_RE = re.compile(r"^  (Static fields|Instance fields|Direct methods|Virtual methods)\s+-")
NAME_RE = re.compile(r"^      name\s+: '(.*)'$")
TYPE_RE = re.compile(r"^      type\s+: '(.*)'$")
DESC_RE = re.compile(r"^  Class descriptor\s+: '(.*)'$")


def parse_dex(path):
    """返回 {class_descriptor: {'field': {name: type}, 'method': {name: type}}}"""
    out = subprocess.run([DEXDUMP, path], capture_output=True, text=True, errors="replace")
    if out.returncode != 0:
        raise SystemExit("dexdump 失败: %s\n%s" % (path, out.stderr[-2000:]))

    classes = {}
    current = None
    kind = None
    pending_name = None
    for line in out.stdout.splitlines():
        m = DESC_RE.match(line)
        if m:
            current = classes.setdefault(m.group(1), {"field": {}, "method": {}})
            pending_name = None
            kind = None
            continue
        m = SECTION_RE.match(line)
        if m:
            kind = "field" if "fields" in m.group(1) else "method"
            pending_name = None
            continue
        m = NAME_RE.match(line)
        if m:
            pending_name = m.group(1)
            continue
        m = TYPE_RE.match(line)
        if m and current is not None and kind and pending_name is not None:
            current[kind][pending_name] = m.group(1)
            pending_name = None
    return classes


DESC_SETTINGS = "Lme/rerere/rikkahub/data/datastore/Settings;"
DESC_NORMALIZATION = "Lme/rerere/rikkahub/data/datastore/SettingsNormalizationKt;"
DESC_DEFAULTS = "Lme/rerere/rikkahub/data/datastore/DefaultProvidersKt;"
DESC_PROVIDER = "Lme/rerere/ai/provider/ProviderSetting;"

# (说明, 类别, 类描述符, 成员名, 期望类型)
REQUIRED = [
    ("规整入口 normalized()", "method", DESC_NORMALIZATION, "normalized",
     "(Lme/rerere/rikkahub/data/datastore/Settings;)Lme/rerere/rikkahub/data/datastore/Settings;"),
    ("内置提供商清单", "field", DESC_DEFAULTS, "DEFAULT_PROVIDERS", "Ljava/util/List;"),
    ("Settings.providers", "field", DESC_SETTINGS, "providers", "Ljava/util/List;"),
    ("Settings.getProviders", "method", DESC_SETTINGS, "getProviders", "()Ljava/util/List;"),
    ("ProviderSetting.getId", "method", DESC_PROVIDER, "getId", "()Lkotlin/uuid/Uuid;"),
    ("ProviderSetting.getBuiltIn", "method", DESC_PROVIDER, "getBuiltIn", "()Z"),
]

# 每个具体提供商子类都要有 builtIn 字段和 getBuiltIn()，模块会给它们挂兜底钩子
PROVIDER_SUBCLASSES = ["OpenAI", "Google", "Claude", "ChatGPT", "Grok"]
for sub in PROVIDER_SUBCLASSES:
    REQUIRED.append(("%s.builtIn" % sub, "field",
                     "Lme/rerere/ai/provider/ProviderSetting$%s;" % sub, "builtIn", "Z"))
    REQUIRED.append(("%s.getBuiltIn" % sub, "method",
                     "Lme/rerere/ai/provider/ProviderSetting$%s;" % sub, "getBuiltIn", "()Z"))


def main():
    if not os.path.isfile(DEXDUMP):
        raise SystemExit("找不到 dexdump：%s\n%s" % (DEXDUMP, "先跑 ./tools/fetch_toolchain.sh（可用 PROXY=http://ip:port 走代理）拉取构建工具链"))

    if not os.path.isfile(APK):
        if APK != DEFAULT_APK:
            raise SystemExit("找不到 APK: %s" % APK)
        # 目录清理后这里可能是空的，按需拉一份官方包（走 HTTPS_PROXY 环境变量）
        print("本地没有 %s，尝试下载…" % os.path.basename(APK))
        try:
            import urllib.request
            urllib.request.urlretrieve(RELEASE_URL, APK)
            print("已下载 %s" % APK)
        except Exception as exc:  # noqa: BLE001
            raise SystemExit(
                "下载失败（%s）。请手动下载 RikkaHub 3.3.0 的 arm64-v8a APK 放到 %s，\n"
                "或用 HOST_APK=... 指定路径；需要代理时先 export https_proxy=http://ip:port"
                % (exc, APK))

    classes = {}
    with tempfile.TemporaryDirectory() as tmp:
        with zipfile.ZipFile(APK) as zf:
            dex_names = [n for n in zf.namelist() if re.match(r"classes\d*\.dex$", n)]
            if not dex_names:
                raise SystemExit("APK 里没有 dex")
            for name in dex_names:
                path = os.path.join(tmp, name.replace("/", "_"))
                with open(path, "wb") as fh:
                    fh.write(zf.read(name))
                for desc, members in parse_dex(path).items():
                    classes.setdefault(desc, {"field": {}, "method": {}})
                    for kind in ("field", "method"):
                        classes[desc][kind].update(members[kind])

    print("扫描 %s：%d 个 dex，%d 个类" % (os.path.basename(APK), len(dex_names), len(classes)))
    print()

    failures = 0
    for label, kind, desc, member, expected_type in REQUIRED:
        members = classes.get(desc)
        if members is None:
            print("FAIL  %-28s 类不存在 %s" % (label, desc))
            failures += 1
            continue
        actual = members[kind].get(member)
        if actual is None:
            print("FAIL  %-28s 缺少 %s" % (label, member))
            failures += 1
        elif actual != expected_type:
            print("FAIL  %-28s 类型不符：%s != %s" % (label, actual, expected_type))
            failures += 1
        else:
            print("ok    %-28s %s" % (label, actual))

    print()
    if failures:
        print("锚点校验失败：%d 项" % failures)
        return 1
    print("锚点校验通过：%d 项" % len(REQUIRED))
    return 0


if __name__ == "__main__":
    sys.exit(main())
