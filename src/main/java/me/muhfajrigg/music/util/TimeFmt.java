package me.muhfajrigg.music.util;

public final class TimeFmt {
    private TimeFmt() {}

    /** Formats milliseconds into m:ss or h:mm:ss. */
    public static String format(long ms) {
        long total = Math.max(0, ms) / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }
}
