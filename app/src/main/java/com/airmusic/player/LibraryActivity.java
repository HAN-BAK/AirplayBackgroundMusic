package com.airmusic.player;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.airmusic.player.library.MusicLibrary;
import com.airmusic.player.library.Track;
import com.airmusic.player.service.PlaybackService;
import com.airmusic.player.ui.TrackAdapter;

import java.io.File;
import java.util.List;

public class LibraryActivity extends BaseActivity {

    private final TrackAdapter adapter = new TrackAdapter();
    private TextView txtTitle;
    private TextView txtSelectCount;
    private ImageButton btnDelete;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_library);

        txtTitle = findViewById(R.id.txt_title);
        txtSelectCount = findViewById(R.id.txt_select_count);
        btnDelete = findViewById(R.id.btn_delete);

        findViewById(R.id.btn_back).setOnClickListener(v -> {
            if (adapter.isSelectionMode()) {
                exitSelectionMode();
            } else {
                finish();
            }
        });

        RecyclerView list = findViewById(R.id.track_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        adapter.setOnTrackClick(track -> {
            PlaybackService service = PlaybackService.getInstance();
            if (service != null) {
                service.playTrack(track);
                finish();
            }
        });
        adapter.setOnTrackLongClick(track -> updateSelectionUi());
        adapter.setOnSelectionChanged(count -> updateSelectionUi());
        btnDelete.setOnClickListener(v -> confirmDelete());

        loadTracks();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadTracks();
    }

    private void loadTracks() {
        PlaybackService service = PlaybackService.getInstance();
        List<Track> tracks = service != null
                ? service.getTracks()
                : MusicLibrary.getInstance().getCachedTracks();
        if (tracks != null && !tracks.isEmpty()) {
            adapter.setTracks(tracks);
            applyCurrentTrack(tracks);
        }
        // Always rescan so newly added files (USB, new downloads) show up;
        // the cached list is shown immediately and replaced when ready.
        MusicLibrary.getInstance().rescan(this, (result, error) -> {
            if (service != null && result != null) {
                service.setTracks(result);
            }
            if (result != null) {
                adapter.setTracks(result);
                applyCurrentTrack(result);
            }
            if (result != null && result.isEmpty() && error != null) {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show();
            }
            if (result != null && result.isEmpty()) {
                Toast.makeText(this, R.string.no_tracks, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void updateSelectionUi() {
        boolean selecting = adapter.isSelectionMode();
        txtTitle.setVisibility(selecting ? View.GONE : View.VISIBLE);
        txtSelectCount.setVisibility(selecting ? View.VISIBLE : View.GONE);
        btnDelete.setVisibility(selecting ? View.VISIBLE : View.GONE);
        if (selecting) {
            txtSelectCount.setText(getString(R.string.selected_count, adapter.getSelectedCount()));
        }
    }

    private void exitSelectionMode() {
        adapter.setSelectionMode(false);
        updateSelectionUi();
    }

    private void confirmDelete() {
        final List<Track> selected = adapter.getSelectedTracks();
        if (selected.isEmpty()) {
            exitSelectionMode();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete)
                .setMessage(getString(R.string.delete_files_confirm, selected.size()))
                .setPositiveButton(android.R.string.ok, (d, w) -> deleteTracks(selected))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteTracks(List<Track> selected) {
        int ok = 0;
        List<Track> deleted = new java.util.ArrayList<>();
        for (Track t : selected) {
            if (deleteFile(t)) {
                ok++;
                deleted.add(t);
            }
        }
        exitSelectionMode();
        Toast.makeText(this, getString(R.string.deleted_count, ok), Toast.LENGTH_SHORT).show();
        MusicLibrary.getInstance().clearCache();
        PlaybackService service = PlaybackService.getInstance();
        if (service != null) {
            // Remove the files from the playlist immediately and switch the
            // player to a surviving track if the current one was deleted.
            service.removeDeletedTracks(deleted);
        }
        loadTracks();
    }

    private boolean deleteFile(Track t) {
        try {
            if (t.filePath != null && !t.filePath.isEmpty()) {
                File f = new File(t.filePath);
                if (f.exists()) {
                    return f.delete();
                }
            }
            if (t.uri != null) {
                return getContentResolver().delete(t.uri, null, null) > 0;
            }
        } catch (Exception e) {
            Log.w("LibraryActivity", "delete failed", e);
        }
        return false;
    }

    /** Highlights the currently playing track and scrolls it to the top of
     *  the visible list (the sort order itself stays unchanged). */
    private void applyCurrentTrack(List<Track> tracks) {
        if (tracks == null || tracks.isEmpty()) return;
        PlaybackService service = PlaybackService.getInstance();
        if (service == null || !service.isShowingLibraryTrack()) {
            adapter.setCurrentUri(null);
            adapter.setCurrentTitleArtist(null, null);
            return;
        }
        int target = -1;
        Track cur = service.getCurrentTrack();
        if (cur != null) {
            adapter.setCurrentUri(cur.uri);
            for (int i = 0; i < tracks.size(); i++) {
                if (cur.uri != null && cur.uri.equals(tracks.get(i).uri)) {
                    target = i;
                    break;
                }
            }
        } else {
            String title = service.getCurrentDisplayTitle();
            String artist = service.getCurrentDisplayArtist();
            adapter.setCurrentTitleArtist(title, artist);
            if (title != null && title.length() > 0) {
                for (int i = 0; i < tracks.size(); i++) {
                    Track t = tracks.get(i);
                    if (title.equals(t.displayTitle())
                            && (artist == null || artist.length() == 0
                            || artist.equals(t.displayArtist()))) {
                        target = i;
                        break;
                    }
                }
            }
        }
        if (target >= 0) {
            final int pos = target;
            RecyclerView list = findViewById(R.id.track_list);
            list.post(() -> {
                RecyclerView.LayoutManager lm = list.getLayoutManager();
                if (lm instanceof LinearLayoutManager) {
                    ((LinearLayoutManager) lm).scrollToPositionWithOffset(pos, 0);
                }
            });
        }
    }
}
