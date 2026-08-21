package com.airmusic.player;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.ViewCompat;

import com.airmusic.player.util.LocaleHelper;
import com.airmusic.player.service.PlaybackService;
import com.airmusic.player.util.BlurBackground;
import com.airmusic.player.view.BoxAspectFrameLayout;

/** Applies the user-selected UI language to every activity. */
public abstract class BaseActivity extends AppCompatActivity {

    private String createdLang;
    private boolean boxAspectWrapped;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Edge-to-edge: the content extends under the system bars and the
        // BoxAspectFrameLayout letterboxes the 16:9 content inside the safe
        // area, so it adapts consistently across vendors (MIUI / HyperOS
        // included) regardless of status-bar or gesture-pill insets.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        hideStatusBar();
        forceLightSystemBarIcons();
    }

    /** Hides the status bar (immersive); swiping it back shows it briefly. */
    private void hideStatusBar() {
        try {
            // Classic fullscreen flag: hides the status bar on every API
            // level (Android 6.0 devices like PA03 ignore the immersive
            // flags alone, leaving a bar that shrinks the 16:9 content).
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController controller =
                        getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars());
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            }
            // Also set the legacy flags: some vendors (MIUI) only honor
            // these, and it re-asserts the hide if the system restored the
            // bars after a permission dialog / focus change.
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } catch (Throwable ignored) {
        }
    }

    /** Keeps the status-bar / navigation-bar icons light on the dark UI. */
    private void forceLightSystemBarIcons() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController controller =
                        getWindow().getInsetsController();
                if (controller != null) {
                    controller.setSystemBarsAppearance(0,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        getWindow().getDecorView().getSystemUiVisibility()
                                & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                                & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onContentChanged() {
        super.onContentChanged();
        wrapContentToBoxAspect();
    }

    /**
     * Wraps the activity's root layout in a {@link BoxAspectFrameLayout} so
     * every screen keeps the reference TV-box 16:9 proportions regardless of
     * the device's own aspect ratio (phones / tablets letterbox it centered,
     * with the blurred background filling the sides).
     */
    private void wrapContentToBoxAspect() {
        if (boxAspectWrapped) return;
        ViewGroup content = findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0) return;
        View child = content.getChildAt(0);
        if (child instanceof BoxAspectFrameLayout) return;
        boxAspectWrapped = true;
        content.removeView(child);
        final BoxAspectFrameLayout box = new BoxAspectFrameLayout(this);
        box.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        box.addView(child, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        content.addView(box);
        // Feed window insets (status / navigation bars) into the aspect box
        // so the 16:9 content stays clear of them on non-box devices.
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            box.setInsets(insets.getSystemWindowInsetLeft(),
                    insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),
                    insets.getSystemWindowInsetBottom());
            return insets;
        });
    }

    @Override
    protected void attachBaseContext(@NonNull Context newBase) {
        createdLang = LocaleHelper.currentLanguage(newBase);
        super.attachBaseContext(LocaleHelper.attach(newBase));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // System windows (permission dialogs etc.) can restore the status
        // bar; re-apply the immersive hide every time we regain the screen.
        hideStatusBar();
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
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideStatusBar();
        }
    }

    @Override
    protected void onDestroy() {
        BlurBackground.detach(this);
        super.onDestroy();
    }
}
