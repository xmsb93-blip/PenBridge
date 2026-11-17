package com.penbridge;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

public final class LogBuffer {
    private static final int MAX = 300;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private LogBuffer() {
    }

    public static void add(String msg) {
        String line = FMT.format(new Date()) + "  " + msg;
        synchronized (LINES) {
            LINES.addFirst(line);
            while (LINES.size() > MAX) {
                LINES.removeLast();
            }
        }
        Log.i("PenBridge", msg);
    }

    public static String text() {
        StringBuilder sb = new StringBuilder();
        synchronized (LINES) {
            for (String line : LINES) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
