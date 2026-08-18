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
            adapter.setTracks(tracks);
        }
        // Always rescan so newly added files (USB, new downloads) show up;
        // the cached list is shown immediately and replaced when ready.
        MusicLibrary.getInstance().rescan(this, (result, error) -> {
            if (service != null && result != null) {
                service.setTracks(result);
            }
            if (result != null) {
                adapter.setTracks(result);
            }
            if (result != null && result.isEmpty() && error != null) {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show();
            }
            if (result != null && result.isEmpty()) {
                Toast.makeText(this, R.string.no_tracks, Toast.LENGTH_LONG).show();
            }
        });
    }
}
