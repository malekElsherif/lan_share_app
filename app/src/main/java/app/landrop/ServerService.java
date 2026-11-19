package app.landrop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;

/** بيشغّل السيرفر في الخلفية (Foreground Service) عشان يفضل شغال والشاشة مقفولة. */
public class ServerService extends Service {
    public static final int PORT = 8765;
    public static final String ACTION_STOP = "app.landrop.STOP";
    private static final String CHANNEL = "landrop";

    public static volatile boolean running = false;

    private LanServer server;
    private Discovery.Responder responder;
    private PowerManager.WakeLock wake;
    private WifiManager.MulticastLock multicast;
    private WifiManager.WifiLock wifi;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        goForeground();
        if (!running) startServer();
        return START_STICKY;
    }

    private void goForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "LAN Drop", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, ServerService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("LAN Drop شغّال")
                .setContentText("http://" + LanServer.lanIp() + ":" + PORT)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                        "إيقاف", stopPi).build())
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    private void startServer() {
        try {
            File root = getExternalFilesDir(null);
            if (root == null) root = getFilesDir();
            root = new File(root, "LanDrop");
            server = new LanServer(root, PORT, readAsset("index.html"));
            server.setHostIdentity(Identity.id(this), Identity.name(this));
            server.setEvents(new LanServer.Events() {
                @Override
                public void onFile(String name, String from, String to) {
                    if (!MainActivity.foreground) Notifier.post(ServerService.this, "📥 ملف جديد من " + from, name);
                }

                @Override
                public void onText(String text, String from, String to) {
                    if (!MainActivity.foreground)
                        Notifier.post(ServerService.this, "💬 رسالة من " + from, text.length() > 120 ? text.substring(0, 120) : text);
                }
            });
            server.start();
            responder = new Discovery.Responder();
            try {
                responder.start(PORT);
            } catch (Exception ignored) { /* الاكتشاف اختياري */ }
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "landrop:server");
            wake.acquire();
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            multicast = wm.createMulticastLock("landrop");
            multicast.acquire();
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "landrop");
            wifi.acquire();
            running = true;
        } catch (Exception e) {
            running = false;
            stopSelf();
        }
    }

    private byte[] readAsset(String name) throws Exception {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        if (server != null) server.stop();
        if (responder != null) responder.stop();
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) { }
        try { if (multicast != null && multicast.isHeld()) multicast.release(); } catch (Exception ignored) { }
        try { if (wifi != null && wifi.isHeld()) wifi.release(); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
