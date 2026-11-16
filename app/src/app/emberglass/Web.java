package app.emberglass;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Tiny HTTP server for the LAN.
 *   /          live page
 *   /stream    MJPEG
 *   /snapshot.jpg
 *   /temps     JSON statistics
 *   /raw       256x192 uint16 LE, celsius = v / 64 - 273.15
 *   /cmd?name=..&v=..
 * No authentication: anyone on the same network can watch and send commands.
 */
final class Web {
    interface Commands {
        String run(String name, String value) throws Exception;
        String statusJson();
        /** Bundled file from assets/, or null. */
        byte[] asset(String name);
        /** Required as ?pin= on every request when not empty. */
        String pin();
        /** False: the browser may watch but not send commands. */
        boolean remoteControl();
        /** Monitoring samples newer than `since` seconds, as JSON. */
        String history(double since);
        /** Current monitoring CSV, or null. */
        java.io.File logFile();
    }

    private final Thermal th;
    private final Commands cmds;
    private final byte[] page;
    private ServerSocket server;

    Web(Thermal th, Commands cmds, byte[] page) { this.th = th; this.cmds = cmds; this.page = page; }

    void start(int port) throws IOException {
        server = new ServerSocket(port);
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    new Thread(() -> handle(s), "http").start();
                } catch (IOException ignored) { }
            }
        }, "http-accept");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        try { if (server != null) server.close(); } catch (IOException ignored) { }
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setTcpNoDelay(true);
            InputStream in = sock.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1));
            String line = r.readLine();
            if (line == null) return;
            String wsKey = null;
            while (true) {
                String h = r.readLine();
                if (h == null || h.isEmpty()) break;
                if (h.regionMatches(true, 0, "Sec-WebSocket-Key:", 0, 18)) wsKey = h.substring(18).trim();
            }
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            String target = parts[1];
            int q = target.indexOf('?');
            String path = q < 0 ? target : target.substring(0, q);
            Map<String, String> args = query(q < 0 ? "" : target.substring(q + 1));
            OutputStream out = sock.getOutputStream();
            String pin = cmds.pin();
            if (!pin.isEmpty() && !path.startsWith("/fonts/") && !java.security.MessageDigest.isEqual(
                    pin.getBytes(StandardCharsets.UTF_8), String.valueOf(args.get("pin")).getBytes(StandardCharsets.UTF_8))) {
                send(out, 401, "text/html; charset=utf-8", PIN_PAGE);
                return;
            }
            String setting = String.valueOf(args.get("v"));
            if (path.equals("/cmd") && "set".equals(args.get("name"))
                    && (setting.startsWith("webPin:") || setting.startsWith("remoteControl:"))) {
                send(out, 403, "application/json", "{\"ok\":false,\"error\":\"PIN and remote control can only be changed on the phone.\"}".getBytes());
                return;
            }
            if (path.equals("/cmd") && !cmds.remoteControl()) {
                send(out, 403, "application/json", "{\"ok\":false,\"error\":\"Control from the browser is off on the phone.\"}".getBytes());
                return;
            }
            switch (path) {
                case "/": send(out, 200, "text/html; charset=utf-8", page); break;
                case "/stream": stream(out); break;
                case "/ws":
                    if (wsKey == null) send(out, 400, "text/plain", "websocket only".getBytes());
                    else radiometric(out, wsKey);
                    break;
                case "/history": {
                    double since = 0;
                    try { since = Double.parseDouble(String.valueOf(args.get("since"))); } catch (NumberFormatException ignored) { }
                    send(out, 200, "application/json", cmds.history(since).getBytes(StandardCharsets.UTF_8));
                    break;
                }
                case "/log.csv": {
                    java.io.File f = cmds.logFile();
                    if (f == null || !f.isFile()) { send(out, 404, "text/plain", "no monitoring log yet".getBytes()); break; }
                    byte[] b = new byte[(int) f.length()];
                    try (java.io.FileInputStream fin = new java.io.FileInputStream(f)) {
                        int n = 0;
                        while (n < b.length) { int r2 = fin.read(b, n, b.length - n); if (r2 < 0) break; n += r2; }
                    }
                    send(out, 200, "text/csv; charset=utf-8", b);
                    break;
                }
                case "/snapshot.jpg": {
                    th.wantJpeg();
                    th.freshBitmap(1500);
                    th.freshBitmap(1500);   // the first fresh frame may predate the JPEG request
                    byte[] j = th.jpeg;
                    if (j == null) send(out, 503, "text/plain", "no frame yet".getBytes());
                    else send(out, 200, "image/jpeg", j);
                    break;
                }
                case "/temps": send(out, 200, "application/json", cmds.statusJson().getBytes(StandardCharsets.UTF_8)); break;
                case "/raw": {
                    ByteBuffer b = ByteBuffer.allocate(th.raw.length * 2).order(ByteOrder.LITTLE_ENDIAN);
                    synchronized (th.frameLock) { b.asShortBuffer().put(th.raw); }
                    send(out, 200, "application/octet-stream", b.array());
                    break;
                }
                case "/cmd": {
                    String res;
                    int code = 200;
                    try {
                        res = cmds.run(args.get("name"), args.get("v"));
                    } catch (Exception e) {
                        code = 500;
                        res = "{\"ok\":false,\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
                    }
                    send(out, code, "application/json", res.getBytes(StandardCharsets.UTF_8));
                    break;
                }
                case "/debug/state": case "/debug/log": case "/debug/screen": case "/debug/ui":
                    debug(out, path, args);
                    break;
                default: {
                    byte[] a = path.startsWith("/fonts/") && path.endsWith(".woff2") && !path.contains("..")
                            ? cmds.asset(path.substring(1)) : null;
                    if (a != null) sendCached(out, "font/woff2", a);
                    else send(out, 404, "text/plain", "not found".getBytes());
                }
            }
        } catch (IOException ignored) {
            // client went away
        }
    }

    /** Development channel (DEV=1 builds only): state, log, real screenshots, UI actions. */
    private void debug(OutputStream out, String path, Map<String, String> args) throws IOException {
        if (!Debug.on) { send(out, 404, "text/plain", "not found".getBytes()); return; }
        try {
            switch (path) {
                case "/debug/state": send(out, 200, "application/json", Debug.state((Engine) cmds).getBytes(StandardCharsets.UTF_8)); break;
                case "/debug/log": send(out, 200, "text/plain; charset=utf-8", Debug.log().getBytes(StandardCharsets.UTF_8)); break;
                case "/debug/screen": send(out, 200, "image/png", Debug.screen()); break;
                default: {
                    Debug.Ui ui = Debug.ui;
                    if (ui == null) throw new IllegalStateException("no visible activity");
                    send(out, 200, "text/plain", ui.run(String.valueOf(args.get("do"))).getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) {
            send(out, 500, "text/plain", String.valueOf(e).getBytes(StandardCharsets.UTF_8));
        }
    }

    // --- radiometric WebSocket ----------------------------------------------------------
    // One binary message per published frame:
    //   u32 LE json length | status JSON | 768 B palette (RGB) | 512 B contrast curve (palette index per bin)
    //   | zlib-deflated u16 LE temperatures, (celsius + 100) * 100, 256x192 in sensor orientation.
    // The browser draws it with WebGL and reads the temperature under the cursor from it.

    private final Object wsLock = new Object();
    private long wsId = -1;
    private byte[] wsMsg;

    /** The message for the newest frame, built once and shared by all clients. */
    private byte[] wsMessage() {
        synchronized (wsLock) {
            long id = th.published;
            if (id == wsId && wsMsg != null) return wsMsg;
            byte[] json = cmds.statusJson().getBytes(StandardCharsets.UTF_8);
            byte[] u16 = new byte[Thermal.W * Thermal.H * 2];
            byte[] curve = new byte[Thermal.CURVE_BINS];
            synchronized (th.frameLock) {
                for (int i = 0; i < th.view.length; i++) {
                    int q = Math.round((th.view[i] + 100f) * 100f);
                    q = q < 0 ? 0 : q > 65535 ? 65535 : q;
                    u16[2 * i] = (byte) q;
                    u16[2 * i + 1] = (byte) (q >> 8);
                }
                for (int i = 0; i < curve.length; i++) curve[i] = (byte) Math.max(0, Math.min(255, Math.round(th.viewCurve[i])));
            }
            java.util.zip.Deflater d = new java.util.zip.Deflater(1);   // fastest level: the phone's CPU matters more
            d.setInput(u16);
            d.finish();
            java.io.ByteArrayOutputStream z = new java.io.ByteArrayOutputStream(u16.length / 2);
            byte[] buf = new byte[16384];
            while (!d.finished()) z.write(buf, 0, d.deflate(buf));
            d.end();
            int[] lut = th.lut();
            ByteBuffer m = ByteBuffer.allocate(4 + json.length + 768 + curve.length + z.size()).order(ByteOrder.LITTLE_ENDIAN);
            m.putInt(json.length).put(json);
            for (int c : lut) m.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c);
            m.put(curve).put(z.toByteArray());
            wsMsg = m.array();
            wsId = id;
            return wsMsg;
        }
    }

    private void radiometric(OutputStream out, String key) throws IOException {
        String accept;
        try {
            byte[] sha = java.security.MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1));
            accept = java.util.Base64.getEncoder().encodeToString(sha);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        long last = -1;
        while (true) {
            boolean idle;
            synchronized (th.frameLock) {   // never write to the socket while holding the frame lock
                if (th.published == last) {
                    try { th.frameLock.wait(1000); } catch (InterruptedException e) { return; }
                }
                idle = th.published == last;
            }
            if (idle) { out.write(new byte[]{(byte) 0x89, 0}); out.flush(); continue; }   // ping keeps the socket alive
            last = th.published;
            byte[] msg = wsMessage();
            // binary frame, server frames are not masked
            if (msg.length < 126) out.write(new byte[]{(byte) 0x82, (byte) msg.length});
            else if (msg.length < 65536) out.write(new byte[]{(byte) 0x82, 126, (byte) (msg.length >> 8), (byte) msg.length});
            else {
                byte[] h = new byte[10];
                h[0] = (byte) 0x82; h[1] = 127;
                for (int i = 0; i < 8; i++) h[2 + i] = (byte) ((long) msg.length >> (56 - 8 * i));
                out.write(h);
            }
            out.write(msg);
            out.flush();
        }
    }

    private void stream(OutputStream out) throws IOException {
        out.write(("HTTP/1.1 200 OK\r\nCache-Control: no-cache\r\nConnection: close\r\n"
                + "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n").getBytes());
        byte[] last = null;
        while (true) {
            byte[] j;
            th.wantJpeg();
            synchronized (th.frameLock) {
                while (th.jpeg == last) {
                    th.wantJpeg();
                    try { th.frameLock.wait(2000); } catch (InterruptedException e) { return; }
                }
                j = th.jpeg;
            }
            last = j;
            out.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + j.length + "\r\n\r\n").getBytes());
            out.write(j);
            out.write("\r\n".getBytes());
            out.flush();
        }
    }

    private static final byte[] PIN_PAGE = ("<!doctype html><meta name=viewport content='width=device-width'><title>Emberglass</title>"
            + "<body style='background:#0f0415;color:#f2ecf5;font:16px system-ui;display:grid;place-content:center;min-height:90vh'>"
            + "<form><label>PIN <input name=pin inputmode=numeric autofocus style='font:inherit;padding:8px'></label> "
            + "<button style='font:inherit;padding:8px 16px'>Open</button></form>").getBytes(StandardCharsets.UTF_8);

    private static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        String reason = code == 200 ? "OK" : code == 401 ? "Unauthorized" : code == 403 ? "Forbidden" : code == 404 ? "Not Found" : "Error";
        out.write(("HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n").getBytes());
        out.write(body);
        out.flush();
    }

    private static void sendCached(OutputStream out, String type, byte[] body) throws IOException {
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: max-age=86400\r\nConnection: close\r\n\r\n").getBytes());
        out.write(body);
        out.flush();
    }

    private static Map<String, String> query(String q) {
        Map<String, String> m = new HashMap<>();
        for (String kv : q.split("&")) {
            if (kv.isEmpty()) continue;
            int e = kv.indexOf('=');
            try {
                m.put(URLDecoder.decode(e < 0 ? kv : kv.substring(0, e), "UTF-8"),
                        e < 0 ? "" : URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
            } catch (java.io.UnsupportedEncodingException ignored) { }
        }
        return m;
    }
}
