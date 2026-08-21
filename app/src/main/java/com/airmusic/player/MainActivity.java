package com.airmusic.player;

import android.Manifest;
import android.app.AlertDialog;
import android.app.ProgressDialog;
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
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.airmusic.player.multicast.MultiRoomDiscovery;
import com.airmusic.player.multicast.MultiRoomAudioPlayer;
import com.airmusic.player.multicast.MultiRoomManager;
import com.airmusic.player.playback.EqAudioProcessor;
import com.airmusic.player.service.PlaybackService;
import com.airmusic.player.util.PlayerUiState;
import com.airmusic.player.util.BlurBackground;
import com.airmusic.player.util.Prefs;
import com.airmusic.player.util.StateBus;

import nz.co.iswe.android.airplay.audio.AudioOutputQueue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends BaseActivity {

    private static final String ACTION_CAPTURE_START = "com.airmusic.player.CAPTURE_START";
    private static final String ACTION_CAPTURE_STOP = "com.airmusic.player.CAPTURE_STOP";

    private ImageView albumArt;
    private TextView trackTitle;
    private TextView trackArtist;
    private TextView trackAlbum;
    private TextView sourceBadge;
    private TextView positionText;
    private TextView durationText;
    private ImageButton btnPlay;
    private ImageButton btnNext;
    private ImageButton btnPrev;
    private ImageButton btnMulticast;
    private ImageButton btnLibrary;
    private ImageButton btnApps;
    private ProgressBar multicastProgress;
    private ProgressBar playProgress;
    private ProgressBar nextProgress;
    private ProgressBar prevProgress;
    private ImageView volumeIcon;
    private SeekBar seekBar;
    private SeekBar volumeSeek;
    private View seekRow;

    private boolean seeking;
    private PlayerUiState lastState;
    /** True while a receiver transport command is waiting on the master. */
    private boolean controlPending;
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

    /** Restores the disconnect button if the remote session never ends. */
    private final Runnable restoreMulticastUi = new Runnable() {
        @Override
        public void run() {
            multicastProgress.setVisibility(View.GONE);
            btnMulticast.setVisibility(View.VISIBLE);
        }
    };

    /** Safety net: drop the loading spinner if the master never replies. */
    private final Runnable controlTimeoutRunnable = this::hideControlSpinners;

    private final PlaybackService.ControlAckListener controlAckListener =
            this::hideControlSpinners;

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
        BlurBackground.apply(this, R.drawable.bg_main_gradient);
        handleCaptureIntent(getIntent());

        albumArt = findViewById(R.id.album_art);
        trackTitle = findViewById(R.id.track_title);
        trackArtist = findViewById(R.id.track_artist);
        trackAlbum = findViewById(R.id.track_album);
        sourceBadge = findViewById(R.id.source_badge);
        positionText = findViewById(R.id.position_text);
        durationText = findViewById(R.id.duration_text);
        btnPlay = findViewById(R.id.btn_play);
        btnNext = findViewById(R.id.btn_next);
        btnPrev = findViewById(R.id.btn_prev);
        btnMulticast = findViewById(R.id.btn_multicast);
        btnLibrary = findViewById(R.id.btn_library);
        multicastProgress = findViewById(R.id.multicast_progress);
        playProgress = findViewById(R.id.play_progress);
        nextProgress = findViewById(R.id.next_progress);
        prevProgress = findViewById(R.id.prev_progress);
        volumeIcon = findViewById(R.id.volume_icon);
        btnApps = findViewById(R.id.btn_apps);
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
            compactForSmallScreens();
        });

        findViewById(R.id.btn_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btn_library).setOnClickListener(v ->
                startActivity(new Intent(this, LibraryActivity.class)));
        findViewById(R.id.btn_apps).setOnClickListener(v ->
                startActivity(new Intent(this, AppsActivity.class)));
        findViewById(R.id.btn_multicast).setOnClickListener(v -> {
            if (lastState != null && lastState.source == PlayerUiState.Source.REMOTE) {
                // Receiver UI: ask the master to disconnect this device.
                PlaybackService service = PlaybackService.getInstance();
                if (service != null) {
                    // Show a loading indicator while the fade-out + network
                    // disconnect runs, so the tap does not look stuck.
                    multicastProgress.setVisibility(View.VISIBLE);
                    btnMulticast.setVisibility(View.INVISIBLE);
                    volumePollHandler.removeCallbacks(restoreMulticastUi);
                    volumePollHandler.postDelayed(restoreMulticastUi, 8000);
                    service.disconnectFromMaster();
                }
            } else {
                openMultiRoomDialog();
            }
        });
        findViewById(R.id.btn_prev).setOnClickListener(v -> {
            PlaybackService service = PlaybackService.getInstance();
            if (service != null) {
                service.addControlAckListener(controlAckListener);
                if (isReceiverMode()) showControlSpinner(btnPrev, prevProgress);
                service.previous();
            }
        });
        findViewById(R.id.btn_next).setOnClickListener(v -> {
            PlaybackService service = PlaybackService.getInstance();
            if (service != null) {
                service.addControlAckListener(controlAckListener);
                if (isReceiverMode()) showControlSpinner(btnNext, nextProgress);
                service.next();
            }
        });
        btnPlay.setOnClickListener(v -> {
            PlaybackService service = PlaybackService.getInstance();
            if (service != null) {
                service.addControlAckListener(controlAckListener);
                if (isReceiverMode()) showControlSpinner(btnPlay, playProgress);
                service.togglePlay();
            }
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
        PlaybackService service = PlaybackService.getInstance();
        if (service != null) {
            service.addControlAckListener(controlAckListener);
        }
        requestPermissionsIfNeeded();
    }

    /** Compacts the right panel on very short screens (e.g. 4:3 mini boxes)
     *  so the transport controls and the progress bar stay visible. Screens
     *  as tall as the reference TV box keep the original layout untouched. */
    private void compactForSmallScreens() {
        View rightPanel = findViewById(R.id.right_panel);
        if (rightPanel == null) return;
        float d = getResources().getDisplayMetrics().density;
        int heightDp = Math.round(rightPanel.getHeight() / d);
        if (heightDp >= 360) return; // box-sized UI unchanged
        boolean tiny = heightDp < 250; // very short screens (PA03 class)
        rightPanel.setPadding(Math.round(36 * d),
                Math.round((tiny ? 6 : 12) * d),
                Math.round(24 * d), 0);
        trackTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, tiny ? 10 : 15);
        trackTitle.setMaxLines(1);
        trackArtist.setTextSize(TypedValue.COMPLEX_UNIT_SP, tiny ? 9 : 12);
        trackAlbum.setTextSize(TypedValue.COMPLEX_UNIT_SP, tiny ? 8 : 11);
        int badgePad = Math.round((tiny ? 6 : 10) * d);
        int badgePadV = Math.round((tiny ? 0 : 2) * d);
        sourceBadge.setPadding(badgePad, badgePadV, badgePad, badgePadV);
        if (tiny) {
            ViewGroup.MarginLayoutParams mlp =
                    (ViewGroup.MarginLayoutParams) sourceBadge.getLayoutParams();
            mlp.topMargin = Math.round(2 * d);
            sourceBadge.setLayoutParams(mlp);
        }
        seekBar.getLayoutParams().height =
                Math.round((tiny ? 18 : 34) * d);
        if (tiny) {
            positionText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
            durationText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8);
        }
        int play = Math.round((tiny ? 36 : 50) * d);
        int side = Math.round((tiny ? 30 : 40) * d);
        btnPlay.getLayoutParams().width = play;
        btnPlay.getLayoutParams().height = play;
        btnPlay.setPadding(Math.round(8 * d), Math.round(8 * d),
                Math.round(8 * d), Math.round(8 * d));
        btnPrev.getLayoutParams().width = side;
        btnPrev.getLayoutParams().height = side;
        btnNext.getLayoutParams().width = side;
        btnNext.getLayoutParams().height = side;
        if (tiny) {
            View playWrap = (View) btnPlay.getParent();
            if (playWrap != null) {
                ViewGroup.MarginLayoutParams mlp =
                        (ViewGroup.MarginLayoutParams) playWrap.getLayoutParams();
                mlp.setMargins(Math.round(8 * d), 0, Math.round(8 * d), 0);
                playWrap.setLayoutParams(mlp);
            }
        }
    }

    /** True while this device is receiving a multi-room stream. */
    private boolean isReceiverMode() {
        return lastState != null
                && lastState.source == PlayerUiState.Source.REMOTE;
    }

    /** Shows a loading spinner on a transport button while the receiver's
     *  command is waiting for the master to execute and broadcast back. */
    private void showControlSpinner(View icon, ProgressBar spinner) {
        controlPending = true;
        if (icon == btnPlay) {
            // Keep the circular sky-blue frame visible; hide only the glyph.
            btnPlay.setImageResource(android.R.color.transparent);
        } else {
            icon.setVisibility(View.INVISIBLE);
        }
        spinner.setVisibility(View.VISIBLE);
        volumePollHandler.removeCallbacks(controlTimeoutRunnable);
        volumePollHandler.postDelayed(controlTimeoutRunnable, 4000);
    }

    private void hideControlSpinners() {
        if (!controlPending) return;
        controlPending = false;
        volumePollHandler.removeCallbacks(controlTimeoutRunnable);
        if (playProgress != null) playProgress.setVisibility(View.GONE);
        if (nextProgress != null) nextProgress.setVisibility(View.GONE);
        if (prevProgress != null) prevProgress.setVisibility(View.GONE);
        if (btnPlay != null) {
            btnPlay.setVisibility(View.VISIBLE);
            if (lastState != null) {
                btnPlay.setImageResource(
                        lastState.playing ? R.drawable.ic_pause : R.drawable.ic_play);
            }
        }
        if (btnNext != null) btnNext.setVisibility(View.VISIBLE);
        if (btnPrev != null) btnPrev.setVisibility(View.VISIBLE);
    }

    /** Binds the bottom-bar slider to the system media volume. */
    private void setupVolumeSlider() {
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager == null || volumeSeek == null) return;

        volumeSeek.setMax(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        volumeSeek.setProgress(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC));
        updateVolumeIcon(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC));
        volumeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
                }
                updateVolumeIcon(progress);
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
                updateVolumeIcon(current);
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
            updateVolumeIcon(current);
        }
    }

    /** Switches the slider's leading icon to the mute glyph at volume 0. */
    private void updateVolumeIcon(int volume) {
        if (volumeIcon != null) {
            volumeIcon.setImageResource(
                    volume <= 0 ? R.drawable.ic_volume_mute : R.drawable.ic_volume);
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
        btnApps.setVisibility(new Prefs(this).isShowAppsButton()
                ? View.VISIBLE : View.GONE);
        syncVolumeSlider();
        volumePollHandler.removeCallbacks(volumePoll);
        volumePollHandler.postDelayed(volumePoll, 400);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleCaptureIntent(intent);
    }

    /** Debug-only PCM capture toggle driven from adb intents. */
    private void handleCaptureIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if (ACTION_CAPTURE_START.equals(action)) {
            File dir = getExternalFilesDir(null);
            if (dir != null) {
                EqAudioProcessor.startCapture(new File(dir, "eq_capture.pcm"));
                AudioOutputQueue.startCapture(new File(dir, "eq_capture_airplay.pcm"));
                MultiRoomAudioPlayer.startCapture(new File(dir, "mr_capture.pcm"));
            }
        } else if (ACTION_CAPTURE_STOP.equals(action)) {
            EqAudioProcessor.stopCapture();
            AudioOutputQueue.stopCapture();
            MultiRoomAudioPlayer.stopCapture();
        }
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
        volumePollHandler.removeCallbacks(controlTimeoutRunnable);
        PlaybackService service = PlaybackService.getInstance();
        if (service != null) {
            service.removeControlAckListener(controlAckListener);
        }
        if (volumeObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(volumeObserver);
            } catch (Exception ignored) {
            }
            volumeObserver = null;
        }
    }

    private void openMultiRoomDialog() {
        PlaybackService service = PlaybackService.getInstance();
        if (service == null) return;
        MultiRoomManager mgr = service.getMultiRoomManager();
        if (mgr == null) return;
        // Show a spinner while the network scan runs so the delay doesn't
        // feel like a freeze.
        ProgressDialog progress = new ProgressDialog(this);
        progress.setMessage(getString(R.string.multicast_scanning));
        progress.setCancelable(true);
        progress.show();
        final Handler scanHandler = new Handler(Looper.getMainLooper());
        final Runnable showDialog = () -> {
            if (progress.isShowing()) progress.dismiss();
            showMultiRoomDialog(mgr);
        };
        progress.setOnCancelListener(d -> scanHandler.removeCallbacks(showDialog));
        mgr.rescanDevices();
        scanHandler.postDelayed(showDialog, 2500);
    }

    private void showMultiRoomDialog(MultiRoomManager mgr) {
        List<MultiRoomDiscovery.DeviceInfo> devices = new ArrayList<>();
        for (MultiRoomDiscovery.DeviceInfo d : mgr.getDevices()) {
            devices.add(d);
        }
        if (devices.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.multicast_title)
                    .setMessage(R.string.multicast_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        String[] names = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) names[i] = devices.get(i).name;
        boolean[] checked = new boolean[devices.size()];
        List<MultiRoomDiscovery.DeviceInfo> selected = new ArrayList<>();
        // Remember which receivers are already connected so the checkboxes
        // keep their state between dialog opens.
        for (int i = 0; i < devices.size(); i++) {
            if (mgr.isTargetConnected(devices.get(i).name)) {
                checked[i] = true;
                selected.add(devices.get(i));
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.multicast_title)
                .setMultiChoiceItems(names, checked, (d, which, isChecked) -> {
                    if (isChecked) selected.add(devices.get(which));
                    else selected.remove(devices.get(which));
                })
                .setPositiveButton(R.string.multicast_confirm, (d, w) -> mgr.updateTargets(selected))
                .setNegativeButton(R.string.multicast_cancel, null)
                .show();
    }

    private void render(PlayerUiState s) {
        BlurBackground.apply(this, R.drawable.bg_main_gradient);

        // Leaving receiver mode (disconnect / AirPlay takes over) should
        // never leave a transport loading spinner stuck on screen.
        if (s.source != PlayerUiState.Source.REMOTE) {
            hideControlSpinners();
        }

        trackTitle.setText(s.title);
        trackArtist.setText(s.artist);
        trackAlbum.setText(s.album);

        Bitmap art = s.art;
        if (s.source == PlayerUiState.Source.AIRPLAY || s.source == PlayerUiState.Source.REMOTE) {
            if (art != null) {
                albumArt.setImageBitmap(art);
            } else {
                albumArt.setImageResource(R.drawable.ic_airplay);
            }
        } else if (s.source == PlayerUiState.Source.IDLE) {
            albumArt.setImageResource(R.drawable.ic_airplay);
        } else {
            albumArt.setImageResource(R.drawable.ic_music_note);
            if (art != null) {
                albumArt.setImageBitmap(art);
        }
    }

        if (s.source == PlayerUiState.Source.AIRPLAY) {
            sourceBadge.setText(getString(R.string.source_airplay) + " · " + s.clientName);
        } else if (s.source == PlayerUiState.Source.REMOTE) {
            sourceBadge.setText(R.string.source_remote);
        } else if (s.source == PlayerUiState.Source.LOCAL) {
            sourceBadge.setText(R.string.source_local);
        } else {
            sourceBadge.setText(R.string.source_idle);
        }

        if (!(controlPending && playProgress.getVisibility() == View.VISIBLE)) {
            btnPlay.setImageResource(s.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        }
        btnPlay.setContentDescription(getString(s.playing ? R.string.pause : R.string.play));

        if (s.source == PlayerUiState.Source.AIRPLAY) {
            // Multi-room is meaningless while receiving AirPlay.
            multicastProgress.setVisibility(View.GONE);
            volumePollHandler.removeCallbacks(restoreMulticastUi);
            btnMulticast.setVisibility(View.GONE);
            btnLibrary.setVisibility(View.GONE);
        } else if (s.source == PlayerUiState.Source.REMOTE) {
            // The library belongs to this device's own collection, not the
            // stream being received from the master.
            btnLibrary.setVisibility(View.GONE);
            // While a disconnect is fading out, incoming clock refreshes
            // arrive every ~500 ms; don't re-show the button on top of the
            // loading spinner (they would overlap).
            if (multicastProgress.getVisibility() != View.VISIBLE) {
                btnMulticast.setVisibility(View.VISIBLE);
                btnMulticast.setImageResource(R.drawable.ic_disconnect);
                btnMulticast.setContentDescription(getString(R.string.multicast_disconnect));
            }
        } else {
            btnLibrary.setVisibility(View.VISIBLE);
            multicastProgress.setVisibility(View.GONE);
            volumePollHandler.removeCallbacks(restoreMulticastUi);
            btnMulticast.setVisibility(View.VISIBLE);
            btnMulticast.setImageResource(R.drawable.ic_multicast);
            btnMulticast.setContentDescription(getString(R.string.multicast));
        }

        boolean showSeek = (s.source == PlayerUiState.Source.LOCAL
                || s.source == PlayerUiState.Source.REMOTE) && s.durationMs > 0;
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
