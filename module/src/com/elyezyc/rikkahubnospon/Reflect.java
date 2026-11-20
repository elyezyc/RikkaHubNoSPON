/*
 * RikkaHubNoSPON - 阻止 RikkaHub 自动写入内置提供商，并允许删除自带提供商
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.elyezyc.rikkahubnospon;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** 一点反射小工具，全部吞掉异常并把失败交给调用方判断。 */
final class Reflect {

    private Reflect() {
    }

    /** 沿继承链找字段。 */
    static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            try {
                Field field = cursor.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // 往父类走
            }
        }
        throw new NoSuchFieldException(type.getName() + "#" + name);
    }

    /** 读字段，失败返回 null。 */
    static Object get(Field field, Object target) {
        if (field == null) {
            return null;
        }
        try {
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 写字段。<b>要求字段加过 setAccessible</b>——Kotlin 的 {@code val} 编译出来是
     * {@code private final}，Android 的 ART 允许通过反射改这类实例字段（Java 的规则更严，但这里跑在 ART 上）。
     */
    static boolean set(Field field, Object target, Object value) {
        if (field == null) {
            return false;
        }
        try {
            field.set(target, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 写 boolean 字段。 */
    static boolean setBoolean(Field field, Object target, boolean value) {
        if (field == null) {
            return false;
        }
        try {
            field.setBoolean(target, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 读 boolean 字段，失败按 false 算。 */
    static boolean getBoolean(Field field, Object target) {
        if (field == null) {
            return false;
        }
        try {
            return field.getBoolean(target);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 找无参方法。 */
    static Method findNoArgMethod(Class<?> type, String name) {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            try {
                Method method = cursor.getDeclaredMethod(name);
                method.setAccessible(true);
                return method;
            } catch (Throwable ignored) {
                // 往父类走
            }
        }
        return null;
    }

    /** 调用无参方法，失败返回 null。 */
    static Object invoke(Method method, Object target) {
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
