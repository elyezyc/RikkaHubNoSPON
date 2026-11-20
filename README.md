# RikkaHubNoSPON

RikkaHub 的 Xposed 模块。阻止它自动写入内置提供商，并让自带提供商可以被删除。

- 包名：`com.elyezyc.rikkahubnospon`
- 宿主：RikkaHub（`me.rerere.rikkahub`），对照版本 [3.3.0](https://github.com/rikkahub/rikkahub/tree/3.3.0)
- 模块 API：Xposed 82
- 许可证：GPL-3.0-or-later

安装包在 [Releases](https://github.com/elyezyc/RikkaHubNoSPON/releases)。

## 问题

RikkaHub 每次读盘、每次写入设置都会规整一遍提供商列表
（[SettingsNormalization.kt](https://github.com/rikkahub/rikkahub/blob/3.3.0/app/src/main/java/me/rerere/rikkahub/data/datastore/SettingsNormalization.kt)）：

```kotlin
internal fun Settings.normalized(): Settings =
    withBuiltInDefaults().withoutInvalidReferences().withValuesInRange()

internal fun Settings.withBuiltInDefaults(): Settings {
    var providers = this.providers.ifEmpty { DEFAULT_PROVIDERS }.toMutableList()
    DEFAULT_PROVIDERS.forEach { defaultProvider ->
        if (providers.none { it.id == defaultProvider.id }) {
            providers.add(defaultProvider.copyProvider())
        }
    }
    providers = providers.map { provider ->
        val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
        ...
        defaultProvider != null -> provider.copyProvider(
            builtIn = defaultProvider.builtIn,
            ...
```

缺掉的条目被补回来，`builtIn` 也被重新标成 `true`。于是删掉的提供商下次启动又回来了，删除入口也一直不可用：
列表页是 `enabled = !provider.builtIn`
（[SettingProviderPage.kt](https://github.com/rikkahub/rikkahub/blob/3.3.0/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProviderPage.kt)），
详情页是 `if (!internalProvider.builtIn)`
（[SettingProviderDetailPage.kt](https://github.com/rikkahub/rikkahub/blob/3.3.0/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProviderDetailPage.kt)）。

## 做法

挂钩 `SettingsNormalizationKt.normalized`，在它返回后：

- 按 id 比对入参，丢弃这次被补回来的条目；
- 把留下来的条目的 `builtIn` 清成 `false`。

入参里出现过的 id 视为用户自己保存的，所以手动添加的、备份恢复的、填过 Key 的自带提供商都会保留；
[DefaultProviders.kt](https://github.com/rikkahub/rikkahub/blob/3.3.0/app/src/main/java/me/rerere/rikkahub/data/datastore/DefaultProviders.kt)
里那 21 个条目（含 AiHubMix、APIMart、随想AI网关、STA1N API 等带推广链接的）不会再被写回。

## 使用

推荐用 [Vector](https://github.com/JingMatrix/Vector) 加载。它是 Zygisk 模块，支持 Android 8.1 到 17 Beta，
模块 API 与原始 Xposed 保持一致。

1. 在 [Releases](https://github.com/elyezyc/RikkaHubNoSPON/releases) 下载 APK 并安装。
2. 装 Vector：从 [Vector Releases](https://github.com/JingMatrix/Vector/releases) 下载系统模块包，
   用 Magisk 或 KernelSU 刷入，确认 Zygisk 已启用（必要时配 [NeoZygisk](https://github.com/JingMatrix/NeoZygisk)），重启设备。
3. 从系统通知打开 Vector 管理界面，在模块列表里启用 `RikkaHubNoSPON`。
4. 作用域勾选 RikkaHub（`me.rerere.rikkahub`）。清单里已声明 `xposedscope`，一般会自动勾好。
5. 强制停止 RikkaHub 再打开。

生效后，RikkaHub 设置里的提供商列表只剩自己添加的条目，自带条目可以正常删除。
Vector 日志页过滤 `RikkaHubNoSPON` 能看到 `提供商 N → M` 记录，锚点没挂上会输出错误。

LSPosed 也可以。模块只用到 API 82 的 `XposedBridge.hookMethod`、`hookAllMethods` 和 `IXposedHookLoadPackage`。

## 实现

| 文件 | 作用 |
| --- | --- |
| [`ProviderPolicy.java`](module/src/com/elyezyc/rikkahubnospon/ProviderPolicy.java) | 纯函数，算出规整后该保留哪些条目 |
| [`ProviderGuard.java`](module/src/com/elyezyc/rikkahubnospon/ProviderGuard.java) | 找锚点、`bootstrap`、`applyTo`、装钩子 |
| [`Reflect.java`](module/src/com/elyezyc/rikkahubnospon/Reflect.java) | 反射工具 |
| [`HookEntry.java`](module/src/com/elyezyc/rikkahubnospon/HookEntry.java) | `IXposedHookLoadPackage` 入口，只对宿主包名生效 |
| [`MainActivity.java`](module/src/com/elyezyc/rikkahubnospon/MainActivity.java) | 模块信息页 |

挂钩的锚点：

| 锚点 | 用途 |
| --- | --- |
| `SettingsNormalizationKt#normalized(Settings)Settings` | 后处理返回值：过滤提供商、刷 `builtIn` |
| `Settings#providers` | 写回过滤后的列表 |
| `DefaultProvidersKt#DEFAULT_PROVIDERS` | 内置条目身份，并预先清掉它们的 `builtIn` |
| `ProviderSetting$*.getBuiltIn()Z` | 兜底，UI 读到的始终是 `false` |

`Settings.providers` 是 `val` 加 `final` 字段，落盘时序列化器读的是字段而不是 getter，所以这里直接改字段；
只钩 getter 会出现界面上删了、盘上没删。

## 构建

需要 JDK 17、Android build-tools 和官方 `api-82.jar`，不用 Gradle：

```bash
./tools/fetch_toolchain.sh   # 首次执行，需要代理时先 export PROXY=http://127.0.0.1:7890
./build.sh
./verify.sh
```

签名信息从 `Key/keystore.properties` 读（`storePassword`、`keyAlias`、`keyPassword`），
也可以用 `KS_PASS`、`KEY_ALIAS`、`KEY_PASS` 覆盖。产物是 `dist/RikkaHubNoSPON-<版本>.apk`。

## 校验

`./verify.sh` 依次跑：

- 用 `dexdump` 扫官方 3.3.0 APK，逐项确认挂钩用的类、方法、字段都存在，共 16 项；
- JVM 单测：过滤规则 12 项断言，反射层 17 项断言，宿主替身在 `test/me` 下；
- 检查产物 APK 的入口、清单和 dex 内容。

尚未在真机上运行过。需要在设备上确认的是 ART 改 `final` 字段是否放行、框架注入是否早于宿主初始化。

## 目录

```
module/     模块源码与资源
test/       JVM 单测和宿主替身
tools/      工具链下载、锚点校验、产物校验
build.sh    构建
verify.sh   校验
```

`dist/`、`Key/`、`tools/xposed-api-82.jar` 不在仓库里：前两个由 `.gitignore` 排除，jar 由 `fetch_toolchain.sh` 下载。

## 范围

内置的 TTS、ASR、媒体生成提供商同样有按 id 补齐的逻辑，本模块不处理；
也不涉及解锁付费能力、修改请求或注入网络流量。

## 许可证

GPL-3.0-or-later，见 [LICENSE](LICENSE)。
