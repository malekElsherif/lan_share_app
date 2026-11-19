package app.landrop;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** اكتشاف السيرفر على الشبكة (نفس بروتوكول lan_drop.py: UDP 8766). */
public final class Discovery {
    public static final int PORT = 8766;

    private Discovery() { }

    public static List<InetAddress> targets() {
        List<InetAddress> t = new ArrayList<>();
        try {
            t.add(InetAddress.getByName("255.255.255.255"));
            t.add(InetAddress.getByName("127.0.0.1"));
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses())
                    if (ia.getBroadcast() != null) t.add(ia.getBroadcast());
            }
        } catch (Exception ignored) { }
        return t;
    }

    /** بيرجّع {ip, port} لو لقى سيرفر، أو null. */
    public static String[] find(int timeoutMs) {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(300);
            byte[] q = "LANDROP?".getBytes(StandardCharsets.UTF_8);
            byte[] buf = new byte[64];
            List<InetAddress> t = targets();
            long end = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < end) {
                for (InetAddress a : t) {
                    try { s.send(new DatagramPacket(q, q.length, a, PORT)); } catch (IOException ignored) { }
                }
                try {
                    DatagramPacket r = new DatagramPacket(buf, buf.length);
                    s.receive(r);
                    String d = new String(r.getData(), 0, r.getLength(), StandardCharsets.UTF_8);
                    if (d.startsWith("LANDROP!"))
                        return new String[]{r.getAddress().getHostAddress(), d.substring(8).trim()};
                } catch (SocketTimeoutException ignored) { }
            }
        } catch (IOException ignored) { }
        return null;
    }

    /** بيرد على أي جهاز بيدوّر على السيرفر. */
    public static final class Responder {
        private DatagramSocket sock;

        public void start(final int httpPort) throws IOException {
            sock = new DatagramSocket(null);
            sock.setReuseAddress(true);
            sock.bind(new InetSocketAddress(PORT));
            Thread t = new Thread(() -> {
                byte[] buf = new byte[64];
                while (!sock.isClosed()) {
                    try {
                        DatagramPacket r = new DatagramPacket(buf, buf.length);
                        sock.receive(r);
                        if ("LANDROP?".equals(new String(r.getData(), 0, r.getLength(), StandardCharsets.UTF_8))) {
                            byte[] a = ("LANDROP!" + httpPort).getBytes(StandardCharsets.UTF_8);
                            sock.send(new DatagramPacket(a, a.length, r.getAddress(), r.getPort()));
                        }
                    } catch (IOException e) {
                        if (sock.isClosed()) break;
                    }
                }
            }, "landrop-discovery");
            t.setDaemon(true);
            t.start();
        }

        public void stop() {
            if (sock != null) sock.close();
        }
    }
}
