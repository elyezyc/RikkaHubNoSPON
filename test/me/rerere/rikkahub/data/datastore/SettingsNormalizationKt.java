/*
 * 宿主 SettingsNormalizationKt 的替身：照抄 RikkaHub 3.3.0
 * Settings.withBuiltInDefaults() / withoutInvalidReferences() 里与本模块相关的语义。
 *
 * 真实实现（反编译 + 源码一致）：
 *   1. providers 为空时用 DEFAULT_PROVIDERS 顶上；
 *   2. 逐个把 DEFAULT_PROVIDERS 里缺的补回来（copyProvider，builtIn 跟着默认值走）；
 *   3. 最后按 id 去重。
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.rerere.rikkahub.data.datastore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.rerere.ai.provider.ProviderSetting;

public abstract class SettingsNormalizationKt {

    public static Settings normalized(Settings settings) {
        List<ProviderSetting> providers = settings.getProviders().isEmpty()
                ? new ArrayList<ProviderSetting>(DefaultProvidersKt.DEFAULT_PROVIDERS)
                : new ArrayList<ProviderSetting>(settings.getProviders());

        // withBuiltInDefaults：缺的按 id 补回来
        for (ProviderSetting defaultProvider : DefaultProvidersKt.DEFAULT_PROVIDERS) {
            if (!containsId(providers, idOf(defaultProvider))) {
                providers.add(defaultProvider);
            }
        }

        // builtIn / 描述始终以代码里的为准
        Map<Object, ProviderSetting> defaults = indexDefaults();
        List<ProviderSetting> mapped = new ArrayList<>(providers.size());
        for (ProviderSetting provider : providers) {
            ProviderSetting defaultProvider = defaults.get(idOf(provider));
            mapped.add(defaultProvider == null
                    ? provider
                    : provider.withBuiltIn(defaultProvider.getBuiltIn()));
        }

        // withoutInvalidReferences：按 id 去重，顺序不变
        Map<Object, ProviderSetting> distinct = new LinkedHashMap<>();
        for (ProviderSetting provider : mapped) {
            Object id = idOf(provider);
            if (!distinct.containsKey(id)) {
                distinct.put(id, provider);
            }
        }
        return new Settings(new ArrayList<>(distinct.values()));
    }

    private static Object idOf(ProviderSetting provider) {
        return provider.getId();
    }

    private static boolean containsId(List<ProviderSetting> providers, Object id) {
        for (ProviderSetting provider : providers) {
            if (idOf(provider).equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static Map<Object, ProviderSetting> indexDefaults() {
        Map<Object, ProviderSetting> map = new LinkedHashMap<>();
        for (ProviderSetting provider : DefaultProvidersKt.DEFAULT_PROVIDERS) {
            map.put(idOf(provider), provider);
        }
        return map;
    }
}
