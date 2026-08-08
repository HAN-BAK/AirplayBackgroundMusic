package com.airmusic.btsink.discover;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.os.Bundle;
import android.widget.TextView;

import java.lang.reflect.Method;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setTextSize(24);
        tv.setPadding(48, 48, 48, 48);
        setContentView(tv);

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            tv.setText("no bluetooth adapter");
            return;
        }
        if (!adapter.isEnabled()) {
            tv.setText("bluetooth is off");
            return;
        }
        try {
            // SCAN_MODE_CONNECTABLE_DISCOVERABLE = 23, duration = 300s
            Method m = BluetoothAdapter.class.getMethod("setScanMode", int.class, int.class);
            Object res = m.invoke(adapter, 23, 300);
            tv.setText("可发现模式已开启（5分钟）\n\n设备名: " + adapter.getName()
                    + "\n\n请在手机上搜索并配对 " + adapter.getName()
                    + "\n\nsetScanMode -> " + res);
        } catch (Exception e) {
            tv.setText("error: " + e);
        }
    }
}
