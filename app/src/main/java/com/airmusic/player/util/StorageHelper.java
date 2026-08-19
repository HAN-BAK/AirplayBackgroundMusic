package com.airmusic.player.util;

import android.os.Environment;

import java.io.File;
import java.util.Locale;

/** Disk-space helpers for the music library directory. */
public final class StorageHelper {

    /** Uploads are refused when the free space would drop below this. */
    public static final long MIN_FREE_BYTES = 80L * 1024 * 1024;

    private StorageHelper() {
    }

    /** Resolves the music library directory (custom path or default Music). */
    public static File getMusicDir(String folderPath) {
        if (folderPath != null && !folderPath.isEmpty()) {
            File f = new File(folderPath);
            if (f.exists()) return f;
            File parent = f.getParentFile();
            if (parent != null && parent.exists()) return parent;
            return f;
        }
        return new File(Environment.getExternalStorageDirectory(), "Music");
    }

    public static long getTotalBytes(String folderPath) {
        long v = getMusicDir(folderPath).getTotalSpace();
        return v > 0 ? v : 0;
    }

    public static long getUsableBytes(String folderPath) {
        long v = getMusicDir(folderPath).getUsableSpace();
        return v > 0 ? v : 0;
    }

    /** True when at least MIN_FREE_BYTES would remain after adding extraBytes. */
    public static boolean hasEnoughSpace(String folderPath, long extraBytes) {
        long usable = getUsableBytes(folderPath);
        return usable > 0 && usable - extraBytes >= MIN_FREE_BYTES;
    }

    public static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
        }
        if (bytes >= 1024L * 1024) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return bytes + " B";
    }
}
