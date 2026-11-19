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
import android.os.Build;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashSet;
import java.util.Set;

/**
 * لما التليفون يكون "عميل" (متصل بسيرفر جهاز تاني) بيفضل يراقب السيرفر في الخلفية
 * وبيطلّع إشعار لما حد يبعتله ملف أو رسالة.
 */
public class WatchService extends Service {
    static final String ACTION_STOP = "app.landrop.WATCH_STOP";
    static final String EXTRA_URL = "url";
    private static final String CHANNEL = "landrop_watch";

    private volatile String base = "";
    private volatile boolean stop = false;
    private Thread worker;

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String url = intent != null ? intent.getStringExtra(EXTRA_URL) : null;
        if (url == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        foreground(url);
        if (!url.equals(base)) {
            base = url;
            if (worker != null) worker.interrupt();
            stop = false;
            worker = new Thread(this::loop, "landrop-watch");
            worker.setDaemon(true);
            worker.start();
        }
        return START_STICKY;
    }

    private void foreground(String url) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "المراقبة", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, ChooserActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stopPi = PendingIntent.getService(this, 2,
                new Intent(this, WatchService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("LAN Drop متصل")
                .setContentText("بيراقب " + url.replace("http://", ""))
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                        "إيقاف", stopPi).build())
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(2, n);
    }

    private String get(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(6000);
        c.setRequestProperty("X-Device-Id", Identity.id(this));
        c.setRequestProperty("X-Device-Name", URLEncoder.encode(Identity.name(this), "UTF-8").replace("+", "%20"));
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private void loop() {
        Set<String> seen = new HashSet<>();
        boolean primed = false;
        long failingSince = 0;
        while (!stop) {
            try {
                JSONArray files = new JSONArray(get("/api/files"));
                JSONArray texts = new JSONArray(get("/api/texts"));
                failingSince = 0;
                for (int i = 0; i < files.length(); i++) {
                    JSONObject o = files.getJSONObject(i);
                    String key = "f:" + o.getString("name") + "|" + o.getLong("mtime");
                    if (seen.add(key) && primed && !o.optBoolean("mine")
                            && (o.optString("to").isEmpty() || o.optBoolean("forMe")) && !MainActivity.foreground)
                        Notifier.post(this, "📥 ملف جديد من " + o.optString("from", "جهاز"), o.getString("name"));
                }
                for (int i = 0; i < texts.length(); i++) {
                    JSONObject o = texts.getJSONObject(i);
                    String key = "t:" + o.getString("id");
                    if (seen.add(key) && primed && !o.optBoolean("mine")
                            && (o.optString("to").isEmpty() || o.optBoolean("forMe")) && !MainActivity.foreground) {
                        String t = o.optString("text");
                        Notifier.post(this, "💬 رسالة من " + o.optString("from", "جهاز"), t.length() > 120 ? t.substring(0, 120) : t);
                    }
                }
                primed = true;
            } catch (Exception e) {
                if (failingSince == 0) failingSince = System.currentTimeMillis();
                if (System.currentTimeMillis() - failingSince > 5 * 60 * 1000L) {   // السيرفر اختفى 5 دقايق
                    stopSelf();
                    return;
                }
            }
            try {
                Thread.sleep(4000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    @Override
    public void onDestroy() {
        stop = true;
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }
}
