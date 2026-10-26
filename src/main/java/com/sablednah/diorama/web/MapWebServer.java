package com.sablednah.diorama.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sablednah.diorama.BuildInfo;
import com.sablednah.diorama.DioramaConfig;
import com.sablednah.diorama.neoforge.DioramaService;
import com.sablednah.diorama.render.IsoRenderer;
import com.sablednah.diorama.render.TileStore;
import com.sablednah.diorama.render.View;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.level.ServerLevel;

/**
 * The map's web server: the page, its tiles, and two small JSON answers.
 *
 * <p>The JDK's own {@code HttpServer}, so Diorama ships no web framework. Every path a request can
 * name is matched against a fixed pattern before it touches the disk; nothing a browser sends is
 * ever joined onto a path as-is.</p>
 *
 * <ul>
 * <li>{@code /} and {@code /web/...} — the page, from the mod jar.</li>
 * <li>{@code /tiles/<world>/<view>/<level>/<x>_<y>.png} — from the save folder.</li>
 * <li>{@code /api/config} — worlds, tile geometry, and whether the map can draw at all.</li>
 * <li>{@code /api/markers} — every {@link com.sablednah.diorama.api.Markers} set, as last sampled.</li>
 * <li>{@code /api/players} — where everyone is, once a second.</li>
 * </ul>
 */
public final class MapWebServer {

    private static final Pattern TILE = Pattern.compile("/tiles/([a-z0-9_.\\-]+)/([a-z]{2})(-night)?/(\\d{1,2})/(-?\\d{1,9})_(-?\\d{1,9})\\.png");
    private static final Pattern STATIC = Pattern.compile("/web/([a-zA-Z0-9_\\-]+(?:/[a-zA-Z0-9_\\-]+)*\\.(?:js|css|png|html|txt))");
    private static final Map<String, String> TYPES = Map.of(
            "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "png", "image/png",
            "html", "text/html; charset=utf-8",
            "txt", "text/plain; charset=utf-8");

    private final DioramaService service;
    private final HttpServer http;
    private final ExecutorService executor;

    public MapWebServer(DioramaService service, String bind, int port) throws IOException {
        this.service = service;
        this.http = HttpServer.create(new InetSocketAddress(bind, port), 64);
        this.executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "Diorama-web");
            t.setDaemon(true);
            return t;
        });
        http.setExecutor(executor);
        http.createContext("/", this::handle);
    }

    public void start() {
        http.start();
    }

    public void stop() {
        http.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            if (!"GET".equals(ex.getRequestMethod()) && !"HEAD".equals(ex.getRequestMethod())) {
                send(ex, 405, "text/plain", "GET only".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String path = ex.getRequestURI().getPath();
            if (path.equals("/") || path.equals("/index.html")) {
                resource(ex, "index.html");
                return;
            }
            if (path.equals("/api/config")) {
                json(ex, config());
                return;
            }
            if (path.equals("/api/players")) {
                json(ex, players());
                return;
            }
            if (path.equals("/api/markers")) {
                json(ex, markers());
                return;
            }
            Matcher tile = TILE.matcher(path);
            if (tile.matches()) {
                tile(ex, tile.group(1), View.byId(tile.group(2)), tile.group(3) != null, Integer.parseInt(tile.group(4)),
                        Integer.parseInt(tile.group(5)), Integer.parseInt(tile.group(6)));
                return;
            }
            Matcher stat = STATIC.matcher(path);
            if (stat.matches() && !stat.group(1).contains("..")) {
                resource(ex, stat.group(1));
                return;
            }
            send(ex, 404, "text/plain", "Not here.".getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            send(ex, 500, "text/plain", "Something broke.".getBytes(StandardCharsets.UTF_8));
        }
    }

    private void tile(HttpExchange ex, String world, @org.jspecify.annotations.Nullable View view, boolean night, int level, int x, int y) throws IOException {
        if (view == null || level > TileStore.ZOOM_OUT_LEVELS) {
            send(ex, 404, "text/plain", new byte[0]);
            return;
        }
        for (var e : service.maps().entrySet()) {
            if (DioramaService.worldId(e.getKey()).equals(world)) {
                TileStore tiles = e.getValue().tiles(view, night);
                Path file = tiles == null ? null : tiles.file(level, x, y);
                if (file != null && Files.isRegularFile(file)) {
                    // Short cache: a tile can be redrawn any time somebody builds.
                    ex.getResponseHeaders().set("Cache-Control", "max-age=10");
                    send(ex, 200, "image/png", Files.readAllBytes(file));
                    return;
                }
                break;
            }
        }
        // No tile is the normal answer for most of the plane: say so cheaply.
        ex.getResponseHeaders().set("Cache-Control", "max-age=10");
        send(ex, 204, "image/png", null);
    }

    private void resource(HttpExchange ex, String name) throws IOException {
        try (InputStream in = MapWebServer.class.getResourceAsStream("/assets/diorama/web/" + name)) {
            if (in == null) {
                send(ex, 404, "text/plain", "Not here.".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String ext = name.substring(name.lastIndexOf('.') + 1);
            send(ex, 200, TYPES.getOrDefault(ext, "application/octet-stream"), in.readAllBytes());
        }
    }

    private JsonObject config() {
        JsonObject o = new JsonObject();
        o.addProperty("version", BuildInfo.version());
        o.addProperty("state", service.state().name());
        o.addProperty("detail", service.stateDetail());
        String progress = service.fullRenderProgress();
        if (progress != null) {
            o.addProperty("progress", progress);
        }
        o.addProperty("tileSize", IsoRenderer.TILE);
        o.addProperty("pixelsPerBlock", DioramaConfig.PIXELS_PER_BLOCK.get());
        o.addProperty("zoomOutLevels", TileStore.ZOOM_OUT_LEVELS);
        JsonArray views = new JsonArray();
        for (View v : DioramaConfig.views()) {
            views.add(v.id());
        }
        o.add("views", views);
        o.addProperty("night", DioramaConfig.NIGHT.get());
        JsonArray worlds = new JsonArray();
        for (ServerLevel level : service.server().getAllLevels()) {
            JsonObject w = new JsonObject();
            w.addProperty("id", DioramaService.worldId(level.dimension()));
            w.addProperty("name", level.dimension().identifier().toString());
            w.addProperty("seaLevel", level.getSeaLevel());
            var spawn = level.getRespawnData().globalPos();
            if (spawn.dimension() == level.dimension()) {
                w.addProperty("spawnX", spawn.pos().getX());
                w.addProperty("spawnY", spawn.pos().getY());
                w.addProperty("spawnZ", spawn.pos().getZ());
            }
            worlds.add(w);
        }
        o.add("worlds", worlds);
        return o;
    }

    private JsonObject players() {
        JsonObject o = new JsonObject();
        JsonArray list = new JsonArray();
        for (DioramaService.PlayerDot p : service.players()) {
            JsonObject j = new JsonObject();
            j.addProperty("name", p.name());
            j.addProperty("world", p.world());
            j.addProperty("x", p.x());
            j.addProperty("y", p.y());
            j.addProperty("z", p.z());
            list.add(j);
        }
        o.add("players", list);
        return o;
    }

    private JsonObject markers() {
        JsonArray layers = new JsonArray();
        for (DioramaService.MarkerLayer layer : service.markers()) {
            JsonObject l = new JsonObject();
            l.addProperty("id", layer.id());
            l.addProperty("label", layer.label());
            l.addProperty("shown", layer.shown());
            JsonArray list = new JsonArray();
            for (DioramaService.Placed p : layer.markers()) {
                var m = p.marker();
                JsonObject j = new JsonObject();
                j.addProperty("id", m.id());
                j.addProperty("world", p.world());
                j.addProperty("label", m.label());
                if (m.detail() != null) {
                    j.addProperty("detail", m.detail());
                }
                j.addProperty("x", m.x());
                j.addProperty("y", m.y());
                j.addProperty("z", m.z());
                j.addProperty("colour", String.format("#%06x", m.colour() & 0xFFFFFF));
                list.add(j);
            }
            l.add("markers", list);
            layers.add(l);
        }
        JsonObject o = new JsonObject();
        o.add("layers", layers);
        return o;
    }

    private static void json(HttpExchange ex, JsonObject body) throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, 200, "application/json; charset=utf-8", body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        if (type != null) {
            ex.getResponseHeaders().set("Content-Type", type);
        }
        boolean head = "HEAD".equals(ex.getRequestMethod());
        if (body == null || body.length == 0 || head) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }
}
