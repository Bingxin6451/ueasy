package com.ueasy.client;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 本地偏好：我的楼层 + 每台设备的自定义名字。
 * 存在 SharedPreferences，卸载即清，不上传。
 */
final class Prefs {

    private static final String FILE = "ueasy_prefs";
    private static final String KEY_FLOOR = "my_floor";
    private static final String KEY_NAME_PREFIX = "name_";

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 我的楼层，0 表示未设置 */
    int myFloor() {
        return sp.getInt(KEY_FLOOR, 0);
    }

    void setMyFloor(int floor) {
        sp.edit().putInt(KEY_FLOOR, floor).apply();
    }

    /** 用户给这台设备起的名字，没起过返回 null */
    String customName(String mac) {
        String v = sp.getString(KEY_NAME_PREFIX + norm(mac), null);
        return (v == null || v.trim().isEmpty()) ? null : v;
    }

    void setCustomName(String mac, String name) {
        if (name == null || name.trim().isEmpty()) {
            sp.edit().remove(KEY_NAME_PREFIX + norm(mac)).apply();
        } else {
            sp.edit().putString(KEY_NAME_PREFIX + norm(mac), name.trim()).apply();
        }
    }

    /** 用户把某台设备归到的楼层，0 表示没设过 */
    int deviceFloor(String mac) {
        return sp.getInt("floor_" + norm(mac), 0);
    }

    void setDeviceFloor(String mac, int floor) {
        sp.edit().putInt("floor_" + norm(mac), floor).apply();
    }

    private static String norm(String mac) {
        return mac == null ? "" : mac.replace(":", "").toUpperCase();
    }
}
