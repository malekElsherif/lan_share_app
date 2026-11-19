package app.landrop;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** شاشة النقل المباشر (Nearby أو بلوتوث): بدون شبكة. */
public class P2PActivity extends Activity implements Link.Listener {
    static final String EXTRA_MODE = "mode";   // "nearby" | "bt"
    private static final int REQ_PERM = 21, REQ_PICK = 22;

    private boolean nearby;
    private Link link;
    private TextView status, progress, log;
    private LinearLayout list;
    private Button sendBtn;
    private final Map<String, Button> rows = new HashMap<>();
    private boolean connected = false;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        nearby = "nearby".equals(getIntent().getStringExtra(EXTRA_MODE));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText(nearby ? "📡 Nearby (من غير شبكة)" : "🔵 بلوتوث");
        title.setTextSize(20);
        root.addView(title);

        status = new TextView(this);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        if (!nearby) {
            Button vis = button("خلّي جهازي ظاهر (5 دقايق)");
            vis.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300));
                } catch (Exception e) {
                    status.setText("مقدرتش أخلّي الجهاز ظاهر. اعملها من إعدادات البلوتوث");
                }
            });
            root.addView(vis);
        }
        Button rescan = button("ابحث تاني عن أجهزة");
        rescan.setOnClickListener(v -> { if (link != null) link.rescan(); });
        root.addView(rescan);

        TextView devTitle = new TextView(this);
        devTitle.setText("الأجهزة القريبة (اضغط على الجهاز عشان تتصل بيه):");
        devTitle.setPadding(0, dp(12), 0, dp(4));
        root.addView(devTitle);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        sendBtn = button("📤 ابعت ملفات");
        sendBtn.setEnabled(false);
        sendBtn.setOnClickListener(v -> pick());
        root.addView(sendBtn);

        progress = new TextView(this);
        progress.setGravity(Gravity.CENTER);
        progress.setPadding(0, dp(8), 0, dp(4));
        root.addView(progress);

        log = new TextView(this);
        log.setTextColor(Color.GRAY);
        root.addView(log);

        setContentView(root);
        ensurePermissionsThenStart();
    }

    // ---------- الأذونات ----------
    private String[] needed() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            p.add("android.permission.BLUETOOTH_SCAN");
            p.add("android.permission.BLUETOOTH_ADVERTISE");
            p.add("android.permission.BLUETOOTH_CONNECT");
        }
        if (Build.VERSION.SDK_INT <= 31) p.add("android.permission.ACCESS_FINE_LOCATION");
        if (Build.VERSION.SDK_INT >= 33 && nearby) p.add("android.permission.NEARBY_WIFI_DEVICES");
        if (Build.VERSION.SDK_INT <= 28) p.add("android.permission.WRITE_EXTERNAL_STORAGE");
        return p.toArray(new String[0]);
    }

    private void ensurePermissionsThenStart() {
        List<String> missing = new ArrayList<>();
        for (String p : needed())
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        if (missing.isEmpty()) startLink();
        else requestPermissions(missing.toArray(new String[0]), REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_PERM) return;
        for (int r : res) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                status.setText("لازم توافق على كل الأذونات عشان يشتغل. اقفل الشاشة وافتحها تاني.");
                return;
            }
        }
        startLink();
    }

    private void startLink() {
        link = nearby ? new NearbyLink(this, this) : new BluetoothLink(this, this);
        try {
            link.start();
        } catch (Throwable t) {
            status.setText("مشكلة في البدء: " + t.getMessage()
                    + (nearby ? "\nالوضع ده محتاج Google Play Services." : ""));
        }
    }

    // ---------- اختيار وإرسال الملفات ----------
    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || link == null) return;
        List<Uri> uris = new ArrayList<>();
        ClipData cd = data.getClipData();
        if (cd != null) for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        for (Uri u : uris) {
            String name = Saver.nameOf(this, u);
            if (!link.send(u, name, Saver.sizeOf(this, u)))
                Toast.makeText(this, "مفيش جهاز متصل", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- Link.Listener (ممكن يتنادى من أي Thread) ----------
    @Override
    public void onDevice(final String id, final String name) {
        runOnUiThread(() -> {
            Button b = rows.get(id);
            if (b == null) {
                b = button("");
                final String fid = id;
                b.setOnClickListener(v -> {
                    status.setText("بيتصل...");
                    link.connect(fid);
                });
                rows.put(id, b);
                list.addView(b);
            }
            b.setText(name);
        });
    }

    @Override
    public void onDeviceLost(final String id) {
        runOnUiThread(() -> {
            Button b = rows.remove(id);
            if (b != null) list.removeView(b);
        });
    }

    @Override
    public void onStatus(final String t) {
        runOnUiThread(() -> status.setText(t));
    }

    @Override
    public void onConnected(final String name) {
        runOnUiThread(() -> {
            connected = true;
            status.setText("✅ متصل بـ " + name);
            sendBtn.setEnabled(true);
        });
    }

    @Override
    public void onDisconnected(final String name) {
        runOnUiThread(() -> {
            connected = false;
            status.setText("اتفصل الاتصال" + (name != null ? " بـ " + name : ""));
            sendBtn.setEnabled(false);
        });
    }

    @Override
    public void onProgress(final String label, final int pct) {
        runOnUiThread(() -> progress.setText(label + "  " + pct + "%"));
    }

    @Override
    public void onReceived(final String fileName) {
        runOnUiThread(() -> {
            progress.setText("");
            log.setText("⬇ اتحفظ في Downloads/LanDrop: " + fileName + "\n" + log.getText());
            Toast.makeText(this, "وصل: " + fileName, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public void onConfirm(final String name, final Runnable accept, final Runnable reject) {
        runOnUiThread(() -> new AlertDialog.Builder(this)
                .setTitle("طلب اتصال")
                .setMessage("\"" + name + "\" عايز يتصل بيك. تقبل؟")
                .setCancelable(false)
                .setPositiveButton("قبول", (d, w) -> accept.run())
                .setNegativeButton("رفض", (d, w) -> reject.run())
                .show());
    }

    @Override
    protected void onDestroy() {
        if (link != null) link.stop();
        super.onDestroy();
    }
}
