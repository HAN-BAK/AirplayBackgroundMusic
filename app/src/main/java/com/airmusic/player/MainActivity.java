package com.airmusic.player;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.airmusic.player.service.PlaybackService;
import com.airmusic.player.util.PlayerUiState;
import com.airmusic.player.util.Prefs;
import com.airmusic.player.util.StateBus;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private ImageView albumArt;
    private TextView trackTitle;
    private TextView trackArtist;
    private TextView trackAlbum;
    private TextView sourceBadge;
    private TextView positionText;
    private TextView durationText;
    private ImageButton btnPlay;
    private SeekBar seekBar;
    private SeekBar volumeSeek;
    private View seekRow;

    private boolean seeking;
    private PlayerUiState lastState;
    private AudioManager audioManager;
    private ContentObserver volumeObserver;
    private final Handler volumePollHandler = new Handler(Looper.getMainLooper());
    private final Runnable volumePoll = new Runnable() {
        @Override
        public void run() {
            syncVolumeSlider();
            volumePollHandler.postDelayed(this, 400);
        }
    };

    private final StateBus.Listener stateListener = state -> {
        lastState = state;
        render(state);
    };

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                PlaybackService service = PlaybackService.getInstance();
                if (service != null) service.rescanLibrary();
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        albumArt = findViewById(R.id.album_art);
        trackTitle = findViewById(R.id.track_title);
        trackArtist = findViewById(R.id.track_artist);
        trackAlbum = findViewById(R.id.track_album);
        sourceBadge = findViewById(R.id.source_badge);
        positionText = findViewById(R.id.position_text);
        durationText = findViewById(R.id.duration_text);
        btnPlay = findViewById(R.id.btn_play);
        seekBar = findViewById(R.id.seek_bar);
        volumeSeek = findViewById(R.id.volume_seek);
        seekRow = findViewById(R.id.seek_row);
        View leftPanel = findViewById(R.id.left_panel);

        setupVolumeSlider();

        // Size the album art square and center it in the left panel (same
        // vertical position as the playback controls). The panel wraps around
        // the cover; a safety cap keeps it from crowding out the right panel.
        albumArt.post(() -> {
            int dp18 = Math.round(18 * getResources().getDisplayMetrics().density);
            int size = leftPanel.getHeight() - leftPanel.getPaddingTop() - leftPanel.getPaddingBottom();
            int max = Math.round(getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density * 0.95f);
            size = Math.min(size, max);
            if (size > 0) {
                ViewGroup.LayoutParams lp = albumArt.getLayoutParams();
                lp.width = size;
                lp.height = size;
                albumArt.setLayoutParams(lp);
            }
        });

        findViewById(R.id.btn_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btn_library).setOnClickListener(v ->
                startActivity(new Intent(this, LibraryActivity.class)));
        findViewById(R.id.btn_apps).setOnClickListener(v ->
                startActivity(new Intent(this, AppsActivity.class)));
        findViewById(R.id.btn_prev).setOnClickListener(v -> {
            PlaybackService service = PlaybackService.getInstance();
            if (service != null) service.previous();
        });
        findViewById(R.id.btn_next).setOnClickListener(v -> {
            PlaybackService service = PlaybackService.getInstance();
            if (service != null) service.next();
        });
        btnPlay.setOnClickListener(v -> {
            PlaybackService service = PlaybackService.getInstance();
            if (service != null) service.togglePlay();
        });
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && lastState != null && lastState.durationMs > 0) {
                    positionText.setText(formatTime((long) progress * lastState.durationMs / 1000));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                seeking = false;
                PlaybackService service = PlaybackService.getInstance();
                if (service != null && lastState != null && lastState.durationMs > 0) {
                    service.seekTo(seekBar.getProgress() * lastState.durationMs / 1000);
                }
            }
        });

        PlaybackService.start(this);
        requestPermissionsIfNeeded();
    }

    /** Binds the bottom-bar slider to the system media volume. */
    private void setupVolumeSlider() {
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager == null || volumeSeek == null) return;

        volumeSeek.setMax(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        volumeSeek.setProgress(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC));
        volumeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        // Keep the slider in sync when the volume is changed elsewhere
        // (e.g. the device volume keys).
        volumeObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                if (volumeSeek.getProgress() != current) {
                    volumeSeek.setProgress(current);
                }
            }
        };
        getContentResolver().registerContentObserver(
                Settings.System.getUriFor("volume_music"), false, volumeObserver);
    }

    private void syncVolumeSlider() {
        if (audioManager != null && volumeSeek != null) {
            int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (volumeSeek.getProgress() != current) {
                volumeSeek.setProgress(current);
            }
        }
    }

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            boolean audio = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
            boolean notif = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
            if (!audio || !notif) {
                permissionLauncher.launch(new String[]{
                        Manifest.permission.READ_MEDIA_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS});
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE});
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        StateBus.get().addListener(stateListener);
        syncVolumeSlider();
        volumePollHandler.removeCallbacks(volumePoll);
        volumePollHandler.postDelayed(volumePoll, 400);
    }

    @Override
    protected void onPause() {
        super.onPause();
        StateBus.get().removeListener(stateListener);
        volumePollHandler.removeCallbacks(volumePoll);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        volumePollHandler.removeCallbacks(volumePoll);
        if (volumeObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(volumeObserver);
            } catch (Exception ignored) {
            }
            volumeObserver = null;
        }
    }

    private void render(PlayerUiState s) {
        trackTitle.setText(s.title);
        trackArtist.setText(s.artist);
        trackAlbum.setText(s.album);

        Bitmap art = s.art;
        if (s.source == PlayerUiState.Source.AIRPLAY || s.source == PlayerUiState.Source.IDLE) {
            albumArt.setImageResource(R.drawable.ic_airplay);
        } else {
            albumArt.setImageResource(R.drawable.ic_music_note);
            if (art != null) {
                albumArt.setImageBitmap(art);
            }
        }

        if (s.source == PlayerUiState.Source.AIRPLAY) {
            sourceBadge.setText(getString(R.string.source_airplay) + " · " + s.clientName);
        } else if (s.source == PlayerUiState.Source.LOCAL) {
            sourceBadge.setText(R.string.source_local);
        } else {
            sourceBadge.setText(R.string.source_idle);
        }

        btnPlay.setImageResource(s.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        btnPlay.setContentDescription(getString(s.playing ? R.string.pause : R.string.play));

        boolean showSeek = s.source == PlayerUiState.Source.LOCAL && s.durationMs > 0;
        seekRow.setVisibility(showSeek ? View.VISIBLE : View.GONE);
        if (showSeek) {
            if (!seeking) {
                int progress = s.durationMs > 0 ? (int) ((long) s.positionMs * 1000 / s.durationMs) : 0;
                seekBar.setProgress(Math.min(1000, Math.max(0, progress)));
                positionText.setText(formatTime(s.positionMs));
            }
            durationText.setText(formatTime(s.durationMs));
        }

    }

    private String formatTime(long ms) {
        long totalSec = ms / 1000;
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60);
    }
}
