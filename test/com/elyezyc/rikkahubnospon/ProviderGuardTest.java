/*
 * ProviderGuard 的端到端测试：拿「宿主替身」（同包名、同字段名、同语义的假类）跑通
 * bootstrap + applyTo 两段真实反射代码，不需要真机也不需要 Xposed 运行环境。
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.elyezyc.rikkahubnospon;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import me.rerere.ai.provider.ProviderSetting;
import me.rerere.rikkahub.data.datastore.DefaultProvidersKt;
import me.rerere.rikkahub.data.datastore.Settings;
import me.rerere.rikkahub.data.datastore.SettingsNormalizationKt;

public final class ProviderGuardTest {

    private static int checks = 0;
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        ProviderGuard.setSinkForTest(new ProviderGuard.Sink() {
            @Override
            public void log(String message) {
                System.out.println("      [模块日志] " + message);
            }
        });

        ClassLoader cl = ProviderGuardTest.class.getClassLoader();

        // ---------- 0. 前置：替身里的内置条目初始都是 builtIn = true ----------
        check("替身初始状态：内置条目 builtIn = true",
                builtInFlags(DefaultProvidersKt.DEFAULT_PROVIDERS), Arrays.asList(true, true, true, true));

        // ---------- 1. bootstrap：锚点能找到、内置条目被清标 ----------
        check("bootstrap 成功", ProviderGuard.bootstrap(cl), true);
        check("bootstrap 后内置条目 builtIn 全为 false",
                builtInFlags(DefaultProvidersKt.DEFAULT_PROVIDERS),
                Arrays.asList(false, false, false, false));

        Class<?> settingsClass = Settings.class;

        // ---------- 2. 全新安装：入参就是默认列表本身 ----------
        Settings freshDisk = new Settings(DefaultProvidersKt.DEFAULT_PROVIDERS);
        Settings freshNormalized = SettingsNormalizationKt.normalized(freshDisk);
        check("全新安装：规整后宿主仍然带着 4 个内置条目",
                ids(freshNormalized.getProviders()), Arrays.asList("openai", "gemini", "aihubmix", "deepseek"));
        ProviderGuard.applyTo(freshDisk, freshNormalized, settingsClass);
        check("全新安装：模块把内置条目全部丢掉", ids(freshNormalized.getProviders()),
                Collections.emptyList());

        // ---------- 3. 删掉两个自带提供商，不被写回 ----------
        Settings afterDelete = new Settings(Arrays.asList(
                DefaultProvidersKt.DEFAULT_PROVIDERS.get(0),
                DefaultProvidersKt.DEFAULT_PROVIDERS.get(3)));
        Settings normalizedAfterDelete = SettingsNormalizationKt.normalized(afterDelete);
        check("删掉两个后宿主又把它们补回来了",
                ids(normalizedAfterDelete.getProviders()),
                Arrays.asList("openai", "deepseek", "gemini", "aihubmix"));
        ProviderGuard.applyTo(afterDelete, normalizedAfterDelete, settingsClass);
        check("模块把补回来的再丢掉", ids(normalizedAfterDelete.getProviders()),
                Arrays.asList("openai", "deepseek"));

        // ---------- 4. 用户自己加的提供商保留 ----------
        ProviderSetting mine = new ProviderSetting.OpenAI("my-own", false);
        Settings customDisk = new Settings(Arrays.asList(mine));
        Settings customNormalized = SettingsNormalizationKt.normalized(customDisk);
        ProviderGuard.applyTo(customDisk, customNormalized, settingsClass);
        check("自定义提供商保留（内置的不跟着塞进来）",
                ids(customNormalized.getProviders()), Collections.singletonList("my-own"));

        // ---------- 5. 用户配置过的自带提供商保留，并且 builtIn 被清掉（可删除） ----------
        ProviderSetting configuredDeepSeek = new ProviderSetting.OpenAI("deepseek", true);
        Settings configuredDisk = new Settings(Arrays.asList(configuredDeepSeek));
        Settings configuredNormalized = SettingsNormalizationKt.normalized(configuredDisk);
        ProviderGuard.applyTo(configuredDisk, configuredNormalized, settingsClass);
        check("用户配置过的自带提供商保留",
                ids(configuredNormalized.getProviders()), Collections.singletonList("deepseek"));
        check("保留下来的自带提供商 builtIn = false（删除按钮可见）",
                builtInFlags(configuredNormalized.getProviders()), Collections.singletonList(false));

        // ---------- 6. 全删光：列表保持空 ----------
        Settings emptyDisk = new Settings(new ArrayList<ProviderSetting>());
        Settings emptyNormalized = SettingsNormalizationKt.normalized(emptyDisk);
        check("全删光时宿主会塞回全部内置条目",
                ids(emptyNormalized.getProviders()).size(), 4);
        ProviderGuard.applyTo(emptyDisk, emptyNormalized, settingsClass);
        check("全删光后模块保持空列表", ids(emptyNormalized.getProviders()),
                Collections.emptyList());

        // ---------- 7. 落盘后再读一遍：不会重新长回来 ----------
        Settings secondRead = SettingsNormalizationKt.normalized(emptyNormalized);
        ProviderGuard.applyTo(emptyNormalized, secondRead, settingsClass);
        check("空列表落盘后再次规整仍然是空", ids(secondRead.getProviders()),
                Collections.emptyList());

        // ---------- 8. 幂等：已经处理过的结果再处理一次不再改动 ----------
        Settings again = SettingsNormalizationKt.normalized(afterDelete);
        ProviderGuard.applyTo(afterDelete, again, settingsClass);
        boolean changedTwice = ProviderGuard.applyTo(afterDelete, again, settingsClass);
        check("重复处理不再改动", changedTwice, false);

        // ---------- 9. 宿主就算硬把 builtIn 标回 true，也会被清掉 ----------
        ProviderSetting forcedBuiltIn = new ProviderSetting.OpenAI("deepseek", true);
        Settings forcedDisk = new Settings(Collections.singletonList(forcedBuiltIn));
        Settings forcedResult = new Settings(Collections.singletonList(
                new ProviderSetting.OpenAI("deepseek", true)));
        ProviderGuard.applyTo(forcedDisk, forcedResult, settingsClass);
        check("宿主强制标记的 builtIn 也会被清掉",
                builtInFlags(forcedResult.getProviders()), Collections.singletonList(false));

        // ---------- 10. 异常入参不崩 ----------
        check("入参为 null 不崩", ProviderGuard.applyTo(null, forcedResult, settingsClass), false);
        check("结果类型不对不崩",
                ProviderGuard.applyTo("not a settings", forcedResult, settingsClass), false);

        System.out.println();
        if (failures == 0) {
            System.out.println("全部通过：" + checks + " 项断言");
        } else {
            System.out.println("失败 " + failures + " / " + checks + " 项断言");
            System.exit(1);
        }
    }

    private static List<Object> ids(List<ProviderSetting> providers) {
        List<Object> out = new ArrayList<>(providers.size());
        for (ProviderSetting provider : providers) {
            out.add(provider.getId());
        }
        return out;
    }

    private static List<Boolean> builtInFlags(List<ProviderSetting> providers) {
        List<Boolean> out = new ArrayList<>(providers.size());
        for (ProviderSetting provider : providers) {
            out.add(provider.getBuiltIn());
        }
        return out;
    }

    private static void check(String name, Object actual, Object expected) {
        checks++;
        boolean ok = actual == null ? expected == null : actual.equals(expected);
        if (!ok) {
            failures++;
            System.out.println("FAIL  " + name);
            System.out.println("      期望: " + expected);
            System.out.println("      实际: " + actual);
        } else {
            System.out.println("ok    " + name + "  " + actual);
        }
    }
}
