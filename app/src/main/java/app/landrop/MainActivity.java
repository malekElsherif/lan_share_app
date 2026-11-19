package app.landrop;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Collections;

public class MainActivity extends Activity {
    private static final int REQ_FILE = 11;

    private WebView web;
    private TextView bar;
    private ValueCallback<Uri[]> chooser;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String target = "";
    private int retries = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        bar = new TextView(this);
        int p = (int) (10 * getResources().getDisplayMetrics().density);
        bar.setPadding(p, p, p, p);
        bar.setTextColor(Color.WHITE);
        bar.setBackgroundColor(0xFF2F7BFF);
        bar.setTextSize(13);
        bar.setGravity(Gravity.CENTER);
        bar.setOnClickListener(v -> connect());

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame() && retries++ < 6) {
                    ui.postDelayed(() -> v.loadUrl(target), 800);
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (chooser != null) chooser.onReceiveValue(null);
                chooser = cb;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    chooser = null;
                    return false;
                }
                return true;
            }
        });
        web.setDownloadListener((url, ua, cd, mime, len) -> download(url, mime));

        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        connect();
    }

    /** أول جهاز يفتح بيبقى السيرفر، والباقي بيتصل بيه. */
    private void connect() {
        bar.setText("بدوّر على LAN Drop على الشبكة...");
        new Thread(() -> {
            final String[] found = Discovery.find(1500);
            runOnUiThread(() -> {
                if (found != null && !isOwnAddress(found[0])) {
                    load("http://" + found[0] + ":" + found[1]);
                    bar.setText("متصل بسيرفر على " + found[0] + " — اضغط هنا لإعادة البحث");
                } else {
                    becomeServer();
                }
            });
        }).start();
    }

    private void becomeServer() {
        if (!ServerService.running) {
            startForegroundService(new Intent(this, ServerService.class));
        }
        bar.setText("التليفون هو السيرفر: http://" + LanServer.lanIp() + ":" + ServerService.PORT
                + "\nافتح الرابط ده من أي جهاز على نفس الشبكة");
        ui.postDelayed(() -> load("http://127.0.0.1:" + ServerService.PORT), 700);
    }

    private void load(String url) {
        target = url;
        retries = 0;
        web.loadUrl(url);
    }

    private boolean isOwnAddress(String ip) {
        if (ip.equals("127.0.0.1")) return true;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces()))
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a.getHostAddress().equals(ip)) return true;
        } catch (Exception ignored) { }
        return false;
    }

    // ---------- تحميل الملفات على Downloads ----------
    private void download(final String url, final String mime) {
        final String name = Uri.parse(url).getLastPathSegment() == null ? "file" : Uri.parse(url).getLastPathSegment();
        if (Build.VERSION.SDK_INT < 29) {
            try {
                DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                r.setTitle(name);
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
                toast("بينزّل: " + name);
            } catch (Exception e) {
                toast("فشل التحميل");
            }
            return;
        }
        toast("بينزّل: " + name);
        new Thread(() -> {
            Uri item = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                InputStream in = c.getInputStream();
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LanDrop");
                v.put(MediaStore.Downloads.IS_PENDING, 1);
                item = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                OutputStream out = getContentResolver().openOutputStream(item);
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close();
                in.close();
                ContentValues done = new ContentValues();
                done.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(item, done, null, null);
                toast("اتحفظ في Downloads/LanDrop: " + name);
            } catch (Exception e) {
                if (item != null) {
                    try { getContentResolver().delete(item, null, null); } catch (Exception ignored) { }
                }
                toast("فشل التحميل");
            }
        }).start();
    }

    private void toast(final String m) {
        runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE && chooser != null) {
            chooser.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            chooser = null;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else finish();
    }
}
