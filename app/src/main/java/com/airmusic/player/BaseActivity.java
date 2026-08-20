package com.airmusic.player;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.airmusic.player.util.LocaleHelper;
import com.airmusic.player.service.PlaybackService;
import com.airmusic.player.util.BlurBackground;

/** Applies the user-selected UI language to every activity. */
public abstract class BaseActivity extends AppCompatActivity {

    private String createdLang;

    @Override
    protected void attachBaseContext(@NonNull Context newBase) {
        createdLang = LocaleHelper.currentLanguage(newBase);
        super.attachBaseContext(LocaleHelper.attach(newBase));
    }

    @Override
    protected void onResume() {
        super.onResume();
        String current = LocaleHelper.currentLanguage(this);
        if (createdLang != null && !createdLang.equals(current)) {
            createdLang = current;
            PlaybackService service = PlaybackService.getInstance();
            if (service != null) {
                service.applyUiLanguage();
            }
            recreate();
        }
    }

    @Override
    protected void onDestroy() {
        BlurBackground.detach(this);
        super.onDestroy();
    }
}
