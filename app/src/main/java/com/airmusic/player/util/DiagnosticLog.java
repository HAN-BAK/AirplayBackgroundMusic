package com.airmusic.player.util;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes diagnostic log lines to an app-private file so a user can export
 * them without adb. Each line is also mirrored to logcat.
 */
public final class DiagnosticLog {

    private static final String TAG = "DiagnosticLog";
    private static final long MAX_BYTES = 256 * 1024;
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static File logFile;
    private static Writer writer;
    private static long written;

    private DiagnosticLog() {
    }

    /** Opens (appends to) the diagnostic log file. Safe to call once per process. */
    public static void init(Context context) {
        synchronized (LOCK) {
            try {
                File dir = new File(context.getFilesDir(), "logs");
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.w(TAG, "cannot create log dir");
                    return;
                }
                logFile = new File(dir, "diagnostic.log");
                if (logFile.length() > MAX_BYTES) {
                    File old = new File(dir, "diagnostic.old.log");
                    if (old.exists() && !old.delete()) {
                        Log.w(TAG, "cannot delete old log");
                    }
                    if (!logFile.renameTo(old)) {
                        Log.w(TAG, "cannot rotate log");
                    }
                }
                if (writer != null) {
                    try {
                        writer.close();
                    } catch (Exception ignored) {
                    }
                }
                writer = new OutputStreamWriter(
                        new FileOutputStream(logFile, true), StandardCharsets.UTF_8);
                written = logFile.length();
                write("I", TAG, "=== diagnostic log session started ===");
            } catch (Throwable t) {
                Log.w(TAG, "init failed", t);
            }
        }
    }

    public static void i(String tag, String msg) {
        write("I", tag, msg);
    }

    public static void w(String tag, String msg) {
        write("W", tag, msg);
    }

    public static void e(String tag, String msg) {
        write("E", tag, msg);
    }

    private static void write(String level, String tag, String msg) {
        synchronized (LOCK) {
            if (writer == null) return;
            try {
                if (written > MAX_BYTES) rotate();
                String line = TIME.format(new Date()) + " " + level + " " + tag + ": "
                        + (msg == null ? "" : msg) + "\n";
                writer.write(line);
                writer.flush();
                written += line.getBytes(StandardCharsets.UTF_8).length;
            } catch (Throwable t) {
                Log.w(TAG, "write failed", t);
            }
        }
        if ("I".equals(level)) {
            Log.i(tag, msg);
        } else if ("W".equals(level)) {
            Log.w(tag, msg);
        } else {
            Log.e(tag, msg);
        }
    }

    private static void rotate() {
        try {
            if (writer != null) writer.close();
            File old = new File(logFile.getParentFile(), "diagnostic.old.log");
            if (old.exists() && !old.delete()) {
                Log.w(TAG, "cannot delete old rotated log");
            }
            if (!logFile.renameTo(old)) {
                Log.w(TAG, "cannot rotate oversized log");
            }
            writer = new OutputStreamWriter(
                    new FileOutputStream(logFile, true), StandardCharsets.UTF_8);
            written = 0;
        } catch (Throwable t) {
            Log.w(TAG, "rotate failed", t);
        }
    }

    /** Returns the current log file, or null when none exists. */
    public static File getLogFile(Context context) {
        File f = new File(context.getFilesDir(), "logs/diagnostic.log");
        return f.exists() ? f : null;
    }
}
