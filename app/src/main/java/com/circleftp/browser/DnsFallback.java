package com.circleftp.browser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes the app independent of the phone's DNS settings.
 *
 * Problem: some DNS servers (e.g. an ISP resolver) cannot resolve the CircleFTP
 * hostnames, so every request fails with UnknownHostException unless the user
 * sets a Private DNS such as AdGuard.
 *
 * Solution:
 *   1. route(url): if the system resolver can resolve the host -> URL unchanged.
 *      If it can't, resolve the host ourselves over DNS-over-HTTPS (Cloudflare,
 *      Google, AdGuard, reached by IP / their own names, so no dependency on the
 *      broken resolver) and start a tiny loopback relay 127.0.0.1:PORT that
 *      forwards raw TCP to the real IP, rewriting the HTTP "Host:" header back
 *      to the original hostname. The returned URL points at the relay.
 *   2. unroute(url): maps a relay URL back to the original canonical URL
 *      (so watch-history keys stay stable).
 *
 * The relay is a plain byte pipe (after the request head), so streaming and
 * HTTP Range requests (seeking in MX Player) work.
 *
 * No Android classes are used here on purpose, so it can be unit-tested.
 */
public class DnsFallback {

    public interface Logger { void log(String level, String msg); }

    /** Pluggable for tests. */
    public interface Resolver { InetAddress resolve(String host) throws Exception; }

    private static final String[] DOH_ENDPOINTS = {
            "https://1.1.1.1/dns-query",
            "https://8.8.8.8/dns-query",
            "https://dns.adguard-dns.com/dns-query"
    };
    private static final long SYS_DNS_OK_MS = 2 * 60 * 1000;
    private static final long ROUTE_IP_TTL_MS = 5 * 60 * 1000;

    private static class Route {
        final String host;
        final int hostPort;
        final int localPort;
        volatile InetAddress ip;
        volatile long resolvedAt;
        Route(String host, int hostPort, int localPort, InetAddress ip) {
            this.host = host; this.hostPort = hostPort; this.localPort = localPort;
            this.ip = ip; this.resolvedAt = System.currentTimeMillis();
        }
    }

    private final Logger log;
    private volatile Resolver resolver = this::dohResolve;
    private final ConcurrentHashMap<String, Route> routes = new ConcurrentHashMap<>();      // "host:port" -> route
    private final ConcurrentHashMap<String, Long> sysDnsOkUntil = new ConcurrentHashMap<>(); // host -> expiry

    public DnsFallback(Logger log) { this.log = log; }

    /** Test hook. */
    void setResolver(Resolver r) { this.resolver = r; }

    // ─── Public API ───────────────────────────────────────────────────────────

    /** Returns a URL that can be connected to even when system DNS can't resolve the host. */
    public String route(String urlStr) throws Exception {
        URL u = new URL(urlStr);
        if (!"http".equalsIgnoreCase(u.getProtocol())) return urlStr;     // TLS can't be relayed by host
        String host = u.getHost();
        if (host.equals("localhost") || host.matches("[0-9.]+") || host.contains(":")) return urlStr; // IP literal
        int port = u.getPort() != -1 ? u.getPort() : 80;
        String key = host + ":" + port;

        Route r = routes.get(key);
        if (r == null) {
            Long okUntil = sysDnsOkUntil.get(host);
            if (okUntil != null && okUntil > System.currentTimeMillis()) return urlStr;
            try {
                InetAddress.getAllByName(host);
                sysDnsOkUntil.put(host, System.currentTimeMillis() + SYS_DNS_OK_MS);
                return urlStr;                                             // system DNS works
            } catch (UnknownHostException e) {
                log.log("E", "System DNS cannot resolve " + host + " -> switching to DNS-over-HTTPS + local relay");
            }
            r = createRoute(host, port);
        } else if (System.currentTimeMillis() - r.resolvedAt > ROUTE_IP_TTL_MS) {
            try {
                InetAddress fresh = resolver.resolve(host);
                r.ip = fresh; r.resolvedAt = System.currentTimeMillis();
                log.log("D", "DoH refreshed " + host + " -> " + fresh.getHostAddress());
            } catch (Exception e) {
                r.resolvedAt = System.currentTimeMillis();                // keep old IP, retry later
                log.log("E", "DoH refresh failed for " + host + " (keeping old IP): " + e);
            }
        }
        return new URL("http", "127.0.0.1", r.localPort, u.getFile()).toString();
    }

    /** Maps a relay URL (127.0.0.1:port/...) back to the original hostname URL. */
    public String unroute(String urlStr) {
        if (urlStr == null) return null;
        try {
            URL u = new URL(urlStr);
            if (!"127.0.0.1".equals(u.getHost())) return urlStr;
            for (Route r : routes.values()) {
                if (r.localPort == u.getPort()) {
                    return new URL("http", r.host, r.hostPort == 80 ? -1 : r.hostPort, u.getFile()).toString();
                }
            }
        } catch (Exception ignored) {}
        return urlStr;
    }

    // ─── Route / relay creation ───────────────────────────────────────────────

    private synchronized Route createRoute(String host, int port) throws Exception {
        String key = host + ":" + port;
        Route existing = routes.get(key);
        if (existing != null) return existing;

        InetAddress ip = resolver.resolve(host);
        log.log("D", "DoH resolved " + host + " -> " + ip.getHostAddress());

        final ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        final Route route = new Route(host, port, server.getLocalPort(), ip);
        routes.put(key, route);
        log.log("D", "Relay 127.0.0.1:" + route.localPort + " -> " + host + " (" + ip.getHostAddress() + ":" + port + ")");

        Thread acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    final Socket client = server.accept();
                    Thread t = new Thread(() -> handleClient(client, route), "relay-conn");
                    t.setDaemon(true);
                    t.start();
                } catch (Exception e) {
                    if (!server.isClosed()) log.log("E", "Relay accept error: " + e);
                }
            }
        }, "relay-accept-" + host);
        acceptor.setDaemon(true);
        acceptor.start();
        return route;
    }

    private void handleClient(final Socket client, final Route r) {
        Socket up = null;
        try {
            client.setSoTimeout(15000);
            InputStream cin = client.getInputStream();

            // Read the request head (up to \r\n\r\n).
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int win = 0, b;
            while ((b = cin.read()) != -1) {
                head.write(b);
                win = (win << 8) | b;
                if (win == 0x0D0A0D0A) break;
                if (head.size() > 64 * 1024) throw new Exception("request head too large");
            }
            if (head.size() == 0) { client.close(); return; }
            client.setSoTimeout(0);   // no idle timeout: a paused player must not be cut off

            String hostHeader = r.hostPort == 80 ? r.host : r.host + ":" + r.hostPort;
            String[] lines = new String(head.toByteArray(), StandardCharsets.ISO_8859_1).split("\r\n");
            StringBuilder out = new StringBuilder();
            out.append(lines[0]).append("\r\n");
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.isEmpty()) continue;
                String low = line.toLowerCase();
                if (low.startsWith("host:") || low.startsWith("connection:")
                        || low.startsWith("proxy-connection:") || low.startsWith("keep-alive:")) continue;
                out.append(line).append("\r\n");
            }
            out.append("Host: ").append(hostHeader).append("\r\n");
            out.append("Connection: close\r\n\r\n");   // one request per connection keeps the relay trivial

            up = new Socket();
            up.connect(new InetSocketAddress(r.ip, r.hostPort), 15000);
            up.setSoTimeout(60000);
            OutputStream uout = up.getOutputStream();
            uout.write(out.toString().getBytes(StandardCharsets.ISO_8859_1));
            uout.flush();

            final Socket upF = up;
            Thread c2u = new Thread(() -> pump(cin, uoutOf(upF), client, upF), "relay-c2u");
            c2u.setDaemon(true);
            c2u.start();
            pump(up.getInputStream(), client.getOutputStream(), client, up);   // server -> client (this thread)
        } catch (Exception e) {
            log.log("E", "Relay error for " + r.host + ": " + e);
            closeQuietly(client);
            closeQuietly(up);
        }
    }

    private static OutputStream uoutOf(Socket s) {
        try { return s.getOutputStream(); } catch (Exception e) { return null; }
    }

    private static void pump(InputStream in, OutputStream out, Socket a, Socket b) {
        byte[] buf = new byte[32 * 1024];
        try {
            if (in == null || out == null) return;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (Exception ignored) {
            // normal when the player seeks / closes the connection
        } finally {
            closeQuietly(a);
            closeQuietly(b);
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) try { s.close(); } catch (Exception ignored) {}
    }

    // ─── DNS-over-HTTPS (RFC 8484 wire format) ────────────────────────────────

    private InetAddress dohResolve(String host) throws Exception {
        byte[] query = buildDnsQuery(host);
        StringBuilder errors = new StringBuilder();
        for (String endpoint : DOH_ENDPOINTS) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.setRequestProperty("Content-Type", "application/dns-message");
                c.setRequestProperty("Accept", "application/dns-message");
                try (OutputStream o = c.getOutputStream()) { o.write(query); }
                int code = c.getResponseCode();
                if (code != 200) throw new Exception("HTTP " + code);
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] chunk = new byte[2048];
                    int n;
                    while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
                }
                c.disconnect();
                return parseDnsA(buf.toByteArray());
            } catch (Exception e) {
                errors.append(endpoint).append(" -> ").append(e).append("; ");
                log.log("E", "DoH " + endpoint + " failed for " + host + ": " + e);
            }
        }
        throw new Exception("DoH failed for " + host + ": " + errors);
    }

    static byte[] buildDnsQuery(String host) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0x12); o.write(0x34);          // id
        o.write(0x01); o.write(0x00);          // flags: recursion desired
        o.write(0); o.write(1);                // 1 question
        for (int i = 0; i < 6; i++) o.write(0); // answer/authority/additional = 0
        for (String label : host.split("\\.")) {
            byte[] lb = label.getBytes(StandardCharsets.US_ASCII);
            o.write(lb.length);
            o.write(lb, 0, lb.length);
        }
        o.write(0);
        o.write(0); o.write(1);                // type A
        o.write(0); o.write(1);                // class IN
        return o.toByteArray();
    }

    static InetAddress parseDnsA(byte[] r) throws Exception {
        if (r.length < 12) throw new Exception("short DNS response");
        int rcode = r[3] & 0x0F;
        if (rcode != 0) throw new Exception("DNS rcode " + rcode + (rcode == 3 ? " (NXDOMAIN)" : ""));
        int qd = u16(r, 4), an = u16(r, 6);
        int p = 12;
        for (int i = 0; i < qd; i++) { p = skipName(r, p); p += 4; }
        for (int i = 0; i < an; i++) {
            p = skipName(r, p);
            int type = u16(r, p);
            int rdlen = u16(r, p + 8);
            p += 10;
            if (type == 1 && rdlen == 4) {
                return InetAddress.getByAddress(new byte[]{r[p], r[p + 1], r[p + 2], r[p + 3]});
            }
            p += rdlen;
        }
        throw new Exception("no A record in DNS answer");
    }

    private static int u16(byte[] r, int p) { return ((r[p] & 0xFF) << 8) | (r[p + 1] & 0xFF); }

    private static int skipName(byte[] r, int p) {
        while (true) {
            int len = r[p] & 0xFF;
            if (len == 0) return p + 1;
            if ((len & 0xC0) == 0xC0) return p + 2;
            p += len + 1;
        }
    }
}
