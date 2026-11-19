package app.landrop;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** سيرفر HTTP صغير بنفس واجهة lan_drop.py (نفس الـ API ونفس الصفحة). */
public class LanServer {
    private static final int CHUNK = 256 * 1024;
    private final File root;
    private final int port;
    private final byte[] page;
    private ServerSocket server;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "landrop-conn");
        t.setDaemon(true);
        return t;
    });
    private final List<String[]> texts = new ArrayList<>(); // {id, text, time, senderId, sender, hostFlag}
    private final Map<String, String[]> meta = new HashMap<>(); // اسم الملف -> {senderId, sender, hostFlag}

    /** إشعارات لما جهاز تاني يبعت ملف أو رسالة. */
    public interface Events {
        void onFile(String name, String from);
        void onText(String text, String from);
    }

    private volatile Events events;

    public void setEvents(Events e) {
        events = e;
    }

    private static final class Who {
        final String id, name;
        final boolean host;

        Who(String id, String name, boolean host) {
            this.id = id;
            this.name = name;
            this.host = host;
        }
    }

    private Who whoOf(Socket s, Map<String, String> h) {
        String id = h.containsKey("x-device-id") ? h.get("x-device-id") : "";
        if (id.length() > 64) id = id.substring(0, 64);
        String name = "";
        try {
            name = URLDecoder.decode(h.containsKey("x-device-name") ? h.get("x-device-name").replace("+", "%2B") : "", "UTF-8");
        } catch (Exception ignored) { }
        name = name.replaceAll("[\\x00-\\x1f]", "").trim();
        if (name.length() > 40) name = name.substring(0, 40);
        if (name.isEmpty()) name = "جهاز مجهول";
        InetAddress a = s.getInetAddress();
        String ip = a.getHostAddress();
        boolean host = ip.equals("127.0.0.1") || ip.equals("0:0:0:0:0:0:0:1") || a.equals(s.getLocalAddress());
        return new Who(id, name, host);
    }

    private File metaFile() {
        return new File(root, ".landrop-meta");
    }

    private void loadMeta() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(metaFile()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", 4);
                if (p.length == 4) meta.put(p[0], new String[]{p[1], p[2], p[3]});
            }
        } catch (IOException ignored) { }
    }

    private void saveMeta() {
        synchronized (meta) {
            try (Writer w = new OutputStreamWriter(new FileOutputStream(metaFile()), StandardCharsets.UTF_8)) {
                for (Map.Entry<String, String[]> e : meta.entrySet()) {
                    String[] v = e.getValue();
                    w.write(clean(e.getKey()) + "\t" + clean(v[0]) + "\t" + clean(v[1]) + "\t" + v[2] + "\n");
                }
            } catch (IOException ignored) { }
        }
    }

    private static String clean(String t) {
        return t.replaceAll("[\\t\\r\\n]", " ");
    }

    public LanServer(File root, int port, byte[] page) {
        this.root = root;
        this.port = port;
        this.page = page;
        root.mkdirs();
        loadMeta();
    }

    public void start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port));
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    final Socket s = server.accept();
                    pool.execute(() -> handle(s));
                } catch (IOException e) {
                    if (server.isClosed()) break;
                }
            }
        }, "landrop-accept");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        try { server.close(); } catch (Exception ignored) { }
        pool.shutdownNow();
    }

    // ---------- helpers ----------
    static String safeName(String n) {
        if (n == null) return null;
        n = n.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        n = n.replaceAll("[\\x00-\\x1f<>:\"|?*]", "_");
        n = n.replaceAll("^[ .]+|[ .]+$", "");
        return n.isEmpty() ? null : n;
    }

    private File uniqueFile(String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        File f = new File(root, name);
        int i = 1;
        while (f.exists() || new File(root, name + ".part").exists()) {
            f = new File(root, base + " (" + i + ")" + ext);
            i++;
        }
        return f;
    }

    public static String lanIp() {
        String best = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String n = ni.getName();
                        if (n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("eth")) return a.getHostAddress();
                        if (best == null) best = a.getHostAddress();
                    }
                }
            }
        } catch (SocketException ignored) { }
        return best != null ? best : "127.0.0.1";
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    /** بيطلّع قيمة نصية من JSON بسيط {"text":"..."} */
    static String jsonString(String json, String key) {
        int k = json.indexOf("\"" + key + "\"");
        if (k < 0) return "";
        int i = json.indexOf(':', k);
        if (i < 0) return "";
        i = json.indexOf('"', i);
        if (i < 0) return "";
        StringBuilder b = new StringBuilder();
        for (i++; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') break;
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(++i);
                switch (n) {
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u':
                        if (i + 4 < json.length()) {
                            b.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                        break;
                    default: b.append(n);
                }
            } else b.append(c);
        }
        return b.toString();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new IOException("line too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("ISO-8859-1");
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<>();
        if (q == null || q.isEmpty()) return m;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            try {
                String k = URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), "UTF-8");
                String v = i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), "UTF-8");
                m.put(k, v);
            } catch (Exception ignored) { }
        }
        return m;
    }

    private static String reason(int c) {
        switch (c) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 411: return "Length Required";
            case 416: return "Range Not Satisfiable";
            default: return "Error";
        }
    }

    private void head(OutputStream out, int code, String ctype, long len, String... extra) throws IOException {
        StringBuilder sb = new StringBuilder("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        if (ctype != null) sb.append("Content-Type: ").append(ctype).append("\r\n");
        sb.append("Content-Length: ").append(len).append("\r\n");
        sb.append("Cache-Control: no-store\r\nConnection: close\r\n");
        for (String e : extra) sb.append(e).append("\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    private void send(OutputStream out, int code, String ctype, byte[] body) throws IOException {
        head(out, code, ctype, body.length);
        out.write(body);
        out.flush();
    }

    private void json(OutputStream out, int code, String body) throws IOException {
        send(out, code, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    // ---------- request handling ----------
    private void handle(Socket sock) {
        try (Socket s = sock) {
            s.setSoTimeout(60000);
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream(), 16384);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 65536);
            String rl = readLine(in);
            if (rl == null || rl.isEmpty()) return;
            String[] p = rl.split(" ");
            if (p.length < 2) return;
            String method = p[0], target = p[1];
            Map<String, String> h = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int i = line.indexOf(':');
                if (i > 0) h.put(line.substring(0, i).trim().toLowerCase(Locale.ROOT), line.substring(i + 1).trim());
            }
            String path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
            Map<String, String> qp = parseQuery(query);
            Who who = whoOf(s, h);
            if (method.equals("GET") || method.equals("HEAD")) doGet(out, path, qp, h, who);
            else if (method.equals("POST")) doPost(in, out, path, qp, h, who);
            else send(out, 400, "text/plain", "Bad request".getBytes());
            out.flush();
        } catch (Exception ignored) { }
    }

    private void doGet(OutputStream out, String path, Map<String, String> qp, Map<String, String> h, Who who) throws IOException {
        if (path.equals("/")) { send(out, 200, "text/html; charset=utf-8", page); return; }
        if (path.equals("/api/files")) {
            File[] fs = root.listFiles();
            List<File> list = new ArrayList<>();
            if (fs != null) for (File f : fs)
                if (f.isFile() && !f.getName().endsWith(".part") && !f.getName().startsWith(".")) list.add(f);
            Collections.sort(list, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                File f = list.get(i);
                if (i > 0) sb.append(',');
                String[] m;
                synchronized (meta) { m = meta.get(f.getName()); }
                boolean mine = m != null && !who.id.isEmpty() && m[0].equals(who.id);
                sb.append("{\"name\":").append(esc(f.getName())).append(",\"size\":").append(f.length())
                  .append(",\"mtime\":").append(f.lastModified() / 1000)
                  .append(",\"from\":").append(esc(m != null ? m[1] : "غير معروف"))
                  .append(",\"fromHost\":").append(m != null && "1".equals(m[2]))
                  .append(",\"mine\":").append(mine)
                  .append(",\"canDelete\":").append(who.host || mine).append('}');
            }
            json(out, 200, sb.append(']').toString());
            return;
        }
        if (path.equals("/api/texts")) {
            StringBuilder sb = new StringBuilder("[");
            synchronized (texts) {
                for (int i = 0; i < texts.size(); i++) {
                    String[] t = texts.get(i);
                    if (i > 0) sb.append(',');
                    boolean mine = !who.id.isEmpty() && t[3].equals(who.id);
                    sb.append("{\"id\":").append(t[0]).append(",\"text\":").append(esc(t[1]))
                      .append(",\"time\":").append(t[2])
                      .append(",\"from\":").append(esc(t[4]))
                      .append(",\"fromHost\":").append("1".equals(t[5]))
                      .append(",\"mine\":").append(mine)
                      .append(",\"canDelete\":").append(who.host || mine).append('}');
                }
            }
            json(out, 200, sb.append(']').toString());
            return;
        }
        if (path.equals("/api/info")) {
            json(out, 200, "{\"link\":" + esc("http://" + lanIp() + ":" + port) + "}");
            return;
        }
        if (path.startsWith("/files/")) {
            String raw;
            try { raw = URLDecoder.decode(path.substring(7).replace("+", "%2B"), "UTF-8"); }
            catch (Exception e) { send(out, 400, "text/plain", "bad".getBytes()); return; }
            download(out, raw, qp.containsKey("dl"), h.get("range"));
            return;
        }
        send(out, 404, "text/plain", "Not found".getBytes());
    }

    private void download(OutputStream out, String raw, boolean dl, String range) throws IOException {
        String name = safeName(raw);
        File f = name == null ? null : new File(root, name);
        if (f == null || !f.isFile()) { send(out, 404, "text/plain", "Not found".getBytes()); return; }
        long size = f.length(), start = 0, end = size - 1;
        int status = 200;
        if (range != null) {
            Matcher m = Pattern.compile("bytes=(\\d*)-(\\d*)").matcher(range.trim());
            if (m.matches() && (!m.group(1).isEmpty() || !m.group(2).isEmpty())) {
                if (!m.group(1).isEmpty()) {
                    start = Long.parseLong(m.group(1));
                    if (!m.group(2).isEmpty()) end = Long.parseLong(m.group(2));
                } else {
                    start = Math.max(0, size - Long.parseLong(m.group(2)));
                }
                end = Math.min(end, size - 1);
                if (start > end || start >= size) {
                    head(out, 416, null, 0, "Content-Range: bytes */" + size);
                    out.flush();
                    return;
                }
                status = 206;
            }
        }
        String ctype = URLConnection.guessContentTypeFromName(name);
        if (ctype == null) ctype = "application/octet-stream";
        String enc = URLEncoder.encode(name, "UTF-8").replace("+", "%20");
        List<String> extra = new ArrayList<>();
        extra.add("Accept-Ranges: bytes");
        if (status == 206) extra.add("Content-Range: bytes " + start + "-" + end + "/" + size);
        extra.add("Content-Disposition: " + (dl ? "attachment" : "inline") + "; filename*=UTF-8''" + enc);
        head(out, status, ctype, end - start + 1, extra.toArray(new String[0]));
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.seek(start);
            byte[] buf = new byte[CHUNK];
            long left = end - start + 1;
            while (left > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) break;
                out.write(buf, 0, n);
                left -= n;
            }
        }
        out.flush();
    }

    private void doPost(InputStream in, OutputStream out, String path, Map<String, String> qp,
                        Map<String, String> h, Who who) throws IOException {
        try {
            if (path.equals("/api/upload")) { upload(in, out, qp, h, who); return; }
            if (path.equals("/api/delete")) {
                String name = safeName(qp.get("name"));
                File f = name == null ? null : new File(root, name);
                if (f == null || !f.isFile()) { json(out, 404, "{\"error\":\"not found\"}"); return; }
                String[] m;
                synchronized (meta) { m = meta.get(name); }
                boolean allowed = who.host || (m != null && !who.id.isEmpty() && m[0].equals(who.id));
                if (!allowed) {
                    json(out, 403, "{\"error\":" + esc("مش مسموحلك تحذف ده: بس صاحب السيرفر أو اللي رفعه") + "}");
                    return;
                }
                if (f.delete()) {
                    synchronized (meta) { meta.remove(name); }
                    saveMeta();
                    json(out, 200, "{\"ok\":true}");
                } else json(out, 404, "{\"error\":\"not found\"}");
                return;
            }
            if (path.equals("/api/text")) {
                long n = Long.parseLong(h.getOrDefault("content-length", "0"));
                if (n > 200000) { json(out, 400, "{\"error\":\"too large\"}"); return; }
                byte[] b = new byte[(int) n];
                new DataInputStream(in).readFully(b);
                String text = jsonString(new String(b, StandardCharsets.UTF_8), "text").trim();
                if (text.isEmpty()) { json(out, 400, "{\"error\":\"empty\"}"); return; }
                if (text.length() > 100000) text = text.substring(0, 100000);
                synchronized (texts) {
                    texts.add(0, new String[]{String.valueOf(System.currentTimeMillis()), text,
                            String.valueOf(System.currentTimeMillis() / 1000), who.id, who.name, who.host ? "1" : "0"});
                    while (texts.size() > 30) texts.remove(texts.size() - 1);
                }
                Events ev = events;
                if (ev != null && !who.host) ev.onText(text, who.name);
                json(out, 200, "{\"ok\":true}");
                return;
            }
            if (path.equals("/api/text/delete")) {
                String id = qp.getOrDefault("id", "0");
                boolean denied = false;
                synchronized (texts) {
                    for (Iterator<String[]> it = texts.iterator(); it.hasNext(); ) {
                        String[] t = it.next();
                        if (!t[0].equals(id)) continue;
                        if (who.host || (!who.id.isEmpty() && t[3].equals(who.id))) it.remove();
                        else denied = true;
                    }
                }
                if (denied) json(out, 403, "{\"error\":" + esc("مش مسموحلك تحذف ده: بس صاحب السيرفر أو اللي بعته") + "}");
                else json(out, 200, "{\"ok\":true}");
                return;
            }
        } catch (Exception e) {
            json(out, 400, "{\"error\":" + esc(String.valueOf(e.getMessage())) + "}");
            return;
        }
        send(out, 404, "text/plain", "Not found".getBytes());
    }

    private void upload(InputStream in, OutputStream out, Map<String, String> qp, Map<String, String> h, Who who) throws IOException {
        String name = safeName(qp.get("name"));
        if (name == null) { json(out, 400, "{\"error\":\"bad name\"}"); return; }
        String cl = h.get("content-length");
        if (cl == null) { json(out, 411, "{\"error\":\"length required\"}"); return; }
        long left = Long.parseLong(cl);
        File fin = uniqueFile(name);
        File part = new File(fin.getPath() + ".part");
        boolean ok = false;
        try (OutputStream fo = new BufferedOutputStream(new FileOutputStream(part), CHUNK)) {
            byte[] buf = new byte[CHUNK];
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new IOException("upload interrupted");
                fo.write(buf, 0, n);
                left -= n;
            }
            fo.flush();
            ok = true;
        } catch (IOException e) {
            part.delete();
            json(out, 400, "{\"error\":" + esc(String.valueOf(e.getMessage())) + "}");
            return;
        } finally {
            if (!ok) part.delete();
        }
        if (!part.renameTo(fin)) { part.delete(); json(out, 400, "{\"error\":\"rename failed\"}"); return; }
        synchronized (meta) { meta.put(fin.getName(), new String[]{who.id, who.name, who.host ? "1" : "0"}); }
        saveMeta();
        Events ev = events;
        if (ev != null && !who.host) ev.onFile(fin.getName(), who.name);
        json(out, 200, "{\"ok\":true,\"name\":" + esc(fin.getName()) + "}");
    }
}
