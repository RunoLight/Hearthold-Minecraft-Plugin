package dev.hearthold.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.hearthold.config.HeartholdConfig;
import dev.hearthold.i18n.Lang;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Built-in web server for the portal. Login is via a one-time link from chat (/site), then a session cookie.
 * All data comes from prepared {@link WebData} snapshots; HTTP threads never touch the world.
 */
public final class WebServer {

    private static final String COOKIE = "hearthold_session";

    private record Pending(UUID player, String name, long expiresAt) { }

    private record Session(UUID player, String name, long expiresAt) { }

    private final Plugin plugin;
    private final WebData data;
    private final ScoutingService scouting;
    private final Supplier<HeartholdConfig> config;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> tokens = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, byte[]> assets = new ConcurrentHashMap<>();
    private HttpServer server;
    private ExecutorService executor;

    private AdminWebData admin;

    /** Admin panel data (visible to operators only). */
    public void setAdmin(AdminWebData admin) {
        this.admin = admin;
    }

    public WebServer(Plugin plugin, WebData data, ScoutingService scouting, Supplier<HeartholdConfig> config) {
        this.plugin = plugin;
        this.data = data;
        this.scouting = scouting;
        this.config = config;
    }

    public void start() throws IOException {
        HeartholdConfig.WebSettings w = config.get().web();
        for (String name : List.of("index.html", "app.js")) {
            try (InputStream in = plugin.getResource("web/" + name)) {
                if (in == null) {
                    throw new IOException("Missing resource web/" + name);
                }
                assets.put(name, in.readAllBytes());
            }
        }
        server = HttpServer.create(new InetSocketAddress(w.bind(), w.port()), 0);
        executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "Hearthold-Web");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        plugin.getLogger().info("Web portal: " + w.bind() + ":" + w.port() + " (" + w.publicUrl() + ")");
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /** One-time login link; called from the /site command. */
    public String issueLink(UUID player, String name) {
        long now = System.currentTimeMillis();
        tokens.values().removeIf(p -> p.expiresAt() < now);
        String token = randomToken();
        tokens.put(token, new Pending(player, name, now + config.get().web().tokenMinutes() * 60_000L));
        return config.get().web().publicUrl() + "/login?token=" + token;
    }

    private String randomToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // --- Routes ---

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            if (!ex.getRequestMethod().equals("GET")) {
                send(ex, 405, "text/plain", "Method not allowed".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String path = ex.getRequestURI().getPath();
            switch (path) {
                case "/", "/index.html" -> send(ex, 200, "text/html; charset=utf-8", index());
                case "/app.js" -> send(ex, 200, "text/javascript; charset=utf-8", assets.get("app.js"));
                case "/login" -> login(ex);
                case "/logout" -> logout(ex);
                case "/api/me", "/api/map", "/api/politics", "/api/admin" -> api(ex, path);
                default -> send(ex, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Web portal error", e);
        }
    }

    private void login(HttpExchange ex) throws IOException {
        String token = query(ex).get("token");
        Pending p = token == null ? null : tokens.remove(token);
        long now = System.currentTimeMillis();
        if (p == null || p.expiresAt() < now) {
            send(ex, 400, "text/html; charset=utf-8", ("<!doctype html><html lang=\"" + Lang.language()
                    + "\"><meta charset=utf-8><title>Hearthold</title><p>" + Lang.tr("web.login.invalid"))
                    .getBytes(StandardCharsets.UTF_8));
            return;
        }
        String id = randomToken();
        long ttl = config.get().web().sessionDays() * 86_400_000L;
        sessions.put(id, new Session(p.player(), p.name(), now + ttl));
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + id + "; Path=/; HttpOnly; SameSite=Lax; Max-Age="
                + ttl / 1000);
        ex.getResponseHeaders().add("Location", "/");
        send(ex, 302, "text/plain", new byte[0]);
    }

    private void logout(HttpExchange ex) throws IOException {
        String id = cookie(ex);
        if (id != null) {
            sessions.remove(id);
        }
        ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
        ex.getResponseHeaders().add("Location", "/");
        send(ex, 302, "text/plain", new byte[0]);
    }

    private void api(HttpExchange ex, String path) throws IOException {
        Session s = session(ex);
        WebData.Snapshot snap = data.snapshot();
        if (s == null) {
            json(ex, 401, Map.of("error", "login"));
            return;
        }
        if (snap == null) {
            json(ex, 503, Map.of("error", "starting"));
            return;
        }
        UUID clan = snap.membership().get(s.player());
        int tier = clan == null ? 0 : snap.scouting().getOrDefault(clan, 0);
        switch (path) {
            case "/api/me" -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("player", s.name());
                m.put("clan", clan == null ? null : snap.clanNames().get(clan));
                m.put("scouting", tier);
                m.put("admin", admin != null && admin.isOperator(s.player()));
                m.put("lang", Lang.language());
                json(ex, 200, m);
            }
            case "/api/admin" -> {
                if (admin == null || !admin.isOperator(s.player())) {
                    json(ex, 403, Map.of("error", "forbidden"));
                } else {
                    json(ex, 200, admin.data());
                }
            }
            case "/api/map" -> json(ex, 200, map(snap, clan, tier));
            default -> {
                Map<String, Object> m = new LinkedHashMap<>(snap.politics());
                m.put("routes", clan == null ? List.of() : snap.routes().getOrDefault(clan, List.of()));
                json(ex, 200, m);
            }
        }
    }

    /**
     * Map for the viewer. Own clan players are always visible (from the latest frame). Others only with
     * scouting ≥ 1, only outside their own bases and with the tier's delay; couriers with cargo need scouting ≥ 2.
     */
    private Map<String, Object> map(WebData.Snapshot snap, UUID viewerClan, int tier) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", snap.at());
        m.put("world", snap.world());
        m.put("bases", snap.bases());
        m.put("zones", snap.zones());
        m.put("regions", snap.regions());
        m.put("events", snap.events());
        int delay = scouting.delaySeconds(tier);
        m.put("scouting", tier);
        m.put("delaySeconds", delay);
        List<Object> players = new ArrayList<>();
        WebData.Frame latest = data.latest();
        if (latest != null && viewerClan != null) {
            for (WebData.Pos p : latest.players()) {
                if (viewerClan.equals(p.clan())) {
                    players.add(pos(snap, p, true, true));
                }
            }
        }
        if (delay >= 0) {
            WebData.Frame frame = data.frameAt(System.currentTimeMillis() - delay * 1000L);
            if (frame != null) {
                for (WebData.Pos p : frame.players()) {
                    if (p.outsideBase() && (viewerClan == null || !viewerClan.equals(p.clan()))) {
                        players.add(pos(snap, p, false, tier >= 2));
                    }
                }
            }
        }
        m.put("players", players);
        return m;
    }

    private static Map<String, Object> pos(WebData.Snapshot snap, WebData.Pos p, boolean own, boolean showCourier) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.name());
        m.put("clan", p.clan() == null ? null : snap.clanNames().get(p.clan()));
        m.put("x", Math.round(p.x()));
        m.put("z", Math.round(p.z()));
        m.put("own", own);
        m.put("courier", showCourier && p.courier());
        return m;
    }

    // --- Helpers ---

    /** index.html with the active plugin language in {@code <html lang>}, so the page script picks it up. */
    private byte[] index() {
        String html = new String(assets.get("index.html"), StandardCharsets.UTF_8);
        return html.replace("<html lang=\"en\">", "<html lang=\"" + Lang.language() + "\">")
                .getBytes(StandardCharsets.UTF_8);
    }

    private Session session(HttpExchange ex) {
        String id = cookie(ex);
        if (id == null) {
            return null;
        }
        Session s = sessions.get(id);
        if (s == null || s.expiresAt() < System.currentTimeMillis()) {
            sessions.remove(id);
            return null;
        }
        return s;
    }

    private static String cookie(HttpExchange ex) {
        for (String header : ex.getRequestHeaders().getOrDefault("Cookie", List.of())) {
            for (String part : header.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals(COOKIE)) {
                    return kv[1];
                }
            }
        }
        return null;
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> result = new LinkedHashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) {
            return result;
        }
        for (String part : q.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                result.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    private static void json(HttpExchange ex, int status, Object body) throws IOException {
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        send(ex, status, "application/json; charset=utf-8", Json.write(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        var h = ex.getResponseHeaders();
        h.add("Content-Type", type);
        h.add("X-Content-Type-Options", "nosniff");
        h.add("Referrer-Policy", "no-referrer");
        h.add("Content-Security-Policy",
                "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'");
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
