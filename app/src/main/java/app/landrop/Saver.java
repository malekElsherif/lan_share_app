package app.landrop;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

/** بيحفظ الملفات المستقبلة في Downloads/LanDrop. */
final class Saver {
    private Saver() { }

    static final class Out {
        final OutputStream os;
        private final Context c;
        private final Uri uri;
        private final File file;

        Out(OutputStream os, Context c, Uri uri, File file) {
            this.os = os;
            this.c = c;
            this.uri = uri;
            this.file = file;
        }

        void finish(boolean ok) {
            try { os.close(); } catch (IOException ignored) { }
            if (uri != null) {
                if (ok) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.IS_PENDING, 0);
                    c.getContentResolver().update(uri, v, null, null);
                } else {
                    c.getContentResolver().delete(uri, null, null);
                }
            } else if (!ok && file != null) {
                file.delete();
            }
        }
    }

    static String clean(String n) {
        if (n == null) return "file";
        n = n.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\x00-\\x1f<>:\"|?*]", "_").trim();
        return n.isEmpty() ? "file" : n;
    }

    static String mimeOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (m != null) return m;
        }
        return "application/octet-stream";
    }

    static Out open(Context c, String name) throws IOException {
        name = clean(name);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, mimeOf(name));
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LanDrop");
            v.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("insert failed");
            OutputStream os = c.getContentResolver().openOutputStream(uri);
            if (os == null) throw new IOException("open failed");
            return new Out(os, c, uri, null);
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LanDrop");
        dir.mkdirs();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        File f = new File(dir, name);
        for (int i = 1; f.exists(); i++) f = new File(dir, base + " (" + i + ")" + ext);
        return new Out(new FileOutputStream(f), c, null, f);
    }

    static String nameOf(Context c, Uri u) {
        try (Cursor q = c.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (q != null && q.moveToFirst() && !q.isNull(0)) return q.getString(0);
        } catch (Exception ignored) { }
        String s = u.getLastPathSegment();
        return s == null ? "file" : s;
    }

    static long sizeOf(Context c, Uri u) {
        try (Cursor q = c.getContentResolver().query(u, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (q != null && q.moveToFirst() && !q.isNull(0)) return q.getLong(0);
        } catch (Exception ignored) { }
        return -1;
    }
}
