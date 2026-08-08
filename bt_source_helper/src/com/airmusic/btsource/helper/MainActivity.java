package com.airmusic.btsource.helper;

import android.app.Activity;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.Set;

public class MainActivity extends Activity {
    private static final String TAG = "BTSourceHelper";
    private static final String TARGET_NAME = "Han's Xtreme 3";

    private BluetoothAdapter mAdapter;
    private TextView mStatus;
    private BluetoothA2dp mA2dp;
    private BluetoothDevice mTarget;
    private boolean mPairing;
    private int mDiscoveryTries;
    private int mRetryCount;
    private final Handler mHandler = new Handler();
    private final Runnable mWatchdog = new Runnable() {
        @Override
        public void run() {
            if (mTarget == null && !mPairing) {
                log("watchdog: discovery idle, restarting");
                try {
                    if (mAdapter != null && mAdapter.isEnabled()) {
                        mAdapter.cancelDiscovery();
                        mAdapter.startDiscovery();
                    }
                } catch (Exception e) {
                    log("watchdog error: " + e);
                }
            }
            mHandler.postDelayed(this, 30000);
        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            log("event: " + action);
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (dev == null) return;
                String name = dev.getName();
                log("found: " + (name == null ? "(no name)" : name) + " " + dev.getAddress());
                if (name != null && name.contains(TARGET_NAME)) {
                    mTarget = dev;
                    log("==> target matched: " + dev.getAddress());
                    mAdapter.cancelDiscovery();
                    startPairing(dev);
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                log("discovery finished");
                if (mTarget == null && !mPairing) {
                    mDiscoveryTries++;
                    log("not found yet, restarting discovery (try " + mDiscoveryTries + ")");
                    mAdapter.startDiscovery();
                }
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
                if (dev != null) {
                    log("bond state " + dev.getAddress() + " -> " + state);
                }
                if (mTarget != null && dev != null && mTarget.getAddress().equals(dev.getAddress())
                        && state == BluetoothDevice.BOND_BONDED) {
                    connectA2dp(dev);
                }
            } else if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                if (dev != null) {
                    log("a2dp state " + dev.getAddress() + " -> " + state);
                }
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    mRetryCount = 0;
                    String n = dev == null ? "" : (dev.getName() == null ? dev.getAddress() : dev.getName());
                    log("===== A2DP CONNECTED to " + n + " =====");
                } else if (state == BluetoothProfile.STATE_DISCONNECTED && mTarget != null) {
                    mRetryCount++;
                    log("not connected, retrying in 10s (try " + mRetryCount + ")...");
                    mStatus.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (mTarget != null) {
                                connectA2dp(mTarget);
                            }
                        }
                    }, 10000);
                }
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                log("adapter state: " + state);
                if (state == BluetoothAdapter.STATE_ON) {
                    startFlow();
                } else if (state == BluetoothAdapter.STATE_OFF) {
                    log("bluetooth was turned off, re-enabling...");
                    mAdapter.enable();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mStatus = new TextView(this);
        mStatus.setTextSize(20);
        mStatus.setPadding(48, 48, 48, 48);
        setContentView(mStatus);

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(mReceiver, filter);

        mAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mAdapter == null) {
            log("ERROR: no bluetooth adapter");
            return;
        }
        if (!mAdapter.isEnabled()) {
            log("bluetooth off, enabling...");
            mAdapter.enable();
        } else {
            startFlow();
        }
        mHandler.postDelayed(mWatchdog, 30000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mAdapter != null && mAdapter.isEnabled() && mTarget == null && !mPairing) {
            log("resumed, ensuring discovery...");
            try {
                mAdapter.startDiscovery();
            } catch (Exception e) {
                log("resume discovery error: " + e);
            }
        }
    }

    private void startFlow() {
        mPairing = false;
        Set<BluetoothDevice> bonded = mAdapter.getBondedDevices();
        if (bonded != null) {
            for (BluetoothDevice dev : bonded) {
                String name = dev.getName();
                if (name != null && name.contains(TARGET_NAME)) {
                    mTarget = dev;
                    log("target already bonded: " + dev.getAddress() + ", connecting...");
                    connectA2dp(dev);
                    return;
                }
            }
        }
        log("scanning for " + TARGET_NAME + "...");
        mAdapter.startDiscovery();
    }

    private void startPairing(BluetoothDevice dev) {
        mPairing = true;
        log("pairing with " + dev.getAddress());
        if (dev.getBondState() == BluetoothDevice.BOND_BONDED) {
            connectA2dp(dev);
        } else {
            boolean ok = dev.createBond();
            log("createBond() -> " + ok);
            if (!ok) {
                log("bond request failed, retrying in 3s...");
                mStatus.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (mTarget != null) mTarget.createBond();
                    }
                }, 3000);
            }
        }
    }

    private void connectA2dp(BluetoothDevice dev) {
        log("getting A2DP profile proxy...");
        boolean ok = mAdapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.A2DP && proxy instanceof BluetoothA2dp) {
                    mA2dp = (BluetoothA2dp) proxy;
                    log("A2DP proxy ready, calling connect()");
                    try {
                        Method m = BluetoothA2dp.class.getMethod("connect", BluetoothDevice.class);
                        Boolean res = (Boolean) m.invoke(mA2dp, dev);
                        log("connect() -> " + res);
                    } catch (Exception e) {
                        log("connect() error: " + e);
                    }
                }
            }

            @Override
            public void onServiceDisconnected(int profile) {
                mA2dp = null;
            }
        }, BluetoothProfile.A2DP);
        log("getProfileProxy() -> " + ok);
    }

    private void log(final String msg) {
        Log.i(TAG, msg);
        runOnUiThread(new Runnable() {
            @Override public void run() {
                mStatus.append(msg + "\n");
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacks(mWatchdog);
        try {
            unregisterReceiver(mReceiver);
        } catch (Exception ignored) {
        }
        if (mA2dp != null) {
            mAdapter.closeProfileProxy(BluetoothProfile.A2DP, mA2dp);
            mA2dp = null;
        }
    }
}
