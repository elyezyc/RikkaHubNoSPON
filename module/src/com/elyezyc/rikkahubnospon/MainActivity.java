/*
 * RikkaHubNoSPON - 阻止 RikkaHub 自动写入内置提供商，并允许删除自带提供商
 * Copyright (C) 2026 elyezyc
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.elyezyc.rikkahubnospon;

import android.app.Activity;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.widget.TextView;

/**
 * 一个纯信息页：模块本身没有需要配置的东西，两个功能都是一直开着的。
 *
 * <p>这个 Activity 跑在模块自己的进程里，<b>不会</b>被注入——它只是给用户看说明。
 */
public final class MainActivity extends Activity {

    static final String VERSION = "1.0.0";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        TextView body = findViewById(R.id.body);
        body.setText(getString(R.string.main_body, VERSION));
        body.setMovementMethod(LinkMovementMethod.getInstance());
    }
}
