# RikkaHubNoSPON

阻止 RikkaHub 自动写入内置（含推广位）提供商，并让自带提供商可以被真正删掉的 Xposed 模块。

| | |
| --- | --- |
| 模块名称 | RikkaHubNoSPON |
| 包名 | `com.elyezyc.rikkahubnospon` |
| 宿主 | RikkaHub（`me.rerere.rikkahub`） |
| 对照版本 | [RikkaHub 3.3.0](https://github.com/rikkahub/rikkahub/tree/3.3.0)（`versionCode` 197） |
| 许可证 | GPL-3.0-or-later |

**下载：[Releases](https://github.com/elyezyc/RikkaHubNoSPON/releases)** —— 仓库里只放源码，
APK 一律走 Release 附件，不放进版本库。

---

## 它要解决的问题

RikkaHub 的设置里有一条「规整」逻辑，每次读盘和每次写入都会跑一遍。
下面是 tag `3.3.0` 上的实现节选（箭头处的注释是本文加的）：

```kotlin
// app/src/main/java/me/rerere/rikkahub/data/datastore/SettingsNormalization.kt
internal fun Settings.normalized(): Settings =
    withBuiltInDefaults().withoutInvalidReferences().withValuesInRange()

internal fun Settings.withBuiltInDefaults(): Settings {
    var providers = this.providers.ifEmpty { DEFAULT_PROVIDERS }.toMutableList()
    DEFAULT_PROVIDERS.forEach { defaultProvider ->
        if (providers.none { it.id == defaultProvider.id }) {
            providers.add(defaultProvider.copyProvider())   // ← 缺了就补回来
        }
    }
    providers = providers.map { provider ->
        val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
        ...
        defaultProvider != null -> provider.copyProvider(
            builtIn = defaultProvider.builtIn,              // ← 顺手把标记重新标回 true
            ...
```

- 源码位置：[SettingsNormalization.kt @ 3.3.0](https://github.com/rikkahub/rikkahub/blob/3.3.0/app/src/main/java/me/rerere/rikkahub/data/datastore/SettingsNormalization.kt)
- 内置清单：[DefaultProviders.kt @ 3.3.0](https://github.com/rikkahub/rikkahub/blob/3.3.0/app/src/main/java/me/rerere/rikkahub/data/datastore/DefaultProviders.kt)
  —— 21 个条目，含 AiHubMix、APIMart、随想AI网关、STA1N API 这类带推广链接的
- 删除入口被 `builtIn` 挡住：[SettingProviderPage.kt @ 3.3.0](https://github.com/rikkahub/rikkahub/blob/3.3.0/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProviderPage.kt)
  （`enabled = !provider.builtIn`）、[SettingProviderDetailPage.kt @ 3.3.0](https://github.com/rikkahub/rikkahub/blob/3.3.0/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProviderDetailPage.kt)
  （`if (!internalProvider.builtIn) { 删除按钮 }`）

于是你删掉的提供商下次启动就又回来了，删除按钮也一直是灰的。

本模块在 `normalized()` 返回后把「本次被补回来」的条目摘掉，并把保留下来的条目的 `builtIn` 清成 `false`。

## 功能

| 功能 | 表现 |
| --- | --- |
| 不再自动写入内置提供商 | 全新安装时内置那 21 个一条都不落盘；删掉的不会被写回 |
| 允许删除自带提供商 | 列表页和详情页的删除入口都可用，删掉之后保持删除状态 |
| 你自己的提供商不受影响 | 手动添加的、备份恢复带进来的、填了 Key 的自带条目都正常保留 |

判断依据是「`normalized()` 的入参里有没有这个 id」：用户自己保存过或者刚在设置页里加的条目一定在入参里，
被自动补回来的不在。所以在设置页里添加提供商（包括从「推荐」里添加的那几个）照常可用。

## 使用

### 框架：推荐 Vector

模块跑在 Xposed API 82 之上，推荐用 **[Vector](https://github.com/JingMatrix/Vector)**（`JingMatrix/Vector`）
来加载它，而不是 LSPosed：

| | Vector | LSPosed |
| --- | --- | --- |
| 维护状态 | 活跃，跟着 Android 新版走 | 长期未更新 |
| 支持系统 | Android 8.1 – 17 Beta | 大致停在 Android 14 时代 |
| 模块 API | 与原始 Xposed 保持 API 一致（本模块用的 API 82 可直接用） | 同一套 |
| 运行环境 | Magisk / KernelSU + Zygisk | 同左 |

Vector 是 Zygisk 模块（底层 LSPlant），不改 APK、重启即可撤销，装法和 LSPosed 基本一样。

### 步骤

1. 到 [Releases](https://github.com/elyezyc/RikkaHubNoSPON/releases) 下载最新一版的 APK 装上。
2. 装好 [Vector](https://github.com/JingMatrix/Vector)：
   - 从 [Vector Releases](https://github.com/JingMatrix/Vector/releases) 下载最新的系统模块包；
   - 用 Magisk / KernelSU 刷入，**确认 Zygisk 已启用**（必要时配 [NeoZygisk](https://github.com/JingMatrix/NeoZygisk)）；
   - 重启设备。
3. 从系统通知进入 Vector 管理界面 → 模块列表里启用 `RikkaHubNoSPON`。
4. 作用域勾选 RikkaHub（`me.rerere.rikkahub`）。清单里已经用 `xposedscope` 声明了默认作用域，通常自动勾好。
5. 强制停止 RikkaHub，再重新打开。

确认生效：

- RikkaHub → 设置 → 提供商：列表里只剩你自己添加的条目，自带的可以删。
- Vector 的日志页过滤 `RikkaHubNoSPON`，会看到 `提供商 N → M` 这样的记录；关键钩子没挂上会明确报错。

模块本身不联网、不读写宿主数据、不开任何后台服务，只改内存里的这一个列表。

> 想继续用 LSPosed 也可以——本模块只依赖 API 82 的 `XposedBridge.hookMethod/hookAllMethods` 与
> `IXposedHookLoadPackage`，两套框架都提供。只是 LSPosed 对新系统支持有限，遇到注入问题时请先换 Vector 复现。

## 实现

按「纯逻辑 / 反射 / 挂钩」分三层：

| 文件 | 作用 |
| --- | --- |
| [`ProviderPolicy.java`](module/src/com/elyezyc/rikkahubnospon/ProviderPolicy.java) | 纯函数：给定规整前后的列表，算出该保留哪些（可单独跑单测） |
| [`ProviderGuard.java`](module/src/com/elyezyc/rikkahubnospon/ProviderGuard.java) | 找锚点、`bootstrap`、`applyTo`、装钩子 |
| [`Reflect.java`](module/src/com/elyezyc/rikkahubnospon/Reflect.java) | 反射小工具，异常一律吞掉交给调用方判断 |
| [`HookEntry.java`](module/src/com/elyezyc/rikkahubnospon/HookEntry.java) | `IXposedHookLoadPackage` 入口，只对宿主包名生效 |
| [`MainActivity.java`](module/src/com/elyezyc/rikkahubnospon/MainActivity.java) | 模块信息页（中英双语） |

挂钩点（都按名字找，找不到只记日志）：

| 锚点 | 用途 |
| --- | --- |
| `SettingsNormalizationKt#normalized(Settings)Settings` | 后处理返回值：过滤提供商、刷 `builtIn` |
| `Settings#providers` 字段 | 直接写回过滤后的列表（`Settings` 是 `val` + `final` 字段，ART 允许反射改实例字段） |
| `DefaultProvidersKt#DEFAULT_PROVIDERS` | 取出内置条目身份集合，并把它们的 `builtIn` 预先清掉，之后所有 `copyProvider` 副本都跟着是 `false` |
| `ProviderSetting$*.getBuiltIn()Z` | 兜底：即使真机上字段改写被拦，UI 读到的也是 `false` |

两个取舍值得记一下：

- **改字段而不是只钩 getter**：`Settings$$serializer` 落盘时读的是字段、不是 getter，
  只钩 getter 会出现「界面能删、盘上没删」。
- **不用 lambda**：`javac` 以 `android.jar` 为 bootclasspath 编译时找不到 `LambdaMetafactory`，
  统一用匿名内部类。

## 校验

不依赖真机的三层校验，`./verify.sh` 一条命令跑完：

```bash
./build.sh     # aapt2 + javac + d8 + zipalign + apksigner，产物在 dist/
./verify.sh    # 三层校验，全绿才算通过
```

| 层 | 实现 | 结果 |
| --- | --- | --- |
| 反射锚点对照官方发布包 | [`tools/verify_anchors.py`](tools/verify_anchors.py) 用 `dexdump` 扫 RikkaHub 3.3.0 官方 APK | 16 项通过 |
| 过滤规则单测 | [`ProviderPolicyTest.java`](tests/src/com/elyezyc/rikkahubnospon/ProviderPolicyTest.java) | 12 项断言通过 |
| 反射层端到端 | [`ProviderGuardTest.java`](tests/src/com/elyezyc/rikkahubnospon/ProviderGuardTest.java) | 17 项断言通过 |
| 产物 APK 检查 | [`tools/verify_apk.py`](tools/verify_apk.py) | 通过 |

- 第二、三层用 [`tests/run.sh`](tests/run.sh) 在普通 JVM 上跑，不需要设备。
- 宿主替身放在 [`tests/src/me`](tests/src/me) 下，包名、字段名、`withBuiltInDefaults` 语义都与真实实现一致，
  所以 `bootstrap` + `applyTo` 的反射路径是真跑过的。
- 产物 dex 里对 `XposedBridge.log/hookMethod/hookAllMethods`、`MethodHookParam.args/getResult/setResult`
  的引用描述符与官方 `api-82.jar` 逐条比对一致，排除了运行期 `NoSuchMethodError`。

> **1.0.0 尚未在真机上做运行验证。**
> 已经做到可离线验证的是：锚点对照官方发布包、过滤逻辑与反射层的 JVM 单测、产物结构检查。
> 仍然需要在设备上确认的两个运行时行为是：ART 上改 `final` 字段是否放行、框架注入是否早于宿主初始化。
> 如果日志里出现「写不回 providers 字段」，说明这条路径在你的设备上被拦了，请把日志带回来。

## 构建环境

`build.sh` 不需要 Gradle，只要 JDK 17 + Android build-tools + 官方 `api-82.jar`：

```bash
# 首次：拉 JDK / build-tools / platform / Xposed API
# 需要走代理时设 PROXY（换成你自己的代理地址；直连就不要设）
export PROXY=http://127.0.0.1:7890
./tools/fetch_toolchain.sh

./build.sh
./verify.sh
```

- 工具链脚本：[`tools/fetch_toolchain.sh`](tools/fetch_toolchain.sh)，支持 `PROXY` 环境变量；直连时不用设
- 构建脚本：[`build.sh`](build.sh)；校验脚本：[`verify.sh`](verify.sh)
- 签名信息从 `Key/keystore.properties` 读（`storePassword` / `keyAlias` / `keyPassword`），
  也可以用 `KS_PASS` / `KEY_ALIAS` / `KEY_PASS` 环境变量覆盖。
- 产物落在 `dist/RikkaHubNoSPON-<版本>.apk`，本地保留、不入库；发版时把它作为附件传到 Release 上。

## 目录

```
module/            模块源码与资源（AndroidManifest.xml、res/、assets/xposed_init、src/）
tests/             JVM 单元测试 + 宿主替身
tools/             fetch_toolchain.sh、锚点校验、产物校验
build.sh           构建（产物写到 dist/）
verify.sh          三层校验
```

仓库里只有文本：源码、脚本、文档、许可证。APK 和第三方 jar 都不入库。

以下这些**不在仓库里**，只存在于本地，或按需重新获取：

| 路径 | 说明 |
| --- | --- |
| `dist/` | 构建产物（APK），只发 Release；已在 `.gitignore` 排除 |
| `tools/xposed-api-82.jar` | 第三方二进制，`fetch_toolchain.sh` 按需下载 |
| `Key/` | 签名密钥库、口令文件、SSH 私钥；已在 `.gitignore` 排除 |
| `rikkahub-3.3.0.apk` | 官方发布包，锚点校验用；本地缺失时 `verify_anchors.py` 会自动下载 |
| `rikkahub/` | RikkaHub 源码克隆，供对照阅读（不是构建依赖），需要时 `git clone --depth 1 https://github.com/rikkahub/rikkahub.git` |

## 范围之外

- 内置的语音合成（TTS）、语音识别（ASR）、媒体生成提供商同样有「按 id 补齐」的逻辑，但不在本模块处理范围内。
- 本模块只改「提供商列表怎么规整」这一件事，不涉及解锁任何付费功能、不改请求、不注入网络流量。

## 许可证

GPL-3.0-or-later，全文见 [`LICENSE`](LICENSE)。
