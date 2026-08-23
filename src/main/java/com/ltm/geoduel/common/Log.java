package com.ltm.geoduel.common;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Logger console tối giản, có timestamp, an toàn đa luồng (println đã đồng bộ). */
public final class Log {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private Log() {}

    public static void info(String tag, String msg) {
        System.out.println(stamp() + " [" + tag + "] " + msg);
    }

    public static void warn(String tag, String msg) {
        System.out.println(stamp() + " [" + tag + "] CANH BAO: " + msg);
    }

    public static void error(String tag, String msg, Throwable t) {
        System.out.println(stamp() + " [" + tag + "] LOI: " + msg + (t != null ? " - " + t : ""));
        if (t != null) t.printStackTrace(System.out);
    }

    private static String stamp() {
        return LocalDateTime.now().format(FMT);
    }
}
