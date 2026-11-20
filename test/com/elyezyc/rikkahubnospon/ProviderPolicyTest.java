/*
 * ProviderPolicy 的单元测试：不依赖 JUnit、不依赖 Android，直接用 JDK 跑。
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * 用 test/run.sh 跑。
 */
package com.elyezyc.rikkahubnospon;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class ProviderPolicyTest {

    /** 假的提供商，只带一个 id。 */
    static final class Provider {
        final String id;

        Provider(String id) {
            this.id = id;
        }

        @Override
        public String toString() {
            return "Provider(" + id + ")";
        }
    }

    private static final ProviderPolicy.IdOf ID_OF = new ProviderPolicy.IdOf() {
        @Override
        public Object idOf(Object provider) {
            return provider instanceof Provider ? ((Provider) provider).id : null;
        }
    };

    private static int failures = 0;
    private static int checks = 0;

    public static void main(String[] args) {
        // 内置那一批（宿主 DEFAULT_PROVIDERS）
        List<Provider> defaults = Arrays.asList(
                new Provider("openai"),
                new Provider("gemini"),
                new Provider("aihubmix"),
                new Provider("deepseek"));

        // 1. 全新安装：入参就是内置默认列表本身 → 一条都不写
        expectIds("全新安装（pristine）不写入任何内置提供商",
                ProviderPolicy.filter(defaults, defaults, true, ID_OF),
                Collections.emptyList());

        // 2. 用户删掉了两个：规整把它们补回来了，过滤器要把补回来的再丢掉
        List<Provider> afterDelete = Arrays.asList(defaults.get(0), defaults.get(3));
        List<Provider> normalizedAfterDelete = Arrays.asList(
                defaults.get(0), defaults.get(3), defaults.get(1), defaults.get(2));
        expectIds("删掉自带提供商后不会被重新写回",
                ProviderPolicy.filter(afterDelete, normalizedAfterDelete, false, ID_OF),
                Arrays.asList("openai", "deepseek"));

        // 3. 用户自己加的提供商要留下
        Provider mine = new Provider("my-own");
        expectIds("自定义提供商保留",
                ProviderPolicy.filter(Arrays.asList(defaults.get(0), mine),
                        Arrays.asList(defaults.get(0), mine), false, ID_OF),
                Arrays.asList("openai", "my-own"));

        // 4. 用户给自带提供商填了 key（盘上有这条）：保留，不删用户数据
        expectIds("用户配置过的自带提供商保留",
                ProviderPolicy.filter(Collections.singletonList(defaults.get(3)),
                        Arrays.asList(defaults.get(3), defaults.get(1)), false, ID_OF),
                Collections.singletonList("deepseek"));

        // 5. 全删光：入参是空列表（不是默认列表实例），补回来的全部丢弃
        expectIds("删光后列表保持为空",
                ProviderPolicy.filter(Collections.emptyList(), defaults, false, ID_OF),
                Collections.emptyList());

        // 6. 重复条目只留一份
        expectIds("重复条目去重",
                ProviderPolicy.filter(Collections.singletonList(defaults.get(0)),
                        Arrays.asList(defaults.get(0), defaults.get(0)), false, ID_OF),
                Collections.singletonList("openai"));

        // 7. 入参为 null 不炸
        expectIds("入参为 null 时不崩溃",
                ProviderPolicy.filter(null, Arrays.asList(defaults.get(1)), false, ID_OF),
                Collections.emptyList());

        // 8. 规整结果为空时直接返回空
        expectIds("结果为空保持为空",
                ProviderPolicy.filter(defaults, new ArrayList<Provider>(), false, ID_OF),
                Collections.emptyList());

        // 9. 顺序保持宿主的顺序（不要因为过滤打乱排名）
        expectIds("保留宿主的相对顺序",
                ProviderPolicy.filter(Arrays.asList(defaults.get(3), defaults.get(0)),
                        Arrays.asList(defaults.get(1), defaults.get(3), defaults.get(0)), false, ID_OF),
                Arrays.asList("deepseek", "openai"));

        // 10. sameElements
        List<Object> a = new ArrayList<Object>();
        a.add(defaults.get(0));
        check("sameElements 相同内容", ProviderPolicy.sameElements(a, new ArrayList<Object>(a)), true);
        check("sameElements 长度不同",
                ProviderPolicy.sameElements(a, Collections.emptyList()), false);
        check("sameElements 同引用", ProviderPolicy.sameElements(a, a), true);

        System.out.println();
        if (failures == 0) {
            System.out.println("全部通过：" + checks + " 项断言");
        } else {
            System.out.println("失败 " + failures + " / " + checks + " 项断言");
            System.exit(1);
        }
    }

    private static void expectIds(String name, List<Object> actual, List<?> expectedIds) {
        List<Object> actualIds = new ArrayList<>();
        for (Object provider : actual) {
            actualIds.add(ID_OF.idOf(provider));
        }
        check(name, actualIds, new ArrayList<Object>(expectedIds));
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
