package com.airmusic.player.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.airmusic.player.service.PlaybackService;

/**
 * Starts the background service after device boot so AirPlay receiving and
 * optional local autoplay run without opening the app.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            Log.i(TAG, "Boot completed, starting playback service");
            PlaybackService.start(context);
        }
    }
}
