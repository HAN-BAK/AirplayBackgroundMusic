package com.airmusic.player.library;

import android.net.Uri;

/**
 * A single music track, located either in system media library (MediaStore),
 * a user-picked folder (SAF) or a USB device.
 */
public class Track {

    /** Fallback values returned when a metadata field is missing. */
    public static final String UNKNOWN_TITLE = "未知歌曲";
    public static final String UNKNOWN_ARTIST = "未知歌手";
    public static final String UNKNOWN_ALBUM = "未知专辑";

    public final Uri uri;
    public final String title;
    public final String artist;
    public final String album;
    public final long durationMs;
    public final String folder;
    public final String filePath;
    public final String extension;

    public Track(Uri uri, String title, String artist, String album, long durationMs,
                 String folder, String filePath, String extension) {
        this.uri = uri;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.durationMs = durationMs;
        this.folder = folder;
        this.filePath = filePath;
        this.extension = extension;
    }

    public String displayTitle() {
        return title == null || title.trim().length() == 0 ? UNKNOWN_TITLE : title;
    }

    public String displayArtist() {
        return artist == null || artist.trim().length() == 0 ? UNKNOWN_ARTIST : artist;
    }

    public String displayAlbum() {
        return album == null || album.trim().length() == 0 ? UNKNOWN_ALBUM : album;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Track)) return false;
        Track t = (Track) o;
        return uri != null && uri.equals(t.uri);
    }

    @Override
    public int hashCode() {
        return uri == null ? 0 : uri.hashCode();
    }
}
