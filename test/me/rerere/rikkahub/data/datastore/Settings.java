/*
 * 宿主 Settings 的替身：只有本模块用到的 providers 字段和 getProviders()。
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.rerere.rikkahub.data.datastore;

import java.util.List;

import me.rerere.ai.provider.ProviderSetting;

public final class Settings {

    private final List<ProviderSetting> providers;

    public Settings(List<ProviderSetting> providers) {
        this.providers = providers;
    }

    public List<ProviderSetting> getProviders() {
        return providers;
    }
}
