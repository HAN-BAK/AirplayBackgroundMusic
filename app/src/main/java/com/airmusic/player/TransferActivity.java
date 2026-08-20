package com.airmusic.player;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.airmusic.player.library.AudioExt;
import com.airmusic.player.library.MusicLibrary;
import com.airmusic.player.service.PlaybackService;
import com.airmusic.player.transfer.MusicTransferServer;
import com.airmusic.player.transfer.QrEncoder;
import com.airmusic.player.util.BlurBackground;
import com.airmusic.player.util.Prefs;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

public class TransferActivity extends BaseActivity {

    private MusicTransferServer server;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshLibrary = () -> {
        // Merge refreshes when several files arrive back-to-back.
        MusicLibrary.getInstance().clearCache();
        PlaybackService service = PlaybackService.getInstance();
        if (service != null) {
            service.rescanLibrary();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transfer);
        BlurBackground.apply(this, R.color.background);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        TextView txtAddress = findViewById(R.id.txt_address);
        TextView txtStatus = findViewById(R.id.txt_status);
        TextView txtFormats = findViewById(R.id.txt_formats);
        ImageView imgQr = findViewById(R.id.img_qr);
        com.google.android.material.button.MaterialButton btnWifi =
                findViewById(R.id.btn_connect_wifi);

        txtFormats.setText(getString(R.string.transfer_supported, AudioExt.supportedList()));

        String folder = new Prefs(this).getMusicFolderPath();
        server = new MusicTransferServer(folder, readIcon(), new Prefs(this).getLanguage(), (name, success, message) ->
                runOnUiThread(() -> {
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                    if (success) {
                        handler.removeCallbacks(refreshLibrary);
                        handler.postDelayed(refreshLibrary, 1000);
                    }
                }));

        int port = server.start();
        String ip = getLocalIpv4();
        if (port <= 0 || ip == null || ip.isEmpty()) {
            // No usable LAN address (Wi-Fi / network not connected): offer a
            // shortcut to the system Wi-Fi settings instead of failing.
            txtStatus.setText(R.string.transfer_no_network);
            txtAddress.setVisibility(android.view.View.GONE);
            imgQr.setVisibility(android.view.View.GONE);
            btnWifi.setVisibility(android.view.View.VISIBLE);
            btnWifi.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                } catch (Exception e) {
                    Toast.makeText(this, R.string.home_settings_unavailable,
                            Toast.LENGTH_SHORT).show();
                }
            });
            return;
        }
        String url = "http://" + ip + ":" + port + "/";
        txtAddress.setText(ip + " : " + port);
        txtStatus.setText(R.string.transfer_server_started);

        try {
            boolean[][] matrix = QrEncoder.encode(url);
            int size = matrix.length;
            int scale = Math.max(1, 220 / size);
            Bitmap bmp = Bitmap.createBitmap(size * scale, size * scale, Bitmap.Config.ARGB_8888);
            for (int y = 0; y < size * scale; y++) {
                for (int x = 0; x < size * scale; x++) {
                    bmp.setPixel(x, y, matrix[y / scale][x / scale]
                            ? Color.BLACK : Color.WHITE);
                }
            }
            imgQr.setImageBitmap(bmp);
        } catch (Exception e) {
            txtStatus.setText(getString(R.string.transfer_qr_failed, e.getMessage()));
        }
    }

    @Override
    protected void onDestroy() {
        if (server != null) {
            server.stop();
            server = null;
        }
        handler.removeCallbacks(refreshLibrary);
        super.onDestroy();
    }

    private byte[] readIcon() {
        try (InputStream is = getAssets().open("transfer_icon.png")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private String getLocalIpv4() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String host = addr.getHostAddress();
                        if (host != null && !host.startsWith("127.")) {
                            return host;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
