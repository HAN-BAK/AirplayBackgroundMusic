package com.airmusic.player;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.airmusic.player.library.AudioExt;
import com.airmusic.player.util.BlurBackground;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A lightweight, self-contained folder browser used to pick the local music
 * path. It browses internal storage and USB volumes directly via the
 * filesystem, so it works on devices that ship without a system file manager.
 */
public class FolderPickerActivity extends BaseActivity {

    public static final String EXTRA_RESULT_PATH = "result_path";
    public static final String EXTRA_RESULT_DISPLAY = "result_display";

    private TextView pathText;
    private TextView statusText;
    private Button btnGrant;
    private ListView listView;
    private Button btnSelect;

    private final List<FolderEntry> entries = new ArrayList<>();
    private final List<File> roots = new ArrayList<>();
    private final List<String> rootLabels = new ArrayList<>();
    private ArrayAdapter<FolderEntry> adapter;
    private File currentDir;

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    loadRoots();
                } else {
                    showStatus(getString(R.string.folder_permission_denied));
                }
            });

    private static class FolderEntry {
        final File dir;
        final String name;
        final String sub;

        FolderEntry(File dir, String name, String sub) {
            this.dir = dir;
            this.name = name;
            this.sub = sub;
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_folder_picker);
        BlurBackground.apply(this, R.color.background);

        pathText = findViewById(R.id.path_text);
        statusText = findViewById(R.id.status_text);
        btnGrant = findViewById(R.id.btn_grant);
        listView = findViewById(R.id.folder_list);
        btnSelect = findViewById(R.id.btn_select);

        adapter = new ArrayAdapter<FolderEntry>(this, R.layout.item_folder, R.id.item_name, entries) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                FolderEntry entry = getItem(position);
                TextView name = view.findViewById(R.id.item_name);
                TextView sub = view.findViewById(R.id.item_sub);
                name.setText(entry.name);
                sub.setText(entry.sub);
                return view;
            }
        };
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            FolderEntry entry = entries.get(position);
            openFolder(entry.dir);
        });

        btnSelect.setOnClickListener(v -> confirmSelection());
        btnGrant.setOnClickListener(v -> openAllFilesSettings());
        findViewById(R.id.btn_back).setOnClickListener(v -> navigateUp());

        if (hasStorageAccess()) {
            loadRoots();
        } else {
            requestStoragePermission();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Returning from the "All files access" settings screen.
        if (hasStorageAccess() && roots.isEmpty() && currentDir == null) {
            loadRoots();
        }
    }

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            showStatus(getString(R.string.folder_need_permission));
            btnGrant.setVisibility(View.VISIBLE);
        } else {
            btnGrant.setVisibility(View.GONE);
            showStatus(getString(R.string.folder_need_permission));
            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
    }

    private void openAllFilesSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception ex) {
                Toast.makeText(this, R.string.folder_permission_denied, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showStatus(String text) {
        statusText.setVisibility(View.VISIBLE);
        statusText.setText(text);
    }

    private void loadRoots() {
        btnGrant.setVisibility(View.GONE);
        statusText.setVisibility(View.GONE);
        currentDir = null;
        roots.clear();
        rootLabels.clear();

        File primary = Environment.getExternalStorageDirectory();
        if (primary != null && primary.isDirectory()) {
            roots.add(primary);
            rootLabels.add(getString(R.string.folder_internal_storage));
        }

        // USB / removable volumes show up as mount points under /storage.
        File storageDir = new File("/storage");
        if (storageDir.isDirectory()) {
            File[] mounts = storageDir.listFiles();
            if (mounts != null) {
                Arrays.sort(mounts, (a, b) -> a.getName().compareTo(b.getName()));
                for (File mount : mounts) {
                    if (!mount.isDirectory() || mount.getName().startsWith(".")) continue;
                    if (primary != null && (mount.equals(primary)
                            || mount.getAbsolutePath().startsWith(primary.getAbsolutePath() + "/"))) {
                        continue;
                    }
                    roots.add(mount);
                    rootLabels.add(labelForVolume(mount));
                }
            }
        }

        if (roots.isEmpty()) {
            showStatus(getString(R.string.folder_permission_denied));
            return;
        }

        entries.clear();
        for (int i = 0; i < roots.size(); i++) {
            File root = roots.get(i);
            entries.add(new FolderEntry(root, rootLabels.get(i), root.getAbsolutePath()));
        }
        pathText.setText(getString(R.string.folder_pick_hint));
        btnSelect.setEnabled(false);
        adapter.notifyDataSetChanged();
    }

    private String labelForVolume(File mount) {
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                StorageManager sm = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
                if (sm != null) {
                    for (StorageVolume volume : sm.getStorageVolumes()) {
                        String uuid = volume.getUuid();
                        if (uuid != null && uuid.equalsIgnoreCase(mount.getName())) {
                            String desc = volume.getDescription(this);
                            if (desc != null && desc.trim().length() > 0) {
                                return desc;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return getString(R.string.folder_usb_storage) + " (" + mount.getName() + ")";
    }

    private void openFolder(File dir) {
        if (dir == null || !dir.isDirectory()) {
            Toast.makeText(this, R.string.folder_permission_denied, Toast.LENGTH_SHORT).show();
            return;
        }
        currentDir = dir;
        pathText.setText(dir.getAbsolutePath());
        entries.clear();
        File[] files = dir.listFiles();
        if (files != null) {
            Arrays.sort(files, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) {
                    return a.isDirectory() ? -1 : 1;
                }
                return a.getName().toLowerCase(Locale.US)
                        .compareTo(b.getName().toLowerCase(Locale.US));
            });
            for (File f : files) {
                if (f.isDirectory() && !f.isHidden()) {
                    int count = countAudioFiles(f);
                    String sub = count > 0
                            ? getString(R.string.folder_audio_count, count)
                            : getString(R.string.folder_empty);
                    entries.add(new FolderEntry(f, f.getName(), sub));
                }
            }
        }
        if (entries.isEmpty()) {
            showStatus(getString(R.string.folder_empty));
        } else {
            statusText.setVisibility(View.GONE);
        }
        btnSelect.setEnabled(true);
        adapter.notifyDataSetChanged();
    }

    /** Counts audio files directly inside a folder (cheap, non-recursive). */
    private static int countAudioFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int count = 0;
        for (File f : files) {
            if (f.isFile() && AudioExt.isAudio(f.getName())) count++;
        }
        return count;
    }

    private void navigateUp() {
        if (currentDir == null) {
            finish();
            return;
        }
        File parent = currentDir.getParentFile();
        if (parent != null && parent.isDirectory() && isInsideRoots(parent)) {
            openFolder(parent);
        } else {
            loadRoots();
        }
    }

    private boolean isInsideRoots(File dir) {
        String path = dir.getAbsolutePath();
        for (File root : roots) {
            if (path.equals(root.getAbsolutePath()) || path.startsWith(root.getAbsolutePath() + "/")) {
                return true;
            }
        }
        return false;
    }

    private void confirmSelection() {
        if (currentDir == null) return;
        Intent data = new Intent();
        data.putExtra(EXTRA_RESULT_PATH, currentDir.getAbsolutePath());
        data.putExtra(EXTRA_RESULT_DISPLAY, currentDir.getAbsolutePath());
        setResult(Activity.RESULT_OK, data);
        finish();
    }

    @Override
    public void onBackPressed() {
        navigateUp();
    }
}
