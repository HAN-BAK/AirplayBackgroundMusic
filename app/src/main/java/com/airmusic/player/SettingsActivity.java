package com.airmusic.player;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.airmusic.player.service.PlaybackService;
import com.airmusic.player.util.DiagnosticLog;
import com.airmusic.player.util.Prefs;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;

public class SettingsActivity extends AppCompatActivity {

    private Prefs prefs;
    private TextInputEditText inputName;
    private TextView pathDisplay;
    private RadioGroup radioMode;
    private Switch switchAutoPlay;
    private SeekBar seekBalance;
    private TextView airplayStatus;

    private String pendingFolderPath;
    private String pendingFolderDisplay;

    private final ActivityResultLauncher<Intent> folderPicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String path = result.getData().getStringExtra(FolderPickerActivity.EXTRA_RESULT_PATH);
                    if (path != null) {
                        pendingFolderPath = path;
                        pendingFolderDisplay = result.getData().getStringExtra(
                                FolderPickerActivity.EXTRA_RESULT_DISPLAY);
                        if (pendingFolderDisplay == null) pendingFolderDisplay = path;
                        pathDisplay.setText(pendingFolderDisplay + "\n" + path);
                    }
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = new Prefs(this);
        inputName = findViewById(R.id.input_airplay_name);
        pathDisplay = findViewById(R.id.path_display);
        radioMode = findViewById(R.id.radio_mode);
        switchAutoPlay = findViewById(R.id.switch_auto_play);
        seekBalance = findViewById(R.id.seek_balance);
        airplayStatus = findViewById(R.id.airplay_status);

        inputName.setText(prefs.getAirPlayName());
        String folderPath = prefs.getMusicFolderPath();
        String folderUri = prefs.getMusicFolderUri();
        String folderDisplay = prefs.getMusicFolderDisplay();
        if (folderPath != null) {
            pathDisplay.setText((folderDisplay == null ? "" : folderDisplay + "\n") + folderPath);
        } else if (folderUri != null) {
            pathDisplay.setText((folderDisplay == null ? "" : folderDisplay + "\n") + folderUri);
        }

        switch (prefs.getPlayMode()) {
            case Prefs.PLAY_MODE_REPEAT_ONE:
                radioMode.check(R.id.mode_repeat_one);
                break;
            case Prefs.PLAY_MODE_SHUFFLE:
                radioMode.check(R.id.mode_shuffle);
                break;
            case Prefs.PLAY_MODE_FOLDER_LOOP:
                radioMode.check(R.id.mode_folder_loop);
                break;
            default:
                radioMode.check(R.id.mode_sequence);
                break;
        }
        switchAutoPlay.setChecked(prefs.isAutoPlayOnStart());
        seekBalance.setProgress((int) ((prefs.getBalance() + 1f) * 100f));
        seekBalance.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    PlaybackService service = PlaybackService.getInstance();
                    if (service != null) service.setBalance((progress / 100f) - 1f);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        findViewById(R.id.btn_reset_balance).setOnClickListener(v -> {
            seekBalance.setProgress(100);
            prefs.setBalance(0f);
            PlaybackService service = PlaybackService.getInstance();
            if (service != null) service.setBalance(0f);
            Toast.makeText(this, R.string.balance_reset, Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_choose_folder).setOnClickListener(v ->
                folderPicker.launch(new Intent(this, FolderPickerActivity.class)));
        findViewById(R.id.btn_clear_path).setOnClickListener(v -> {
            prefs.clearMusicFolder();
            pendingFolderPath = null;
            pendingFolderDisplay = null;
            pathDisplay.setText(R.string.pref_music_path_hint);
            Toast.makeText(this, R.string.path_cleared, Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_save).setOnClickListener(v -> save());
        findViewById(R.id.btn_rescan).setOnClickListener(v -> {
            save();
            PlaybackService service = PlaybackService.getInstance();
            if (service != null) service.rescanLibrary();
            Toast.makeText(this, R.string.rescanning, Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btn_set_home).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, R.string.home_settings_unavailable, Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.btn_export_logs).setOnClickListener(v -> exportLogs());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAirPlayStatus();
    }

    private void refreshAirPlayStatus() {
        PlaybackService service = PlaybackService.getInstance();
        if (service != null) {
            airplayStatus.setText(service.getAirPlayStatus());
        } else {
            airplayStatus.setText("service: not running");
        }
    }

    private void exportLogs() {
        File logFile = DiagnosticLog.getLogFile(this);
        if (logFile == null) {
            Toast.makeText(this, R.string.logs_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, "com.airmusic.player.fileprovider", logFile);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.export_logs_share));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, getString(R.string.export_logs_share)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.export_logs_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void save() {
        String name = inputName.getText() == null ? "" : inputName.getText().toString().trim();
        if (name.length() == 0) {
            Toast.makeText(this, R.string.pref_airplay_name_hint, Toast.LENGTH_SHORT).show();
            return;
        }

        boolean nameChanged = !name.equals(prefs.getAirPlayName());
        prefs.setAirPlayName(name);

        if (pendingFolderPath != null) {
            prefs.setMusicFolderPath(pendingFolderPath, pendingFolderDisplay);
            pendingFolderPath = null;
            pendingFolderDisplay = null;
        }

        String mode;
        int checked = radioMode.getCheckedRadioButtonId();
        if (checked == R.id.mode_repeat_one) {
            mode = Prefs.PLAY_MODE_REPEAT_ONE;
        } else if (checked == R.id.mode_shuffle) {
            mode = Prefs.PLAY_MODE_SHUFFLE;
        } else if (checked == R.id.mode_folder_loop) {
            mode = Prefs.PLAY_MODE_FOLDER_LOOP;
        } else {
            mode = Prefs.PLAY_MODE_SEQUENCE;
        }
        prefs.setPlayMode(mode);
        prefs.setAutoPlayOnStart(switchAutoPlay.isChecked());
        prefs.setBalance((seekBalance.getProgress() / 100f) - 1f);

        PlaybackService service = PlaybackService.getInstance();
        if (service != null) {
            service.setBalance((seekBalance.getProgress() / 100f) - 1f);
            service.applyPlayMode(mode);
            if (nameChanged) {
                service.restartAirPlay();
                Toast.makeText(this, R.string.engine_restarting, Toast.LENGTH_SHORT).show();
            }
            service.rescanLibrary();
        }
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        finish();
    }

}
