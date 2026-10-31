package com.warzone.lan;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * سيرفر الغرف المحلية (HTTP + WebSocket) — نقل مباشر لـ lan-server.js بدون أي مكتبات.
 * لا يعتمد على أندرويد، لذلك يمكن اختباره على أي جهاز فيه Java.
 */
public class LanServer {
    public interface Assets { byte[] read(String path) throws IOException; }
    public interface Ips { List<String> get(); }

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final int port;
    private final Assets assets;
    private final Ips ips;
    private final Object lock = new Object();
    private final Map<String, Room> rooms = new HashMap<>();
    private final Random rnd = new Random();
    private ServerSocket server;
    private volatile boolean running;
    private ExecutorService pool;
    private ScheduledExecutorService beat;

    private static class Client {
        final String id;
        final Socket sock;
        final OutputStream out;
        volatile long seen = System.currentTimeMillis();
        volatile boolean closed;

        Client(String id, Socket sock, OutputStream out) { this.id = id; this.sock = sock; this.out = out; }

        void write(byte[] b) {
            synchronized (this) {
                if (closed) return;
                try { out.write(b); out.flush(); } catch (IOException e) { close(); }
            }
        }

        void close() {
            closed = true;
            try { sock.close(); } catch (IOException e) { /* ignore */ }
        }
    }

    private static class Room {
        final String name;
        final String host;
        final Map<String, Client> clients = new LinkedHashMap<>();
        Room(String name, String host) { this.name = name; this.host = host; }
    }

    public LanServer(int port, Assets assets, Ips ips) {
        this.port = port;
        this.assets = assets;
        this.ips = ips;
    }

    public int getPort() { return port; }

    public void start() throws IOException {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(port));
        server = s;
        running = true;
        pool = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "lan-conn"); t.setDaemon(true); return t; });
        beat = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "lan-beat"); t.setDaemon(true); return t; });
        beat.scheduleAtFixedRate(this::heartbeat, 8, 8, TimeUnit.SECONDS);
        Thread acc = new Thread(() -> {
            while (running) {
                try {
                    final Socket c = server.accept();
                    pool.execute(() -> handle(c));
                } catch (IOException e) {
                    if (!running) break;
                }
            }
        }, "lan-accept");
        acc.setDaemon(true);
        acc.start();
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException e) { /* ignore */ }
        List<Client> all = new ArrayList<>();
        synchronized (lock) {
            for (Room r : rooms.values()) all.addAll(r.clients.values());
            rooms.clear();
        }
        for (Client c : all) c.close();
        if (beat != null) beat.shutdownNow();
        if (pool != null) pool.shutdownNow();
    }

    // ---------------------------------------------------------------- helpers

    private static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    /** Base64 بسيط (java.util.Base64 غير متوفر قبل أندرويد 8). */
    private static String b64(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i += 3) {
            int b0 = d[i] & 255, b1 = i + 1 < d.length ? d[i + 1] & 255 : 0, b2 = i + 2 < d.length ? d[i + 2] & 255 : 0;
            sb.append(B64.charAt(b0 >> 2));
            sb.append(B64.charAt(((b0 & 3) << 4) | (b1 >> 4)));
            sb.append(i + 1 < d.length ? B64.charAt(((b1 & 15) << 2) | (b2 >> 6)) : '=');
            sb.append(i + 2 < d.length ? B64.charAt(b2 & 63) : '=');
        }
        return sb.toString();
    }

    private static String key(String n) { return n == null ? "" : n.trim().toLowerCase(Locale.ROOT); }

    private static String jstr(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') b.append("\\\"");
            else if (c == '\\') b.append("\\\\");
            else if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    private static byte[] frame(byte[] p, int op) {
        int n = p.length;
        byte[] h;
        if (n < 126) { h = new byte[]{(byte) (0x80 | op), (byte) n}; }
        else if (n < 65536) { h = new byte[]{(byte) (0x80 | op), 126, (byte) (n >> 8), (byte) n}; }
        else {
            h = new byte[10];
            h[0] = (byte) (0x80 | op);
            h[1] = 127;
            long l = n;
            for (int i = 0; i < 8; i++) h[9 - i] = (byte) (l >> (8 * i));
        }
        byte[] r = new byte[h.length + n];
        System.arraycopy(h, 0, r, 0, h.length);
        System.arraycopy(p, 0, r, h.length, n);
        return r;
    }

    private static byte[] textFrame(String s) { return frame(s.getBytes(StandardCharsets.UTF_8), 1); }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("line too long");
        }
        if (c < 0 && sb.length() == 0) return null;
        return sb.toString();
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int o = 0;
        while (o < n) {
            int r = in.read(b, o, n - o);
            if (r < 0) throw new EOFException();
            o += r;
        }
        return b;
    }

    private static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<>();
        if (q == null || q.isEmpty()) return m;
        for (String part : q.split("&")) {
            if (part.isEmpty()) continue;
            int i = part.indexOf('=');
            String k = i < 0 ? part : part.substring(0, i);
            String v = i < 0 ? "" : part.substring(i + 1);
            try {
                m.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
            } catch (Exception e) { m.put(k, v); }
        }
        return m;
    }

    private String code() {
        String c;
        synchronized (lock) {
            do { c = String.valueOf(1000 + rnd.nextInt(9000)); } while (rooms.containsKey(key(c)));
        }
        return c;
    }

    private List<Room> live() {
        List<Room> l = new ArrayList<>();
        for (Room r : rooms.values()) if (!r.clients.isEmpty()) l.add(r);
        return l;
    }

    private String ipsJson() {
        StringBuilder b = new StringBuilder("[");
        List<String> l = ips == null ? new ArrayList<String>() : ips.get();
        for (int i = 0; i < l.size(); i++) { if (i > 0) b.append(','); b.append(jstr(l.get(i))); }
        return b.append(']').toString();
    }

    // ------------------------------------------------------------------- HTTP

    private void handle(Socket s) {
        try {
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            s.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(s.getInputStream(), 8192);
            String req = readLine(in);
            if (req == null) { s.close(); return; }
            String[] p = req.split(" ");
            if (p.length < 2) { s.close(); return; }
            String method = p[0], target = p[1];
            Map<String, String> h = new HashMap<>();
            String line;
            int count = 0;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int i = line.indexOf(':');
                if (i > 0) h.put(line.substring(0, i).trim().toLowerCase(Locale.ROOT), line.substring(i + 1).trim());
                if (++count > 100) throw new IOException("too many headers");
            }
            int qi = target.indexOf('?');
            String path = qi < 0 ? target : target.substring(0, qi);
            String query = qi < 0 ? "" : target.substring(qi + 1);

            String up = h.get("upgrade");
            if (up != null && up.equalsIgnoreCase("websocket")) {
                String k = h.get("sec-websocket-key");
                if (!"/ws".equals(path) || k == null) { s.close(); return; }
                webSocket(s, in, parseQuery(query), k);
                return;
            }
            http(s, method, path);
        } catch (Exception e) {
            try { s.close(); } catch (IOException x) { /* ignore */ }
        }
    }

    private void respond(Socket s, int code, String type, byte[] body, boolean head) throws IOException {
        String reason = code == 200 ? "OK" : code == 204 ? "No Content" : code == 403 ? "Forbidden" : "Not Found";
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        sb.append("Content-Type: ").append(type).append("\r\n");
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Cache-Control: no-cache\r\n");
        sb.append("Connection: close\r\n\r\n");
        OutputStream out = s.getOutputStream();
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (!head) out.write(body);
        out.flush();
        s.close();
    }

    private static String mime(String f) {
        String l = f.toLowerCase(Locale.ROOT);
        if (l.endsWith(".html")) return "text/html; charset=utf-8";
        if (l.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (l.endsWith(".json")) return "application/json";
        if (l.endsWith(".png")) return "image/png";
        if (l.endsWith(".jpg") || l.endsWith(".jpeg")) return "image/jpeg";
        if (l.endsWith(".svg")) return "image/svg+xml";
        if (l.endsWith(".css")) return "text/css";
        if (l.endsWith(".ico")) return "image/x-icon";
        if (l.endsWith(".woff2")) return "font/woff2";
        if (l.endsWith(".mp3")) return "audio/mpeg";
        if (l.endsWith(".ogg")) return "audio/ogg";
        if (l.endsWith(".wav")) return "audio/wav";
        return "application/octet-stream";
    }

    private void http(Socket s, String method, String rawPath) throws IOException {
        boolean head = "HEAD".equals(method);
        if ("OPTIONS".equals(method)) { respond(s, 204, "text/plain", new byte[0], true); return; }
        String u;
        try { u = URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8"); }
        catch (Exception e) { respond(s, 403, "text/plain", "403".getBytes(StandardCharsets.UTF_8), head); return; }

        if (u.startsWith("/pay/")) { respond(s, 404, "application/json", "{}".getBytes(StandardCharsets.UTF_8), head); return; }

        if ("/info".equals(u)) {
            StringBuilder b = new StringBuilder("{\"ips\":").append(ipsJson()).append(",\"port\":").append(port).append(",\"rooms\":[");
            String first = null; int firstN = 0;
            synchronized (lock) {
                List<Room> l = live();
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) b.append(',');
                    b.append("{\"name\":").append(jstr(l.get(i).name)).append(",\"n\":").append(l.get(i).clients.size()).append('}');
                }
                if (!l.isEmpty()) { first = l.get(0).name; firstN = l.get(0).clients.size(); }
            }
            b.append("],\"room\":").append(first == null ? "null" : jstr(first)).append(",\"n\":").append(firstN).append('}');
            respond(s, 200, "application/json", b.toString().getBytes(StandardCharsets.UTF_8), head);
            return;
        }

        if ("/".equals(u)) u = "/index.html";
        String rel = u.startsWith("/") ? u.substring(1) : u;
        boolean bad = rel.isEmpty() || rel.contains("\\") || rel.contains("\0");
        for (String seg : rel.split("/")) if (seg.startsWith(".")) bad = true;
        if (bad) { respond(s, 404, "text/plain", "404".getBytes(StandardCharsets.UTF_8), head); return; }

        byte[] d = null;
        try { d = assets.read(rel); } catch (IOException e) { d = null; }
        if (d == null) { respond(s, 404, "text/plain", "404".getBytes(StandardCharsets.UTF_8), head); return; }
        respond(s, 200, mime(rel), d, head);
    }

    // -------------------------------------------------------------- WebSocket

    private void webSocket(Socket s, InputStream in, Map<String, String> q, String wsKey) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        String acc = b64(md.digest((wsKey + GUID).getBytes(StandardCharsets.ISO_8859_1)));
        OutputStream out = s.getOutputStream();
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + acc + "\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        s.setSoTimeout(0);

        String id = q.containsKey("id") ? q.get("id").replaceAll("[^\\w-]", "") : "";
        if (id.length() > 20) id = id.substring(0, 20);
        if (id.isEmpty()) id = "u" + Integer.toString(rnd.nextInt(Integer.MAX_VALUE), 36);
        final Client c = new Client(id, s, out);

        String create = q.get("create");
        String want = q.get("room");
        Room R;
        boolean isHost = false;
        String errCode = null;
        int size = 0;

        synchronized (lock) {
            R = null;
            if (create != null) {
                String name = create.trim();
                if (name.length() > 16) name = name.substring(0, 16);
                if (name.isEmpty()) {
                    String cd;
                    do { cd = String.valueOf(1000 + rnd.nextInt(9000)); } while (rooms.containsKey(key(cd)));
                    name = cd;
                }
                Room ex = rooms.get(key(name));
                if (ex != null && !ex.clients.isEmpty()) errCode = "exists";
                else {
                    R = new Room(name, id);
                    rooms.put(key(name), R);
                    isHost = true;
                }
            } else {
                List<Room> L = live();
                if (want != null && !want.trim().isEmpty()) {
                    R = rooms.get(key(want));
                    if (R == null || R.clients.isEmpty()) { R = null; errCode = "noroom"; }
                } else if (L.size() == 1) R = L.get(0);
                else if (L.isEmpty()) errCode = "noroom";
                else errCode = "pick";
                if (R != null) {
                    if (R.clients.size() >= 10) { R = null; errCode = "full"; }
                    else if (R.clients.containsKey(id)) {
                        id = id + Integer.toString(rnd.nextInt(1296), 36);
                    }
                }
            }
            if (R != null) { R.clients.put(id, c); size = R.clients.size(); }
        }

        if (R == null) {
            c.write(textFrame("{\"t\":\"err\",\"e\":" + jstr(errCode == null ? "noroom" : errCode) + "}"));
            beat.schedule(c::close, 200, TimeUnit.MILLISECONDS);
            return;
        }

        final Room room = R;
        final String myId = id;
        c.write(textFrame("{\"t\":\"ok\",\"name\":" + jstr(room.name) + ",\"ips\":" + ipsJson() + ",\"port\":" + port
                + ",\"n\":" + size + ",\"host\":" + (isHost ? 1 : 0) + "}"));
        bcast(room, myId, "{\"t\":\"j\",\"id\":" + jstr(myId) + "}");

        try {
            while (running && !c.closed) {
                int b0 = in.read();
                if (b0 < 0) break;
                int b1 = in.read();
                if (b1 < 0) break;
                int op = b0 & 15;
                boolean masked = (b1 & 128) != 0;
                long len = b1 & 127;
                if (len == 126) { byte[] e = readN(in, 2); len = ((e[0] & 255) << 8) | (e[1] & 255); }
                else if (len == 127) { byte[] e = readN(in, 8); len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | (e[i] & 255); }
                if (len > 1000000L) break;
                byte[] mask = masked ? readN(in, 4) : null;
                byte[] pl = readN(in, (int) len);
                if (masked) for (int i = 0; i < pl.length; i++) pl[i] ^= mask[i & 3];
                c.seen = System.currentTimeMillis();
                if (op == 8) break;
                if (op == 9) { c.write(frame(pl, 10)); continue; }
                if (op == 1) bcast(room, myId, new String(pl, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            // انقطع الاتصال
        } finally {
            onClose(room, myId, c);
        }
    }

    private void bcast(Room R, String from, String text) {
        byte[] f = textFrame(text);
        List<Client> to = new ArrayList<>();
        synchronized (lock) {
            for (Map.Entry<String, Client> e : R.clients.entrySet()) if (!e.getKey().equals(from)) to.add(e.getValue());
        }
        for (Client c : to) c.write(f);
    }

    private void onClose(Room R, String id, Client c) {
        List<Client> kick = new ArrayList<>();
        boolean notify = false;
        synchronized (lock) {
            if (R.clients.get(id) != c) { c.close(); return; }
            R.clients.remove(id);
            notify = true;
            if (R.host.equals(id)) {
                kick.addAll(R.clients.values());
                R.clients.clear();
            }
            if (R.clients.isEmpty() && rooms.get(key(R.name)) == R) rooms.remove(key(R.name));
        }
        c.close();
        if (notify) bcast(R, id, "{\"t\":\"x\",\"id\":" + jstr(id) + "}");
        for (Client k : kick) k.close();
    }

    private void heartbeat() {
        long now = System.currentTimeMillis();
        List<Client> all = new ArrayList<>();
        synchronized (lock) { for (Room r : rooms.values()) all.addAll(r.clients.values()); }
        byte[] ping = frame(new byte[0], 9);
        for (Client c : all) {
            if (now - c.seen > 30000) c.close();
            else c.write(ping);
        }
    }

    // ------------------------------------------------- تشغيل تجريبي على الكمبيوتر
    public static void main(String[] a) throws Exception {
        final int port = a.length > 0 ? Integer.parseInt(a[0]) : 8080;
        final File dir = new File(a.length > 1 ? a[1] : ".").getCanonicalFile();
        LanServer s = new LanServer(port, path -> {
            File f = new File(dir, path).getCanonicalFile();
            if (!f.getPath().startsWith(dir.getPath()) || !f.isFile()) return null;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            }
            return bo.toByteArray();
        }, () -> { List<String> l = new ArrayList<>(); l.add("192.168.1.50"); return l; });
        s.start();
        System.out.println("READY " + port);
        Thread.sleep(Long.MAX_VALUE);
    }
}
