/*
 * RikkaHubNoSPON - 阻止 RikkaHub 自动写入内置提供商，并允许删除自带提供商
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.elyezyc.rikkahubnospon;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 全部挂钩逻辑。
 *
 * <p>只做两件事：
 * <ol>
 *   <li><b>不再自动写入内置提供商</b>：{@code Settings.normalized()} 的返回值里，凡是本次被
 *       「补回来」的条目一律丢掉。判断依据是 {@code normalized} 的入参——用户自己保存过、
 *       或者在设置页里刚加进来的提供商，一定出现在入参里；被自动补回来的不在。</li>
 *   <li><b>允许删除自带提供商</b>：把这些 provider 对象的 {@code builtIn} 字段清成 false，
 *       设置页的删除入口就出现了（列表项的 {@code enabled = !provider.builtIn}、
 *       详情页的删除按钮同理）。{@code builtIn} 是 {@code @Transient} 的派生值，
 *       不落盘，每次规整时由代码按 id 重新算出来，所以必须每轮都清一次。</li>
 * </ol>
 *
 * <p>分成三段，前两段不碰 Xposed 的 native 方法，可以在普通 JVM 上拿「宿主替身」跑通：
 * {@link #bootstrap} 找锚点、{@link #applyTo} 处理规整结果、{@link #install} 挂钩子。
 *
 * <p>宿主版本差异只体现在命名上，全部按名字找、找不到就记日志跳过，不抛异常。
 */
final class ProviderGuard {

    private static final String TAG = "RikkaHubNoSPON: ";

    private static final String CLASS_SETTINGS = "me.rerere.rikkahub.data.datastore.Settings";
    private static final String CLASS_NORMALIZATION =
            "me.rerere.rikkahub.data.datastore.SettingsNormalizationKt";
    private static final String CLASS_DEFAULTS = "me.rerere.rikkahub.data.datastore.DefaultProvidersKt";
    private static final String CLASS_PROVIDER_BASE = "me.rerere.ai.provider.ProviderSetting";

    private static final String METHOD_NORMALIZED = "normalized";
    private static final String METHOD_GET_BUILT_IN = "getBuiltIn";
    private static final String METHOD_GET_ID = "getId";
    private static final String FIELD_PROVIDERS = "providers";
    private static final String FIELD_DEFAULT_PROVIDERS = "DEFAULT_PROVIDERS";
    private static final String FIELD_BUILT_IN = "builtIn";

    /** 每个 provider 子类都有自己的 builtIn 字段，按类缓存。 */
    private static final ConcurrentHashMap<Class<?>, Field> BUILT_IN_FIELDS = new ConcurrentHashMap<>();

    private static volatile Field sProvidersField;
    private static volatile Object sDefaultProvidersList;
    private static volatile ProviderPolicy.IdOf sIdOf;

    /** 日志出口，测试时可以换掉（默认走 XposedBridge）。 */
    interface Sink {
        void log(String message);
    }

    private static final Sink DEFAULT_SINK = new Sink() {
        @Override
        public void log(String message) {
            try {
                XposedBridge.log(message);
            } catch (Throwable ignored) {
                // 框架没准备好时静默，不能因为打日志把宿主搞崩
            }
        }
    };

    private static volatile Sink sSink = DEFAULT_SINK;
    /** 记录类日志的额度，避免刷屏（错误不计入）。 */
    private static int sLogBudget = 12;

    private ProviderGuard() {
    }

    /** 在宿主进程里装挂钩。 */
    static boolean install(ClassLoader classLoader) {
        if (!bootstrap(classLoader)) {
            return false;
        }
        boolean hooked = false;
        try {
            Class<?> settingsClass = Class.forName(CLASS_SETTINGS, false, classLoader);
            hooked = hookNormalized(classLoader, settingsClass);
            hookBuiltInGetters(Class.forName(CLASS_PROVIDER_BASE, false, classLoader));
        } catch (Throwable t) {
            logError("挂挂钩失败：" + t);
        }
        return hooked;
    }

    /**
     * 只做反射查找和内置对象的预处理，不调用任何 Xposed 方法。
     *
     * @return 关键锚点（Settings.providers、ProviderSetting.getId）是否都找到了
     */
    static boolean bootstrap(ClassLoader classLoader) {
        try {
            Class<?> settingsClass = Class.forName(CLASS_SETTINGS, false, classLoader);
            Field providersField = Reflect.findField(settingsClass, FIELD_PROVIDERS);
            if (!List.class.isAssignableFrom(providersField.getType())) {
                logError("providers 字段不是 List：" + providersField.getType());
                return false;
            }
            sProvidersField = providersField;

            Class<?> providerBase = Class.forName(CLASS_PROVIDER_BASE, false, classLoader);
            final Method getId = Reflect.findNoArgMethod(providerBase, METHOD_GET_ID);
            if (getId == null) {
                logError("找不到 ProviderSetting#getId，无法比较提供商身份");
                return false;
            }
            providerBase.getDeclaredMethod(METHOD_GET_BUILT_IN); // 顺便确认锚点还在
            sIdOf = new ProviderPolicy.IdOf() {
                @Override
                public Object idOf(Object provider) {
                    return Reflect.invoke(getId, provider);
                }
            };

            // 内置那一批对象的 builtIn 直接清掉：之后所有 copyProvider 出来的副本都跟着是 false
            Object defaults = readDefaultProviders(classLoader);
            if (defaults instanceof List) {
                sDefaultProvidersList = defaults;
                int cleared = clearBuiltIn((List<?>) defaults);
                info("已清除 " + cleared + "/" + ((List<?>) defaults).size()
                        + " 个内置提供商的 builtIn 标记");
            } else {
                info("没读到 DEFAULT_PROVIDERS，全新安装的判定将不可用（其余功能不受影响）");
            }
            return true;
        } catch (Throwable t) {
            logError("初始化失败：" + t);
            return false;
        }
    }

    /** 读 {@code DefaultProvidersKt.DEFAULT_PROVIDERS}（会顺带触发该类的 <clinit>）。 */
    private static Object readDefaultProviders(ClassLoader classLoader) {
        try {
            Class<?> defaultsClass = Class.forName(CLASS_DEFAULTS, false, classLoader);
            Field field = Reflect.findField(defaultsClass, FIELD_DEFAULT_PROVIDERS);
            return Reflect.get(field, null); // 静态字段
        } catch (Throwable t) {
            logError("读 DEFAULT_PROVIDERS 失败：" + t);
            return null;
        }
    }

    /** 挂 {@code SettingsNormalizationKt.normalized(Settings)}。 */
    private static boolean hookNormalized(ClassLoader classLoader, Class<?> settingsClass) {
        try {
            Class<?> normalization = Class.forName(CLASS_NORMALIZATION, false, classLoader);
            final XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object input = (param.args != null && param.args.length > 0) ? param.args[0] : null;
                        applyTo(input, param.getResult(), settingsClass);
                    } catch (Throwable t) {
                        logError("处理 normalized 返回值时出错：" + t);
                    }
                }
            };
            int count = XposedBridge.hookAllMethods(normalization, METHOD_NORMALIZED, hook).size();
            if (count == 0) {
                logError("没挂上 " + CLASS_NORMALIZATION + "#" + METHOD_NORMALIZED
                        + "（宿主版本可能变了）");
                return false;
            }
            info("已挂上 normalized，共 " + count + " 个重载");
            return true;
        } catch (Throwable t) {
            logError("挂 normalized 失败：" + t);
            return false;
        }
    }

    /**
     * 处理一次规整的输入和输出：把被自动补回来的提供商从结果里去掉，并清掉 builtIn。
     *
     * @param input          {@code normalized()} 的入参（用户盘上那一份）
     * @param result         {@code normalized()} 的返回值
     * @param settingsClass  {@code Settings} 类
     * @return 是否真的改动了结果
     */
    static boolean applyTo(Object input, Object result, Class<?> settingsClass) {
        Field providersField = sProvidersField;
        ProviderPolicy.IdOf idOf = sIdOf;
        if (providersField == null || idOf == null || input == null || result == null) {
            return false;
        }
        if (!settingsClass.isInstance(input) || !settingsClass.isInstance(result)) {
            return false;
        }

        List<?> inputProviders = asList(Reflect.get(providersField, input));
        List<?> resultProviders = asList(Reflect.get(providersField, result));

        // 入参就是内置默认列表本身：这个应用还没写过自己的提供商（全新安装 / 首次读盘）
        boolean pristine = sDefaultProvidersList != null && inputProviders == sDefaultProvidersList;

        List<Object> kept = ProviderPolicy.filter(inputProviders, resultProviders, pristine, idOf);
        for (Object provider : kept) {
            clearBuiltIn(provider);
        }

        if (ProviderPolicy.sameElements(resultProviders, kept)) {
            return false;
        }
        if (!Reflect.set(providersField, result, kept)) {
            logError("写不回 providers 字段，本次未能拦截");
            return false;
        }
        info("提供商 " + resultProviders.size() + " → " + kept.size()
                + (pristine ? "（全新安装，内置条目全部不写入）" : "（丢弃本次自动补回的条目）"));
        return true;
    }

    /** 把所有 provider 子类的 {@code getBuiltIn()} 兜底改成 false。 */
    private static void hookBuiltInGetters(Class<?> providerBase) {
        int hooked = 0;
        for (Class<?> candidate : providerBase.getDeclaredClasses()) {
            try {
                Method method = candidate.getDeclaredMethod(METHOD_GET_BUILT_IN);
                int modifiers = method.getModifiers();
                // 只挂子类自己实现的；基类上那个是 abstract，挂它没有意义
                if (Modifier.isAbstract(modifiers) || Modifier.isStatic(modifiers)) {
                    continue;
                }
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(Boolean.FALSE);
                    }
                });
                hooked++;
            } catch (Throwable ignored) {
                // 这个子类没有自己的 getBuiltIn，跳过
            }
        }
        info("已挂上 " + hooked + " 个 ProviderSetting 子类的 getBuiltIn");
    }

    /** 把某个 provider 对象的 builtIn 字段清成 false。 */
    private static boolean clearBuiltIn(Object provider) {
        if (provider == null) {
            return false;
        }
        return Reflect.setBoolean(builtInFieldOf(provider.getClass()), provider, false);
    }

    private static int clearBuiltIn(List<?> providers) {
        int cleared = 0;
        for (Object provider : providers) {
            if (clearBuiltIn(provider)) {
                cleared++;
            }
        }
        return cleared;
    }

    private static Field builtInFieldOf(Class<?> providerClass) {
        Field cached = BUILT_IN_FIELDS.get(providerClass);
        if (cached != null) {
            return cached;
        }
        try {
            Field field = Reflect.findField(providerClass, FIELD_BUILT_IN);
            BUILT_IN_FIELDS.put(providerClass, field);
            return field;
        } catch (Throwable t) {
            // 这类没有 builtIn 字段（比如 Companion）：不缓存 null（ConcurrentHashMap 不收 null），
            // 反正找不到的类只会有那么几个，重试一次也很便宜
            return null;
        }
    }

    private static List<?> asList(Object value) {
        return value instanceof List ? (List<?>) value : Collections.emptyList();
    }

    // ---- 日志 ----

    static void info(String message) {
        Sink sink = sSink;
        if (sLogBudget > 0) {
            sLogBudget--;
            sink.log(TAG + message);
        }
    }

    static void logError(String message) {
        sSink.log(TAG + "错误： " + message);
    }

    /** 仅供测试：换掉日志出口。 */
    static void setSinkForTest(Sink sink) {
        sSink = sink == null ? DEFAULT_SINK : sink;
    }
}
