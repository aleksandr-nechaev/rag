package com.nechaev.util;

public final class LogUtils {

    private static final int SHORT_ID_LEN = 8;

    private LogUtils() {}

    public static String shortId(String id) {
        if (id == null) return "?";
        return id.substring(0, Math.min(SHORT_ID_LEN, id.length()));
    }
}
