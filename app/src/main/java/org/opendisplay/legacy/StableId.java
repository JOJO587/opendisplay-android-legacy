package org.opendisplay.legacy;

// Copyright (c) 2026 JOJO587
// SPDX-License-Identifier: MIT

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/**
 * 稳定的每设备 UUID。
 *
 * 规范要求（section 2.1 / 6.1）：mDNS TXT 里的 id 必须与 hello 里的 id 一致，
 * 发送端靠它把"同一个物理设备"在不同传输方式、不同名字下认出来。
 */
public final class StableId {

    private static final String PREFS = "opendisplay_legacy";
    private static final String KEY_ID = "stable_id";

    private StableId() {
    }

    public static String get(Context ctx) {
        SharedPreferences sp =
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = sp.getString(KEY_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            sp.edit().putString(KEY_ID, id).apply();
        }
        return id;
    }
}
