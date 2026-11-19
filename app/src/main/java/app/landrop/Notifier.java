package app.landrop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.concurrent.atomic.AtomicInteger;

/** إشعارات الملفات والرسائل الجديدة. */
final class Notifier {
    private static final String CHANNEL = "landrop_events";
    private static final AtomicInteger ID = new AtomicInteger(100);

    private Notifier() { }

    static void post(Context c, String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "ملفات ورسائل جديدة",
                    NotificationManager.IMPORTANCE_HIGH));
            PendingIntent pi = PendingIntent.getActivity(c, 0, new Intent(c, ChooserActivity.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(c, CHANNEL)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(ID.getAndIncrement(), n);
        } catch (Exception ignored) { }
    }
}
