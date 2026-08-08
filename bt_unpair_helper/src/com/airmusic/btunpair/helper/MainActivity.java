package com.airmusic.btunpair.helper;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.os.Bundle;
import android.widget.TextView;

import java.util.Set;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setTextSize(22);
        tv.setPadding(48, 48, 48, 48);
        setContentView(tv);

        String[] targets = {"BC:38:98:65:D2:67", "20:A5:CB:B6:5A:3B"};
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            tv.setText("no adapter");
            return;
        }
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded == null) {
            tv.setText("no bonded devices");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (BluetoothDevice d : bonded) {
            boolean match = false;
            for (String t : targets) {
                if (d.getAddress().equalsIgnoreCase(t)) {
                    match = true;
                    break;
                }
            }
            if (match) {
                try {
                    java.lang.reflect.Method m = BluetoothDevice.class.getMethod("removeBond");
                    boolean ok = (Boolean) m.invoke(d);
                    sb.append("removed ").append(d.getAddress())
                      .append(" (").append(d.getName()).append(") -> ").append(ok).append("\n");
                } catch (Exception e) {
                    sb.append("error ").append(d.getAddress()).append(": ").append(e).append("\n");
                }
            }
        }
        sb.append("done");
        tv.setText(sb.toString());
    }
}
