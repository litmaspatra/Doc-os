package com.litmaspatra.docos;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/** Keeps the last crash on the device so the next start can show it (nothing is ever sent anywhere). */
final class CrashLog {
    private CrashLog() { }

    private static File file(Context c) { return new File(c.getFilesDir(), "last_crash.txt"); }

    static void install(final Context app) {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    String s = "Doc-os 1.0.1 | Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") | "
                            + Build.MANUFACTURER + " " + Build.MODEL + "\nThread: " + t.getName() + "\n\n" + sw;
                    FileOutputStream os = new FileOutputStream(file(app));
                    try { os.write(s.getBytes("UTF-8")); } finally { os.close(); }
                } catch (Throwable ignored) { }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    /** Returns the stored report (and deletes it), or null. */
    static String take(Context c) {
        File f = file(c);
        if (!f.exists()) return null;
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] b = new byte[(int) Math.min(f.length(), 20000)];
            int n = in.read(b);
            in.close();
            f.delete();
            return n <= 0 ? null : new String(b, 0, n, "UTF-8");
        } catch (Throwable t) { f.delete(); return null; }
    }
}
