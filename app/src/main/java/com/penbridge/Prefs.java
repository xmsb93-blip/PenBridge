package com.penbridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.SecureRandom;

public final class Prefs {
    private static final String FILE = "penbridge";
    private static final String KEY_ERASER_MODE = "eraser_mode";
    private static final String KEY_PRESSURE = "pressure_level";
    private static final String KEY_CMD_TOKEN = "cmd_token";

    public static final int MODE_SINGLE = 0;
    public static final int MODE_LONG = 1;
    public static final int MODE_PRESSURE = 2;

    private Prefs() {
    }

    public static int eraserMode(Context context) {
        int mode = prefs(context).getInt(KEY_ERASER_MODE, MODE_PRESSURE);
        // 单击笔键已改为草稿纸，旧的「单击切橡皮」统一迁移到重按
        return mode == MODE_SINGLE ? MODE_PRESSURE : mode;
    }

    public static void setEraserMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_ERASER_MODE, mode).apply();
    }

    public static int cycleEraserMode(Context context) {
        int next = eraserMode(context) == MODE_LONG ? MODE_PRESSURE : MODE_LONG;
        setEraserMode(context, next);
        return next;
    }

    public static String eraserModeName(Context context) {
        return eraserMode(context) == MODE_LONG ? "长按笔键" : "重按笔尖(停顿)";
    }

    public static int pressureLevel(Context context) {
        return prefs(context).getInt(KEY_PRESSURE, 14000);
    }

    public static int cyclePressureLevel(Context context) {
        int cur = pressureLevel(context);
        int next = cur < 13000 ? 14000 : (cur < 15000 ? 16000 : 12000);
        prefs(context).edit().putInt(KEY_PRESSURE, next).apply();
        return next;
    }

    public static synchronized String commandToken(Context context) {
        String token = prefs(context).getString(KEY_CMD_TOKEN, null);
        if (token != null && !token.isEmpty()) {
            return token;
        }
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        token = Base64.encodeToString(random, Base64.NO_WRAP | Base64.URL_SAFE);
        prefs(context).edit().putString(KEY_CMD_TOKEN, token).commit();
        return token;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
