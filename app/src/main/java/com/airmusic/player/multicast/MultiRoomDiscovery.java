package com.airmusic.player.multicast;

import android.util.Log;

import nz.co.iswe.android.airplay.AirPlayServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

/**
 * mDNS registration + discovery for multi-room devices using JmDNS. The
 * service instance name is the device's AirPlay name, so the receiver list
 * shows the same names users already know.
 */
public class MultiRoomDiscovery {

    public interface Listener {
        void onDevicesChanged();
    }

    public static class DeviceInfo {
        public final String name;
        public final String[] addresses;
        public final int port;

        DeviceInfo(String name, String[] addresses, int port) {
            this.name = name;
            this.addresses = addresses;
            this.port = port;
        }
    }

    private static final String TAG = "MultiRoomDiscovery";
    private static final java.util.concurrent.atomic.AtomicBoolean REGISTERED_ONCE =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private final Map<String, DeviceInfo> devices = new ConcurrentHashMap<>();
    private final List<Listener> listeners = new ArrayList<>();
    private volatile JmDNS jmdns;
    private volatile boolean registrationAttempted;
    private volatile List<InetAddress> localAddresses;

    private boolean isLocalAddress(String host) {
        try {
            if (localAddresses == null) {
                List<InetAddress> addrs = new ArrayList<>();
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    addrs.addAll(Collections.list(ni.getInetAddresses()));
                }
                localAddresses = addrs;
            }
            InetAddress addr = InetAddress.getByName(host);
            for (InetAddress l : localAddresses) {
                if (l.equals(addr)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public synchronized void addListener(Listener l) {
        listeners.add(l);
    }

    public synchronized void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyChanged() {
        List<Listener> copy;
        synchronized (this) {
            copy = new ArrayList<>(listeners);
        }
        for (Listener l : copy) {
            try {
                l.onDevicesChanged();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Registers this device's multi-room service (async). */
    public void register(final String deviceName) {
        if (!REGISTERED_ONCE.compareAndSet(false, true)) {
            Log.i(TAG, "already registered once this process; skipping");
            return;
        }
        Thread t = new Thread(() -> {
            // The AirPlay server owns the JmDNS instances (one per interface);
            // register on them so the service is announced on every usable
            // interface without port-5353 conflicts. The instances appear a
            // moment after the AirPlay engine starts, so retry briefly.
            registrationAttempted = true;
            for (int attempt = 0; attempt < 20; attempt++) {
                try {
                    int n = AirPlayServer.getIstance().registerAuxiliaryService(
                            MultiRoomProtocol.SERVICE_TYPE, deviceName, MultiRoomProtocol.PORT,
                            java.util.Collections.singletonMap("name", deviceName));
                    Log.i(TAG, "register attempt " + attempt + " -> " + n + " interface(s)");
                    if (n > 0) {
                        Log.i(TAG, "registered multi-room service '" + deviceName + "' on " + n + " interface(s)");
                        JmDNS j = AirPlayServer.getIstance().getPrimaryJmDNS();
                        if (j != null && jmdns == null) {
                            jmdns = j;
                            j.addServiceListener(MultiRoomProtocol.SERVICE_TYPE, serviceListener);
                        }
                        return;
                    }
                } catch (Throwable e) {
                    Log.w(TAG, "registration failed", e);
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
            Log.w(TAG, "multi-room registration: no JmDNS available yet");
        }, "mr-register");
        t.setDaemon(true);
        t.start();
    }

    private final ServiceListener serviceListener = new ServiceListener() {
        @Override
        public void serviceAdded(ServiceEvent event) {
            ServiceInfo info = event.getInfo();
            if (info != null) {
                JmDNS j = jmdns;
                if (j != null) j.requestServiceInfo(MultiRoomProtocol.SERVICE_TYPE, info.getName());
            }
        }

        @Override
        public void serviceRemoved(ServiceEvent event) {
            devices.remove(event.getName());
            notifyChanged();
        }

        @Override
        public void serviceResolved(ServiceEvent event) {
            addDevice(event.getInfo());
        }
    };

    private void addDevice(ServiceInfo info) {
        if (info == null) return;
        String[] addresses = info.getHostAddresses();
        if (addresses == null || addresses.length == 0) return;
        // Order addresses so IPv4 comes first, link-local IPv6 comes last
        // (link-local needs a scope id and usually fails to connect).
        String[] ordered = orderAddresses(addresses);
        String host = ordered[0];
        // Never list this device itself.
        if (isLocalAddress(host)) return;
        int port = info.getPort() > 0 ? info.getPort() : MultiRoomProtocol.PORT;
        // JmDNS renames duplicate registrations to "name (1)", "name (2)", ...
        // (stale entries from earlier versions may still be cached), so
        // normalize the name for deduplication.
        String name = normalizeName(info.getName());
        DeviceInfo existing = devices.get(name);
        if (existing == null) {
            devices.put(name, new DeviceInfo(name, ordered, port));
        } else {
            devices.put(name, new DeviceInfo(name, mergeAddresses(existing.addresses, ordered), port));
        }
        notifyChanged();
    }

    /** IPv4 first, then non-link-local IPv6, then link-local IPv6. */
    private static String[] orderAddresses(String[] addresses) {
        List<String> ipv4 = new ArrayList<>();
        List<String> global6 = new ArrayList<>();
        List<String> linkLocal6 = new ArrayList<>();
        for (String a : addresses) {
            if (a.contains(".")) {
                ipv4.add(a);
            } else if (a.startsWith("fe8")) {
                linkLocal6.add(a);
            } else {
                global6.add(a);
            }
        }
        List<String> out = new ArrayList<>();
        out.addAll(ipv4);
        out.addAll(global6);
        out.addAll(linkLocal6);
        return out.toArray(new String[0]);
    }

    private static String[] mergeAddresses(String[] a, String[] b) {
        List<String> out = new ArrayList<>();
        for (String s : a) {
            if (!out.contains(s)) out.add(s);
        }
        for (String s : b) {
            if (!out.contains(s)) out.add(s);
        }
        return out.toArray(new String[0]);
    }

    /** Strips a trailing " (N)" suffix JmDNS adds to duplicate instances. */
    private static String normalizeName(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.*?)\\s+\\(\\d+\\)$").matcher(t);
        if (m.matches()) return m.group(1);
        return t;
    }

    /** Re-scans the network (used when the user opens the device dialog). */
    public void rescan() {
        Thread t = new Thread(() -> {
            try {
                devices.clear();
                List<JmDNS> instances = AirPlayServer.getIstance().getJmDNSInstances();
                for (JmDNS j : instances) {
                    ServiceInfo[] infos = j.list(MultiRoomProtocol.SERVICE_TYPE, 1500);
                    for (ServiceInfo info : infos) {
                        Log.i(TAG, "raw service seen: '" + info.getName() + "' @" +
                                (info.getHostAddresses() != null && info.getHostAddresses().length > 0
                                        ? info.getHostAddresses()[0] : "?"));
                        addDevice(info);
                    }
                }
                notifyChanged();
            } catch (Exception e) {
                Log.w(TAG, "rescan failed", e);
            }
        }, "mr-rescan");
        t.setDaemon(true);
        t.start();
    }

    /** Devices seen on the network (excluding this device if it is registered). */
    public List<DeviceInfo> getDevices() {
        List<DeviceInfo> out = new ArrayList<>(devices.values());
        return out;
    }

    public void stop() {
        jmdns = null;
    }
}
