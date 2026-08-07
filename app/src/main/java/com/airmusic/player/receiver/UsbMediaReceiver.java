package com.airmusic.player.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.airmusic.player.service.PlaybackService;

/**
 * Triggers a library rescan when storage (e.g. a USB drive) is mounted or
 * removed.
 */
public class UsbMediaReceiver extends BroadcastReceiver {

    private static final String TAG = "UsbMediaReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        PlaybackService service = PlaybackService.getInstance();
        if (service != null) {
            Log.i(TAG, "Storage event " + intent.getAction() + ", rescanning");
            service.rescanLibrary();
        }
    }
}
