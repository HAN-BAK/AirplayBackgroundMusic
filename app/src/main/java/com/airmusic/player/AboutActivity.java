package com.airmusic.player;

import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.airmusic.player.util.BlurBackground;

/** Display-only "about" screen: the info rows are not interactive. */
public class AboutActivity extends BaseActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        BlurBackground.apply(this, R.color.background);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        TextView txtVersion = findViewById(R.id.txt_version);
        try {
            String version = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            txtVersion.setText(getString(R.string.about_version, version));
        } catch (Exception e) {
            txtVersion.setText("");
        }
    }
}
