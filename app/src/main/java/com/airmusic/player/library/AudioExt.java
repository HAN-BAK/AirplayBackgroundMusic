package com.airmusic.player.library;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class AudioExt {

    private static final Set<String> EXTENSIONS = new HashSet<>(Arrays.asList(
            "mp3", "flac", "m4a", "m4b", "aac", "ogg", "oga", "opus",
            "wav", "ape", "wma", "aiff", "aif"));

    private AudioExt() {
    }

    public static boolean isAudio(String fileName) {
        if (fileName == null) return false;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return false;
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.US);
        return EXTENSIONS.contains(ext);
    }

    public static String extensionOf(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase(Locale.US);
    }
}
