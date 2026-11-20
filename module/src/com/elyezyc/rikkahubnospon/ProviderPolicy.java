/*
 * RikkaHubNoSPON - 阻止 RikkaHub 自动写入内置提供商，并允许删除自带提供商
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.elyezyc.rikkahubnospon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 纯逻辑：决定 {@code Settings.providers} 规整之后能留下哪些条目。
 *
 * <p>RikkaHub 的 {@code Settings.normalized()} 每次读盘和每次写入都会跑一遍
 * {@code withBuiltInDefaults()}：把 {@code DEFAULT_PROVIDERS} 里缺掉的条目重新补回列表。
 * 于是用户删掉的自带提供商会被悄悄写回来。这里只做一件事——把「不是用户自己保存过的」
 * 条目从结果里去掉。
 *
 * <p>不依赖 Xposed、不依赖 Android，可以直接在普通 JVM 上跑单元测试。
 */
public final class ProviderPolicy {

    /** 从宿主对象上取 id（用 equals 比较，不做字符串化）。 */
    public interface IdOf {
        Object idOf(Object provider);
    }

    private ProviderPolicy() {
    }

    /**
     * @param inputProviders       规整前的提供商列表（用户盘上保存的那一份）
     * @param resultProviders      规整后的提供商列表（宿主刚补过内置条目）
     * @param pristineDefaultList  inputProviders 是否就是 {@code DEFAULT_PROVIDERS} 这个列表实例本身
     * @param idOf                 取 id 的方式
     * @return 应当保留的条目；返回 {@link Collections#emptyList()} 表示一条都不留
     */
    public static List<Object> filter(List<?> inputProviders,
                                      List<?> resultProviders,
                                      boolean pristineDefaultList,
                                      IdOf idOf) {
        if (resultProviders == null || resultProviders.isEmpty()) {
            return Collections.emptyList();
        }

        // 输入就是内置默认列表本身：说明这个应用还没有把「用户自己的提供商」写进盘里
        // （全新安装、或提供商这一项还没落盘）。内置那一批一个都不留，避免它们被当成用户数据写回。
        if (pristineDefaultList) {
            return Collections.emptyList();
        }

        Set<Object> inputIds = new HashSet<>();
        if (inputProviders != null) {
            for (Object provider : inputProviders) {
                if (provider == null) {
                    continue;
                }
                Object id = idOf.idOf(provider);
                if (id != null) {
                    inputIds.add(id);
                }
            }
        }

        List<Object> kept = new ArrayList<>(resultProviders.size());
        Set<Object> keptIds = new HashSet<>();
        for (Object provider : resultProviders) {
            if (provider == null) {
                continue;
            }
            Object id = idOf.idOf(provider);
            // 只留用户盘上本来就有 id 的那些：本次被补回来的内置条目会掉出去；
            // 同一个 id 重复出现时只留第一份（宿主自己会 distinctBy，这里兜个底）
            if (id != null && inputIds.contains(id) && keptIds.add(id)) {
                kept.add(provider);
            }
        }
        return kept;
    }

    /** 结果列表是否和期望完全一致（按顺序逐个比引用），用来决定要不要动宿主的字段。 */
    public static boolean sameElements(List<?> a, List<?> b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) {
                return false;
            }
        }
        return true;
    }
}
