package app.landrop;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.UUID;

/** هوية الجهاز (ID ثابت + اسم قابل للتعديل). */
final class Identity {
    private Identity() { }

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("landrop", Context.MODE_PRIVATE);
    }

    static String id(Context c) {
        String v = p(c).getString("id", null);
        if (v == null) {
            v = UUID.randomUUID().toString();
            p(c).edit().putString("id", v).apply();
        }
        return v;
    }

    static String name(Context c) {
        String n = p(c).getString("name", null);
        return n != null ? n : Build.MODEL;
    }

    static void setName(Context c, String n) {
        if (n != null && !n.trim().isEmpty()) p(c).edit().putString("name", n.trim()).apply();
    }
}
