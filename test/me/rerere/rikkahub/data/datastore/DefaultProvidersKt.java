/*
 * 宿主 DefaultProvidersKt 的替身：只看静态字段 DEFAULT_PROVIDERS。
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.rerere.rikkahub.data.datastore;

import java.util.Arrays;
import java.util.List;

import me.rerere.ai.provider.ProviderSetting;

public abstract class DefaultProvidersKt {

    /** 对应宿主里那 21 个自带提供商，这里取 4 个有代表性的。 */
    public static final List<ProviderSetting> DEFAULT_PROVIDERS = Arrays.asList(
            new ProviderSetting.OpenAI("openai", true),
            new ProviderSetting.Google("gemini", true),
            new ProviderSetting.OpenAI("aihubmix", true),
            new ProviderSetting.OpenAI("deepseek", true));
}
