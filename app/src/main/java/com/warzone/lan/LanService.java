package com.warzone.lan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/** يشغّل سيرفر الغرف المحلية داخل التطبيق ويُبقيه حياً حتى لو خرج اللاعب من الشاشة. */
public class LanService extends Service {
    private static final String CHANNEL = "wz_lan";
    private static final int[] PORTS = {8080, 8081, 8082, 8083, 8090};

    private static LanServer server;
    private static int port = 0;
    private PowerManager.WakeLock wake;

    /** يشغّل السيرفر إن لم يكن شغّالاً، ويرجع رقم المنفذ (أو 0 عند الفشل). */
    public static synchronized int ensure(Context ctx) {
        if (server != null) return port;
        final AssetManager am = ctx.getApplicationContext().getAssets();
        for (int p : PORTS) {
            LanServer s = new LanServer(p, new LanServer.Assets() {
                @Override
                public byte[] read(String path) throws IOException {
                    InputStream in = null;
                    try {
                        in = am.open("www/" + path);
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        byte[] buf = new byte[16384];
                        int r;
                        while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
                        return bo.toByteArray();
                    } catch (FileNotFoundException e) {
                        return null;
                    } finally {
                        if (in != null) try { in.close(); } catch (IOException ignored) { }
                    }
                }
            }, new LanServer.Ips() {
                @Override
                public List<String> get() { return localIps(); }
            });
            try {
                s.start();
                server = s;
                port = p;
                return p;
            } catch (IOException e) {
                // المنفذ مشغول، نجرّب التالي
            }
        }
        return 0;
    }

    public static synchronized void shutdown() {
        if (server != null) {
            server.stop();
            server = null;
            port = 0;
        }
    }

    public static int currentPort() { return port; }

    private static int rank(String a) {
        if (a.startsWith("192.168.")) return 0;
        if (a.startsWith("10.")) return 1;
        if (a.startsWith("172.17.")) return 4;
        if (a.matches("^172\\.(1[6-9]|2\\d|3[01])\\..*")) return 2;
        return 3;
    }

    /** عناوين الـIP المحلية (واي فاي / هوت سبوت) لعرضها للمضيف. */
    public static List<String> localIps() {
        List<String> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            if (en == null) return out;
            for (NetworkInterface ni : Collections.list(en)) {
                String n = ni.getName() == null ? "" : ni.getName();
                if (!ni.isUp() || ni.isLoopback()) continue;
                if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("dummy") || n.startsWith("tun")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                        String h = a.getHostAddress();
                        if (h != null && !out.contains(h)) out.add(h);
                    }
                }
            }
        } catch (Exception ignored) { }
        Collections.sort(out, (x, y) -> rank(x) - rank(y));
        return out;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensure(this);
        startForegroundNow();
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (wake == null && pm != null) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wz:lan");
                wake.acquire();
            }
        } catch (Exception ignored) { }
        return START_STICKY;
    }

    private void startForegroundNow() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "War Zone LAN", NotificationManager.IMPORTANCE_LOW));
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int pf = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, pf);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setContentTitle("War Zone — السيرفر المحلي شغّال")
                .setContentText("الغرف المحلية تعمل بدون إنترنت")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pi)
                .setOngoing(true);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    @Override
    public void onDestroy() {
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) { }
        wake = null;
        shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
