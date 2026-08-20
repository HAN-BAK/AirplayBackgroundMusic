package com.airmusic.player;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
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
import com.airmusic.player.util.BlurBackground;
import com.airmusic.player.util.DiagnosticLog;
import com.airmusic.player.util.Prefs;
import com.airmusic.player.util.StorageHelper;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;

public class SettingsActivity extends BaseActivity {

    private Prefs prefs;
    private TextInputEditText inputName;
    private TextView pathDisplay;
    private RadioGroup radioMode;
    private Switch switchAutoPlay;
    private Switch switchShowApps;
    private com.google.android.material.button.MaterialButton btnBlurMode;
    private SeekBar seekBalance;
    private TextView airplayStatus;
    private TextView txtStorageInfo;
    private com.google.android.material.button.MaterialButton btnTransfer;
    private View btnEqualizer;

    private final ActivityResultLauncher<Intent> folderPicker =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String path = result.getData().getStringExtra(FolderPickerActivity.EXTRA_RESULT_PATH);
                    if (path != null) {
                        String display = result.getData().getStringExtra(
                                FolderPickerActivity.EXTRA_RESULT_DISPLAY);
                        if (display == null) display = path;
                        // Auto-save the folder and rescan immediately.
                        prefs.setMusicFolderPath(path, display);
                        pathDisplay.setText(formatFolderDisplay(display, path));
                        PlaybackService svc = PlaybackService.getInstance();
                        if (svc != null) svc.rescanLibrary();
                    }
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        BlurBackground.apply(this, R.color.background);

        prefs = new Prefs(this);
        inputName = findViewById(R.id.input_airplay_name);
        pathDisplay = findViewById(R.id.path_display);
        radioMode = findViewById(R.id.radio_mode);
        switchAutoPlay = findViewById(R.id.switch_auto_play);
        switchShowApps = findViewById(R.id.switch_show_apps);
        btnBlurMode = findViewById(R.id.btn_blur_mode);
        seekBalance = findViewById(R.id.seek_balance);
        airplayStatus = findViewById(R.id.airplay_status);
        txtStorageInfo = findViewById(R.id.txt_storage_info);
        btnTransfer = findViewById(R.id.btn_transfer);
        btnEqualizer = findViewById(R.id.btn_equalizer);

        inputName.setText(prefs.getAirPlayName());
        String folderPath = prefs.getMusicFolderPath();
        String folderUri = prefs.getMusicFolderUri();
        String folderDisplay = prefs.getMusicFolderDisplay();
        if (folderPath != null) {
            pathDisplay.setText(formatFolderDisplay(folderDisplay, folderPath));
        } else if (folderUri != null) {
            pathDisplay.setText(formatFolderDisplay(folderDisplay, folderUri));
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
        switchAutoPlay.setOnCheckedChangeListener((b, checked) ->
                prefs.setAutoPlayOnStart(checked));
        switchShowApps.setChecked(prefs.isShowAppsButton());
        switchShowApps.setOnCheckedChangeListener((b, checked) ->
                prefs.setShowAppsButton(checked));
        setupBlurModeButton();

        inputName.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String name = s.toString().trim();
                if (name.length() > 0) {
                    prefs.setAirPlayName(name);
                }
            }
        });
        inputName.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                PlaybackService service = PlaybackService.getInstance();
                if (service != null) service.restartAirPlay();
            }
        });

        radioMode.setOnCheckedChangeListener((group, checkedId) -> {
            String mode;
            if (checkedId == R.id.mode_repeat_one) {
                mode = Prefs.PLAY_MODE_REPEAT_ONE;
            } else if (checkedId == R.id.mode_shuffle) {
                mode = Prefs.PLAY_MODE_SHUFFLE;
            } else if (checkedId == R.id.mode_folder_loop) {
                mode = Prefs.PLAY_MODE_FOLDER_LOOP;
            } else {
                mode = Prefs.PLAY_MODE_SEQUENCE;
            }
            prefs.setPlayMode(mode);
            PlaybackService service = PlaybackService.getInstance();
            if (service != null) service.applyPlayMode(mode);
        });

        setupSection(findViewById(R.id.section_airplay), findViewById(R.id.airplay_content));
        setupSection(findViewById(R.id.section_local), findViewById(R.id.local_content));
        setupSection(findViewById(R.id.section_ui), findViewById(R.id.ui_content));
        setupSection(findViewById(R.id.section_system), findViewById(R.id.system_content));
        setupSection(findViewById(R.id.section_about), findViewById(R.id.about_content));
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
            pathDisplay.setText(R.string.pref_music_path_hint);
            PlaybackService service = PlaybackService.getInstance();
            if (service != null) service.rescanLibrary();
            Toast.makeText(this, R.string.path_cleared, Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_rescan).setOnClickListener(v -> {
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
        findViewById(R.id.btn_wifi_settings).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, R.string.home_settings_unavailable, Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.btn_equalizer).setOnClickListener(v ->
                startActivity(new Intent(this, EqualizerActivity.class)));
        btnTransfer.setOnClickListener(v -> {
            // Low disk space: refuse to open the transfer page.
            if (!StorageHelper.hasEnoughSpace(prefs.getMusicFolderPath(), 0)) {
                Toast.makeText(this, R.string.storage_low_warning, Toast.LENGTH_LONG).show();
                return;
            }
            startActivity(new Intent(this, TransferActivity.class));
        });
        findViewById(R.id.btn_export_logs).setOnClickListener(v -> exportLogs());
        setupLanguageButton();
        findViewById(R.id.btn_about).setOnClickListener(v ->
                startActivity(new Intent(this, AboutActivity.class)));
        refreshStorageInfo();
    }

    @Override
    protected void onResume() {
        super.onResume();
        BlurBackground.apply(this, R.color.background);
        refreshAirPlayStatus();
        refreshStorageInfo();
        // The equalizer now uses a dedicated instance for the multi-room
        // output, so it can stay enabled while multi-room is active.
        btnEqualizer.setEnabled(true);
        btnEqualizer.setAlpha(1f);
    }

    private void refreshStorageInfo() {
        String folder = prefs.getMusicFolderPath();
        long total = StorageHelper.getTotalBytes(folder);
        long usable = StorageHelper.getUsableBytes(folder);
        txtStorageInfo.setText(getString(R.string.storage_info,
                StorageHelper.formatSize(total), StorageHelper.formatSize(usable)));
        boolean enough = StorageHelper.hasEnoughSpace(folder, 0);
        btnTransfer.setAlpha(enough ? 1f : 0.45f);
        btnTransfer.setClickable(true);
    }

    /** Makes a section header expand/collapse its content. */
    private void setupSection(TextView title, View content) {
        content.setVisibility(View.GONE);
        title.setText("▸ " + title.getText());
        title.setOnClickListener(v -> {
            boolean visible = content.getVisibility() == View.VISIBLE;
            content.setVisibility(visible ? View.GONE : View.VISIBLE);
            String name = title.getText().toString().replaceFirst("^[▸▾] ", "");
            title.setText((visible ? "▸ " : "▾ ") + name);
        });
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

    private void setupLanguageButton() {
        com.google.android.material.button.MaterialButton btnLanguage =
                findViewById(R.id.btn_language);
        updateLanguageLabel(btnLanguage);
        btnLanguage.setOnClickListener(v -> {
            String[] langs = {"zh", "en", "ja", "ko"};
            String[] labels = {
                    getString(R.string.language_zh),
                    getString(R.string.language_en),
                    getString(R.string.language_ja),
                    getString(R.string.language_ko)
            };
            String current = prefs.getLanguage();
            int checked = 0;
            for (int i = 0; i < langs.length; i++) {
                if (langs[i].equals(current)) checked = i;
            }
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.language_dialog_title)
                    .setSingleChoiceItems(labels, checked, (d, which) -> {
                        prefs.setLanguage(langs[which]);
                        PlaybackService service = PlaybackService.getInstance();
                        if (service != null) {
                            // The service may have been running since boot in
                            // the old language; rebuild its UI strings now.
                            service.applyUiLanguage();
                        }
                        d.dismiss();
                        updateLanguageLabel(btnLanguage);
                        // Recreate this screen and let the other activities
                        // refresh themselves when they resume.
                        recreate();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
    }

    private void setupBlurModeButton() {
        updateBlurModeLabel();
        btnBlurMode.setOnClickListener(v -> {
            String[] modes = {Prefs.BLUR_DARK, Prefs.BLUR_OFF};
            String[] labels = {
                    getString(R.string.blur_mode_dark),
                    getString(R.string.blur_mode_off)
            };
            String current = prefs.getBlurMode();
            int checked = 0;
            for (int i = 0; i < modes.length; i++) {
                if (modes[i].equals(current)) checked = i;
            }
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.blur_dialog_title)
                    .setSingleChoiceItems(labels, checked, (d, which) -> {
                        prefs.setBlurMode(modes[which]);
                        BlurBackground.apply(SettingsActivity.this, R.color.background);
                        d.dismiss();
                        updateBlurModeLabel();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
    }

    private void updateBlurModeLabel() {
        String mode = prefs.getBlurMode();
        String label;
        if (Prefs.BLUR_OFF.equals(mode)) label = getString(R.string.blur_mode_off);
        else label = getString(R.string.blur_mode_dark);
        btnBlurMode.setText(label);
    }

    private void updateLanguageLabel(com.google.android.material.button.MaterialButton btn) {
        String lang = prefs.getLanguage();
        String label;
        if ("en".equals(lang)) label = getString(R.string.language_en);
        else if ("ja".equals(lang)) label = getString(R.string.language_ja);
        else if ("ko".equals(lang)) label = getString(R.string.language_ko);
        else label = getString(R.string.language_zh);
        btn.setText(label);
    }

    /** Shows the folder display name and path on one line when they are identical. */
    private String formatFolderDisplay(String display, String path) {
        if (display == null || display.trim().length() == 0 || display.equals(path)) {
            return path;
        }
        return display + "\n" + path;
    }

}
