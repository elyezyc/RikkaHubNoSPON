/*
 * 宿主 ProviderSetting 的替身：结构照抄 RikkaHub 3.3.0 反编译结果——
 * 每个具体子类各自持有一个 private final boolean builtIn 字段，并实现 getBuiltIn()。
 * 仅供 JVM 单元测试使用，不参与模块打包。
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.rerere.ai.provider;

public abstract class ProviderSetting {

    private final Object id;
    private final boolean builtIn;

    ProviderSetting(Object id, boolean builtIn) {
        this.id = id;
        this.builtIn = builtIn;
    }

    public Object getId() {
        return id;
    }

    public boolean getBuiltIn() {
        return builtIn;
    }

    public abstract ProviderSetting withBuiltIn(boolean value);

    public static final class OpenAI extends ProviderSetting {
        public OpenAI(Object id, boolean builtIn) {
            super(id, builtIn);
        }

        @Override
        public ProviderSetting withBuiltIn(boolean value) {
            return new OpenAI(getId(), value);
        }
    }

    public static final class Google extends ProviderSetting {
        public Google(Object id, boolean builtIn) {
            super(id, builtIn);
        }

        @Override
        public ProviderSetting withBuiltIn(boolean value) {
            return new Google(getId(), value);
        }
    }

    public static final class Claude extends ProviderSetting {
        public Claude(Object id, boolean builtIn) {
            super(id, builtIn);
        }

        @Override
        public ProviderSetting withBuiltIn(boolean value) {
            return new Claude(getId(), value);
        }
    }
}
