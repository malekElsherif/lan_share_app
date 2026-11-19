package app.landrop;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** نقل مباشر بالبلوتوث الكلاسيك (RFCOMM). من غير Google Play Services، بس بطيء. */
@SuppressLint("MissingPermission")
final class BluetoothLink implements Link {
    private static final UUID APP_UUID = UUID.fromString("7d6f3b2a-4c1e-4f0a-9b6e-3a1c5e8d9f10");
    private static final int MAGIC = 0x4C44524F;

    private final Context ctx;
    private final Listener ui;
    private BluetoothAdapter adapter;
    private BluetoothServerSocket server;
    private BroadcastReceiver receiver;
    private volatile boolean stopped = false;
    private volatile BluetoothSocket sock;
    private DataOutputStream out;
    private final Object lock = new Object();
    private final Map<String, BluetoothDevice> devices = new HashMap<>();

    BluetoothLink(Context c, Listener l) {
        ctx = c;
        ui = l;
    }

    private String nameOf(BluetoothDevice d) {
        String n = null;
        try { n = d.getName(); } catch (SecurityException ignored) { }
        return n != null ? n : d.getAddress();
    }

    private void addDevice(BluetoothDevice d) {
        devices.put(d.getAddress(), d);
        ui.onDevice(d.getAddress(), nameOf(d));
    }

    @Override
    public void start() {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null) {
            ui.onStatus("الجهاز ده مفيهوش بلوتوث");
            return;
        }
        if (!adapter.isEnabled()) {
            ui.onStatus("شغّل البلوتوث الأول وارجع تاني");
            return;
        }
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) addDevice(d);
        } catch (SecurityException ignored) { }

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                if (BluetoothDevice.ACTION_FOUND.equals(i.getAction())) {
                    BluetoothDevice d = Build.VERSION.SDK_INT >= 33
                            ? i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class)
                            : i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d != null) addDevice(d);
                }
            }
        };
        ctx.registerReceiver(receiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));
        new Thread(this::listen, "bt-listen").start();
        rescan();
        ui.onStatus("اضغط \"خلّي جهازي ظاهر\" على الجهاز اللي هيستقبل، واختار اسمه من القايمة على الجهاز اللي هيبعت");
    }

    @Override
    public void rescan() {
        try {
            if (adapter != null && adapter.isEnabled()) {
                adapter.cancelDiscovery();
                adapter.startDiscovery();
            }
        } catch (SecurityException e) {
            ui.onStatus("محتاج إذن البلوتوث");
        }
    }

    @Override
    public void stop() {
        stopped = true;
        try { if (receiver != null) ctx.unregisterReceiver(receiver); } catch (Exception ignored) { }
        try { if (adapter != null) adapter.cancelDiscovery(); } catch (Exception ignored) { }
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        closeSocket(sock);
    }

    private void closeSocket(BluetoothSocket s) {
        try { if (s != null) s.close(); } catch (IOException ignored) { }
    }

    private void listen() {
        try {
            server = adapter.listenUsingInsecureRfcommWithServiceRecord("LanDrop", APP_UUID);
            final BluetoothSocket s = server.accept();
            server.close();
            final String n = nameOf(s.getRemoteDevice());
            ui.onConfirm(n, () -> attach(s), () -> closeSocket(s));
        } catch (IOException | SecurityException e) {
            if (!stopped) ui.onStatus("السيرفر بتاع البلوتوث وقف");
        }
    }

    @Override
    public void connect(final String id) {
        final BluetoothDevice d = devices.get(id);
        if (d == null) return;
        new Thread(() -> {
            try {
                adapter.cancelDiscovery();
                BluetoothSocket s = d.createInsecureRfcommSocketToServiceRecord(APP_UUID);
                s.connect();
                attach(s);
            } catch (IOException | SecurityException e) {
                ui.onStatus("فشل الاتصال. الجهاز التاني لازم يكون فاتح نفس الوضع وظاهر");
            }
        }, "bt-connect").start();
    }

    private void attach(final BluetoothSocket s) {
        try {
            sock = s;
            synchronized (lock) {
                out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 64 * 1024));
            }
            final String n = nameOf(s.getRemoteDevice());
            ui.onConnected(n);
            new Thread(() -> readLoop(s, n), "bt-read").start();
        } catch (IOException e) {
            closeSocket(s);
            ui.onStatus("فشل الاتصال");
        }
    }

    private void readLoop(BluetoothSocket s, String peer) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 64 * 1024))) {
            while (true) {
                if (in.readInt() != MAGIC) throw new IOException("bad stream");
                String name = in.readUTF();
                long size = in.readLong();
                Saver.Out o = Saver.open(ctx, name);
                boolean ok = false;
                try {
                    byte[] buf = new byte[64 * 1024];
                    long left = size, got = 0;
                    while (left > 0) {
                        int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                        if (n < 0) throw new EOFException();
                        o.os.write(buf, 0, n);
                        left -= n;
                        got += n;
                        if (size > 0) ui.onProgress(name, (int) (100 * got / size));
                    }
                    ok = true;
                } finally {
                    o.finish(ok);
                }
                ui.onReceived(name, peer);
            }
        } catch (IOException ignored) {
            // الاتصال اتقفل
        } finally {
            closeSocket(s);
            if (sock == s) sock = null;
            ui.onDisconnected(peer);
        }
    }

    @Override
    public boolean send(final Uri uri, final String name, final long size) {
        if (sock == null || out == null) return false;
        new Thread(() -> {
            synchronized (lock) {
                try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                    out.writeInt(MAGIC);
                    out.writeUTF(name);
                    out.writeLong(size);
                    byte[] buf = new byte[64 * 1024];
                    long sent = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        sent += n;
                        if (size > 0) ui.onProgress(name, (int) (100 * sent / size));
                    }
                    out.flush();
                    ui.onStatus("✅ اتبعت: " + name);
                } catch (Exception e) {
                    ui.onStatus("❌ فشل إرسال " + name);
                }
            }
        }, "bt-send").start();
        return true;
    }
}
