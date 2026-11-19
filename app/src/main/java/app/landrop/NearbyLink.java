package app.landrop;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** نقل مباشر عن طريق Google Nearby Connections (بلوتوث + Wi-Fi Direct/Hotspot تلقائيًا). */
final class NearbyLink implements Link {
    private static final String SERVICE = "app.landrop.nearby";

    private final Context ctx;
    private final Listener ui;
    private final ConnectionsClient client;
    private final String me = Build.MODEL;
    private final Set<String> connected = new LinkedHashSet<>();
    private final Map<String, String> endpointNames = new HashMap<>();
    private final Map<Long, Payload> incoming = new HashMap<>();
    private final Map<Long, String> inNames = new HashMap<>();
    private final Map<Long, String> outNames = new HashMap<>();

    NearbyLink(Context c, Listener l) {
        ctx = c;
        ui = l;
        client = Nearby.getConnectionsClient(c);
    }

    @Override
    public void start() {
        client.startAdvertising(me, SERVICE, lifecycle,
                new AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                .addOnFailureListener(e -> ui.onStatus("فشل إظهار الجهاز: " + e.getMessage()));
        rescan();
        ui.onStatus("بندوّر على أجهزة قريبة... افتح نفس الوضع على الجهاز التاني");
    }

    @Override
    public void rescan() {
        client.stopDiscovery();
        client.startDiscovery(SERVICE, discovery,
                new DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                .addOnFailureListener(e -> ui.onStatus("فشل البحث: " + e.getMessage()));
    }

    @Override
    public void stop() {
        client.stopAdvertising();
        client.stopDiscovery();
        client.stopAllEndpoints();
        connected.clear();
    }

    @Override
    public void connect(String id) {
        client.requestConnection(me, id, lifecycle)
                .addOnFailureListener(e -> ui.onStatus("فشل الاتصال: " + e.getMessage()));
    }

    @Override
    public boolean send(Uri uri, String name, long size) {
        if (connected.isEmpty()) return false;
        try {
            ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "r");
            Payload file = Payload.fromFile(pfd);
            outNames.put(file.getId(), name);
            List<String> to = new ArrayList<>(connected);
            client.sendPayload(to, Payload.fromBytes((file.getId() + "|" + name).getBytes(StandardCharsets.UTF_8)));
            client.sendPayload(to, file);
            return true;
        } catch (Exception e) {
            ui.onStatus("فشل فتح الملف: " + name);
            return false;
        }
    }

    private final EndpointDiscoveryCallback discovery = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(String id, DiscoveredEndpointInfo info) {
            ui.onDevice(id, info.getEndpointName());
        }

        @Override
        public void onEndpointLost(String id) {
            ui.onDeviceLost(id);
        }
    };

    private final ConnectionLifecycleCallback lifecycle = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(final String id, ConnectionInfo info) {
            endpointNames.put(id, info.getEndpointName());
            ui.onConfirm(info.getEndpointName(),
                    () -> client.acceptConnection(id, payloads),
                    () -> client.rejectConnection(id));
        }

        @Override
        public void onConnectionResult(String id, ConnectionResolution r) {
            if (r.getStatus().getStatusCode() == ConnectionsStatusCodes.STATUS_OK) {
                connected.add(id);
                client.stopDiscovery();
                client.stopAdvertising();
                ui.onConnected(endpointNames.get(id));
            } else {
                ui.onStatus("الاتصال اتلغى أو اترفض");
            }
        }

        @Override
        public void onDisconnected(String id) {
            connected.remove(id);
            ui.onDisconnected(endpointNames.get(id));
        }
    };

    private final PayloadCallback payloads = new PayloadCallback() {
        @Override
        public void onPayloadReceived(String id, Payload p) {
            if (p.getType() == Payload.Type.BYTES) {
                String s = new String(p.asBytes(), StandardCharsets.UTF_8);
                int i = s.indexOf('|');
                if (i > 0) {
                    try { inNames.put(Long.parseLong(s.substring(0, i)), s.substring(i + 1)); }
                    catch (NumberFormatException ignored) { }
                }
            } else if (p.getType() == Payload.Type.FILE) {
                incoming.put(p.getId(), p);
            }
        }

        @Override
        public void onPayloadTransferUpdate(String id, PayloadTransferUpdate u) {
            long pid = u.getPayloadId();
            String label = outNames.containsKey(pid) ? outNames.get(pid) : inNames.get(pid);
            int st = u.getStatus();
            if (st == PayloadTransferUpdate.Status.IN_PROGRESS) {
                if (label != null && u.getTotalBytes() > 0)
                    ui.onProgress(label, (int) (100 * u.getBytesTransferred() / u.getTotalBytes()));
            } else if (st == PayloadTransferUpdate.Status.SUCCESS) {
                if (outNames.remove(pid) != null) {
                    ui.onStatus("✅ اتبعت: " + label);
                } else {
                    Payload p = incoming.remove(pid);
                    if (p != null) save(p, inNames.remove(pid));
                }
            } else if (st == PayloadTransferUpdate.Status.FAILURE || st == PayloadTransferUpdate.Status.CANCELED) {
                outNames.remove(pid);
                incoming.remove(pid);
                ui.onStatus("❌ فشل نقل ملف" + (label != null ? ": " + label : ""));
            }
        }
    };

    private void save(final Payload p, String name) {
        final String fname = name != null ? name : "nearby-" + p.getId();
        new Thread(() -> {
            try {
                Uri src = p.asFile().asUri();
                InputStream in = ctx.getContentResolver().openInputStream(src);
                Saver.Out o = Saver.open(ctx, fname);
                boolean ok = false;
                try {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) o.os.write(buf, 0, n);
                    ok = true;
                } finally {
                    in.close();
                    o.finish(ok);
                }
                try { ctx.getContentResolver().delete(src, null, null); } catch (Exception ignored) { }
                ui.onReceived(fname);
            } catch (Exception e) {
                ui.onStatus("❌ فشل حفظ " + fname);
            }
        }).start();
    }
}
