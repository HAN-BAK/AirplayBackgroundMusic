package com.airmusic.player.util;

import android.util.Log;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Routes java.util.logging (used by the AirPlay engine) to Android logcat.
 */
public class AndroidLogHandler extends Handler {

    @Override
    public void publish(LogRecord record) {
        if (record == null) return;
        String tag = "AirPlay";
        if (record.getLoggerName() != null) {
            String[] parts = record.getLoggerName().split("\\.");
            tag = parts[parts.length - 1];
            if (tag.length() > 20) tag = tag.substring(0, 20);
        }
        String message = record.getMessage() == null ? "" : record.getMessage();
        if (record.getThrown() != null) {
            message += "\n" + Log.getStackTraceString(record.getThrown());
        }
        int level = record.getLevel().intValue();
        if (level >= Level.SEVERE.intValue()) {
            Log.e(tag, message);
        } else if (level >= Level.WARNING.intValue()) {
            Log.w(tag, message);
        } else if (level >= Level.INFO.intValue()) {
            Log.i(tag, message);
        } else {
            Log.d(tag, message);
        }
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
}
