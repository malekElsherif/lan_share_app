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
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** واجهة التطبيق: WebView بيعرض app.html (تصميم مخصص للتطبيق) فوق API السيرفر. */
public class MainActivity extends Activity {
    private static final int REQ_FILE = 11;
    static volatile boolean foreground = false;

    private WebView web;
    private ValueCallback<Uri[]> chooser;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile String mode = "";
    private String target = "";
    private int retries = 0;

    /** بيتنادى من app.html. */
    private class Bridge {
        @JavascriptInterface
        public String id() { return Identity.id(MainActivity.this); }

        @JavascriptInterface
        public String name() { return Identity.name(MainActivity.this); }

        @JavascriptInterface
        public void setName(String n) { Identity.setName(MainActivity.this, n); }

        @JavascriptInterface
        public String mode() { return mode; }

        @JavascriptInterface
        public void reconnect() { runOnUiThread(MainActivity.this::connect); }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0D1015"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "LanDropApp");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                if ("/__app".equals(r.getUrl().getPath())) {
                    try {
                        WebResourceResponse res = new WebResourceResponse("text/html", "UTF-8", getAssets().open("app.html"));
                        Map<String, String> h = new HashMap<>();
                        h.put("Cache-Control", "no-store");
                        res.setResponseHeaders(h);
                        return res;
                    } catch (IOException ignored) { }
                }
                return null;
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame() && retries++ < 6) ui.postDelayed(() -> v.loadUrl(target), 800);
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
        setContentView(web);
        connect();
    }

    /** أول جهاز يفتح بيبقى السيرفر، والباقي بيتصل بيه. */
    private void connect() {
        new Thread(() -> {
            final String[] found = Discovery.find(1500);
            runOnUiThread(() -> {
                if (found != null && !isOwnAddress(found[0])) {
                    String base = "http://" + found[0] + ":" + found[1];
                    mode = "client:" + found[0];
                    stopService(new Intent(this, ServerService.class));
                    startForegroundService(new Intent(this, WatchService.class).putExtra(WatchService.EXTRA_URL, base));
                    load(base + "/__app");
                } else {
                    mode = "server";
                    stopService(new Intent(this, WatchService.class));
                    if (!ServerService.running) startForegroundService(new Intent(this, ServerService.class));
                    ui.postDelayed(() -> load("http://127.0.0.1:" + ServerService.PORT + "/__app"), 700);
                }
            });
        }).start();
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

    // ---------- تحميل الملفات على Downloads/LanDrop ----------
    private void download(final String url, final String mime) {
        final String seg = Uri.parse(url).getLastPathSegment();
        final String name = seg == null ? "file" : seg;
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
                v.put(MediaStore.Downloads.MIME_TYPE, Saver.mimeOf(name));
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
    protected void onResume() {
        super.onResume();
        foreground = true;
    }

    @Override
    protected void onPause() {
        foreground = false;
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
