/*
 * RikkaHubNoSPON - 阻止 RikkaHub 自动写入内置提供商，并允许删除自带提供商
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.elyezyc.rikkahubnospon;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 模块入口，类名要和 {@code assets/xposed_init} 里那行一致。
 *
 * <p>只在 RikkaHub 自己的进程里装挂钩，别的应用一概不碰。
 */
public final class HookEntry implements IXposedHookLoadPackage {

    /** 宿主包名。RikkaHub 的 GitHub 版和 Play 版共用这一个。 */
    private static final String HOST_PACKAGE = "me.rerere.rikkahub";
    /** 宿主 debug 构建，方便自己改着玩。 */
    private static final String HOST_PACKAGE_DEBUG = "me.rerere.rikkahub.debug";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) {
            return;
        }
        if (!HOST_PACKAGE.equals(lpparam.packageName)
                && !HOST_PACKAGE_DEBUG.equals(lpparam.packageName)) {
            return;
        }
        try {
            boolean ok = ProviderGuard.install(lpparam.classLoader);
            XposedBridge.log("[RikkaHubNoSPON] 已注入 " + lpparam.packageName
                    + (ok ? "，提供商拦截生效" : "，但关键挂钩没装上，请查看上面的日志"));
        } catch (Throwable t) {
            XposedBridge.log("[RikkaHubNoSPON] 注入失败：" + t);
            XposedBridge.log(t);
        }
    }
}
