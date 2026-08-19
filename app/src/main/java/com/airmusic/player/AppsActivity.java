package com.airmusic.player;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.airmusic.player.ui.AppsAdapter;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Lists all launchable apps on the device; tapping one opens it, like the
 * device launcher.
 */
public class AppsActivity extends BaseActivity {

    private final AppsAdapter adapter = new AppsAdapter();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apps);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        RecyclerView list = findViewById(R.id.app_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        adapter.setOnAppClick(app -> {
            try {
                startActivity(app.launchIntent);
            } catch (Exception e) {
                Toast.makeText(this, getString(R.string.apps_open_failed, app.label),
                        Toast.LENGTH_SHORT).show();
            }
        });

        loadApps();
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> infos = pm.queryIntentActivities(main, 0);
        List<AppsAdapter.AppInfo> apps = new ArrayList<>();
        for (ResolveInfo info : infos) {
            try {
                String label = info.loadLabel(pm).toString();
                Drawable icon = info.loadIcon(pm);
                Intent launch = new Intent(Intent.ACTION_MAIN);
                launch.addCategory(Intent.CATEGORY_LAUNCHER);
                launch.setClassName(info.activityInfo.packageName, info.activityInfo.name);
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                apps.add(new AppsAdapter.AppInfo(label, info.activityInfo.packageName, icon, launch));
            } catch (Exception ignored) {
            }
        }

        final Collator collator = Collator.getInstance(Locale.CHINESE);
        Collections.sort(apps, new Comparator<AppsAdapter.AppInfo>() {
            @Override
            public int compare(AppsAdapter.AppInfo a, AppsAdapter.AppInfo b) {
                return collator.compare(a.label, b.label);
            }
        });
        adapter.setApps(apps);
    }
}
