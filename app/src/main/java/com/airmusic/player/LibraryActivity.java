package com.airmusic.player;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.airmusic.player.library.MusicLibrary;
import com.airmusic.player.library.Track;
import com.airmusic.player.service.PlaybackService;
import com.airmusic.player.ui.TrackAdapter;

import java.util.List;
import java.util.ArrayList;

public class LibraryActivity extends AppCompatActivity {

    private final TrackAdapter adapter = new TrackAdapter();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_library);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

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
            List<Track> ordered = orderForDisplay(tracks);
            adapter.setTracks(ordered);
            applyCurrentTrack(ordered);
        }
        // Always rescan so newly added files (USB, new downloads) show up;
        // the cached list is shown immediately and replaced when ready.
        MusicLibrary.getInstance().rescan(this, (result, error) -> {
            if (service != null && result != null) {
                service.setTracks(result);
            }
            if (result != null) {
                List<Track> ordered = orderForDisplay(result);
                adapter.setTracks(ordered);
                applyCurrentTrack(ordered);
            }
            if (result != null && result.isEmpty() && error != null) {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show();
            }
            if (result != null && result.isEmpty()) {
                Toast.makeText(this, R.string.no_tracks, Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Puts the currently playing track at the top of the shown list so the
     * library opens with the active song front and centre. The underlying
     * service playlist stays untouched (next/prev order is preserved).
     */
    private List<Track> orderForDisplay(List<Track> tracks) {
        if (tracks == null || tracks.isEmpty()) return tracks;
        PlaybackService service = PlaybackService.getInstance();
        if (service == null || !service.isShowingLibraryTrack()) return tracks;
        Track cur = service.getCurrentTrack();
        if (cur != null) {
            for (int i = 0; i < tracks.size(); i++) {
                if (cur.uri != null && cur.uri.equals(tracks.get(i).uri)) {
                    return i > 0 ? moveToTop(tracks, i) : tracks;
                }
            }
        } else {
            String title = service.getCurrentDisplayTitle();
            String artist = service.getCurrentDisplayArtist();
            if (title != null && title.length() > 0) {
                for (int i = 0; i < tracks.size(); i++) {
                    Track t = tracks.get(i);
                    if (title.equals(t.displayTitle())
                            && (artist == null || artist.length() == 0
                            || artist.equals(t.displayArtist()))) {
                        return i > 0 ? moveToTop(tracks, i) : tracks;
                    }
                }
            }
        }
        return tracks;
    }

    private List<Track> moveToTop(List<Track> tracks, int index) {
        List<Track> reordered = new ArrayList<>(tracks);
        Track t = reordered.remove(index);
        reordered.add(0, t);
        return reordered;
    }

    /** Highlights the currently playing track and scrolls it into view. */
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
                    ((LinearLayoutManager) lm).scrollToPosition(pos);
                }
            });
        }
    }
}
