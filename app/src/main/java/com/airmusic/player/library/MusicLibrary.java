package com.airmusic.player.library;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import com.airmusic.player.util.Prefs;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Scans music from the user-configured folder (SAF, including USB drives) and
 * the system media library (MediaStore).
 */
public final class MusicLibrary {

    private static final String TAG = "MusicLibrary";

    public interface ScanCallback {
        void onScanFinished(List<Track> tracks, String error);
    }

    private static MusicLibrary instance;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile List<Track> cachedTracks = new ArrayList<>();
    private volatile boolean scanning;

    private MusicLibrary() {
    }

    public static synchronized MusicLibrary getInstance() {
        if (instance == null) instance = new MusicLibrary();
        return instance;
    }

    public List<Track> getCachedTracks() {
        return cachedTracks;
    }

    public boolean isScanning() {
        return scanning;
    }

    /** Drops the cached track list so the next rescan starts clean. */
    public void clearCache() {
        cachedTracks = new ArrayList<>();
    }

    public void rescan(Context context, ScanCallback callback) {
        scanning = true;
        final Context appContext = context.getApplicationContext();
        executor.execute(() -> {
            List<Track> tracks = new ArrayList<>();
            String error = null;
            try {
                tracks = scanInternal(appContext);
            } catch (SecurityException e) {
                error = "没有存储权限，请先在设置中授权";
                Log.e(TAG, "scan failed", e);
            } catch (Exception e) {
                error = "扫描失败：" + e.getMessage();
                Log.e(TAG, "scan failed", e);
            }
            final List<Track> result = tracks;
            final String err = error;
            cachedTracks = result;
            scanning = false;
            mainHandler.post(() -> {
                if (callback != null) callback.onScanFinished(result, err);
            });
        });
    }

    private List<Track> scanInternal(Context context) throws Exception {
        Map<String, Track> byUri = new LinkedHashMap<>();

        Prefs prefs = new Prefs(context);
        boolean folderScanned = false;
        String folderPath = prefs.getMusicFolderPath();
        if (folderPath != null) {
            File dir = new File(folderPath);
            if (dir.isDirectory() && dir.canRead()) {
                scanFileTree(context, dir, byUri, 0);
                folderScanned = true;
            }
        }
        if (!folderScanned) {
            String folderUri = prefs.getMusicFolderUri();
            if (folderUri != null) {
                folderScanned = scanSafTree(context, Uri.parse(folderUri), byUri, 0);
            }
        }

        // When a folder is configured, the library is that folder only;
        // otherwise fall back to the system media library (including USB).
        if (!folderScanned) {
            scanMediaStore(context, byUri);
        }

        List<Track> tracks = new ArrayList<>(byUri.values());
        Collections.sort(tracks, new Comparator<Track>() {
            @Override
            public int compare(Track a, Track b) {
                int f = compareNullable(a.folder, b.folder);
                if (f != 0) return f;
                return compareNullable(a.title, b.title);
            }
        });
        return tracks;
    }

    /** Scans a raw filesystem tree (internal storage or USB volume). */
    private void scanFileTree(Context context, File dir, Map<String, Track> byUri, int depth) {
        if (depth > 10) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                scanFileTree(context, file, byUri, depth + 1);
            } else if (file.isFile() && AudioExt.isAudio(file.getName())) {
                Track track = buildTrackFromFile(context, file, dir.getName());
                byUri.put(track.uri.toString(), track);
            }
        }
    }

    /** Reads file tags (title/artist/album/duration) when scanning folders or USB. */
    private Track buildTrackFromFile(Context context, File file, String folder) {
        String name = file.getName();
        String title = nameWithoutExt(name);
        String artist = "";
        String album = "";
        long duration = 0;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            title = firstNonEmpty(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE), title);
            artist = trimToEmpty(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            album = trimToEmpty(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
            String d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) {
                try {
                    duration = Long.parseLong(d);
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "metadata read failed: " + name, e);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
        return new Track(Uri.fromFile(file), title, artist, album, duration, folder,
                file.getAbsolutePath(), AudioExt.extensionOf(name));
    }

    private static int compareNullable(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        return a.toLowerCase(Locale.US).compareTo(b.toLowerCase(Locale.US));
    }

    private boolean scanSafTree(Context context, Uri treeUri, Map<String, Track> byUri, int depth) {
        if (depth > 10) return true;
        DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
        if (root == null || !root.canRead()) return false;
        DocumentFile[] files = root.listFiles();
        if (files == null) return false;
        for (DocumentFile file : files) {
            if (file.isDirectory()) {
                scanSafTree(context, file.getUri(), byUri, depth + 1);
            } else if (file.isFile() && AudioExt.isAudio(file.getName())) {
                Track track = buildTrackFromSaf(context, file, root.getName());
                byUri.put(file.getUri().toString(), track);
            }
        }
        return true;
    }

    /** Reads file tags for SAF (content://) documents. */
    private Track buildTrackFromSaf(Context context, DocumentFile file, String folder) {
        String name = file.getName();
        String title = nameWithoutExt(name);
        String artist = "";
        String album = "";
        long duration = 0;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, file.getUri());
            title = firstNonEmpty(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE), title);
            artist = trimToEmpty(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            album = trimToEmpty(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
            String d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) {
                try {
                    duration = Long.parseLong(d);
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "metadata read failed: " + name, e);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
        return new Track(file.getUri(), title, artist, album, duration, folder,
                null, AudioExt.extensionOf(name));
    }

    private static String nameWithoutExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String firstNonEmpty(String value, String fallback) {
        return value != null && value.trim().length() > 0 ? value.trim() : fallback;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private void scanMediaStore(Context context, Map<String, Track> byUri) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return;
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }

        List<String> volumes = new ArrayList<>();
        volumes.add("external");
        if (Build.VERSION.SDK_INT >= 29) {
            Set<String> extra = MediaStore.getExternalVolumeNames(context);
            for (String v : extra) {
                if (!volumes.contains(v)) volumes.add(v);
            }
        }

        for (String volume : volumes) {
            queryVolume(context, volume, byUri);
        }
    }

    private void queryVolume(Context context, String volume, Map<String, Track> byUri) {
        Uri collection = MediaStore.Audio.Media.getContentUri(volume);
        String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
        };
        String relativePath = null;
        if (Build.VERSION.SDK_INT >= 29) {
            projection = new String[]{
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.RELATIVE_PATH,
            };
        }
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    collection, projection,
                    MediaStore.Audio.Media.IS_MUSIC + " != 0", null,
                    MediaStore.Audio.Media.TITLE + " COLLATE NOCASE");
            if (cursor == null) return;
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
            int titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
            int artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
            int albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
            int durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
            int dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA);
            int pathCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH);

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idCol);
                Uri uri = ContentUris.withAppendedId(collection, id);
                String title = cursor.getString(titleCol);
                String artist = cursor.getString(artistCol);
                String album = cursor.getString(albumCol);
                long duration = cursor.getLong(durCol);
                String data = dataCol >= 0 ? cursor.getString(dataCol) : null;
                String folder = pathCol >= 0 ? cursor.getString(pathCol) : null;
                if (folder == null && data != null) {
                    File f = new File(data);
                    folder = f.getParentFile() != null ? f.getParentFile().getName() : null;
                }
                if (title == null || title.trim().length() == 0) {
                    if (data != null) title = nameWithoutExt(new File(data).getName());
                    else continue;
                }
                Track track = new Track(
                        uri, title, artist, album, duration,
                        folder, data, AudioExt.extensionOf(data == null ? title : data));
                byUri.put(uri.toString(), track);
            }
        } catch (Exception e) {
            Log.e(TAG, "queryVolume failed for " + volume, e);
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /**
     * Loads rich metadata (title/artist/album/duration/embedded art) for a
     * track, filling gaps left by the scan.
     */
    public static MediaMetadataRetriever openRetriever(Context context, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            return retriever;
        } catch (Exception e) {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
            return null;
        }
    }
}
