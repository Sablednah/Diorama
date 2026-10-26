package com.sablednah.diorama.neoforge;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sablednah.diorama.Diorama;
import com.sablednah.diorama.api.Markers;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Regions the server's owner marks out — towns, parks, the spawn district — the way Dynmap's area
 * markers worked: corners laid where you stand, joined into a polygon.
 *
 * <p>Two sources, one format, one layer:</p>
 * <ul>
 *   <li><b>Datapacks</b>: {@code data/<namespace>/diorama/regions/<name>.json}. Versioned with the
 *       world, shipped by a modpack, reloaded by {@code /reload}. Read-only here.</li>
 *   <li><b>{@code /diorama region}</b>: corners added in game, saved to
 *       {@code <world>/diorama/regions.json} — the same format, so it can be edited by hand or
 *       moved into a datapack once it is right.</li>
 * </ul>
 *
 * <pre>{@code
 * { "label": "Old Town", "dimension": "minecraft:overworld", "colour": "#e8a33d",
 *   "detail": "Founded by the first three players",
 *   "corners": [[-40, -80], [40, -80], [40, 0], [-40, 0]],
 *   "y": 72 }                       // optional: flat at y 72 rather than on the ground
 * }</pre>
 *
 * <p>{@code "chunks": [[cx, cz], …]} may stand in for {@code corners}, for land that is naturally
 * chunk-shaped. Factions' territory reaches the map through the same seam, as its own layer.</p>
 */
final class OwnerRegions implements Markers.MarkerSet {

    static final OwnerRegions INSTANCE = new OwnerRegions();
    static final int DEFAULT_COLOUR = 0xE8A33D;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Made in game; this is the copy {@code regions.json} is written from. */
    private final Map<String, Owned> commands = new LinkedHashMap<>();
    /** From datapacks, replaced whole on every reload. */
    private volatile Map<String, Owned> datapacks = Map.of();
    private @Nullable Path file;

    /** One region as an owner wrote it. Mutable only through the command methods, on the server thread. */
    static final class Owned {
        String label;
        ResourceKey<Level> dimension;
        int colour = DEFAULT_COLOUR;
        @Nullable String detail;
        final List<double[]> corners = new ArrayList<>();
        final java.util.Set<Long> chunks = new java.util.LinkedHashSet<>();
        @Nullable Double y;

        Owned(String label, ResourceKey<Level> dimension) {
            this.label = label;
            this.dimension = dimension;
        }
    }

    private OwnerRegions() {}

    @Override
    public String id() {
        return "diorama:regions";
    }

    @Override
    public String label() {
        return Lang.text("marker.regions.layer");
    }

    @Override
    public Collection<Markers.Marker> markers(ResourceKey<Level> level) {
        return List.of();
    }

    @Override
    public Collection<Markers.Region> regions(ResourceKey<Level> level) {
        List<Markers.Region> out = new ArrayList<>();
        datapacks.forEach((id, r) -> add(out, id, r, level));
        synchronized (commands) {
            commands.forEach((id, r) -> add(out, id, r, level));
        }
        return out;
    }

    private static void add(List<Markers.Region> out, String id, Owned r, ResourceKey<Level> level) {
        if (r.dimension != level) {
            return;
        }
        Markers.Shape shape;
        if (!r.chunks.isEmpty()) {
            shape = new Markers.Chunks(java.util.Set.copyOf(r.chunks));
        } else if (r.corners.size() >= 3) {
            shape = new Markers.Polygon(List.copyOf(r.corners), r.y);
        } else {
            return; // fewer than three corners is a line, and a region still being laid
        }
        out.add(new Markers.Region(id, r.label, r.colour, r.detail, shape));
    }

    // ---------------------------------------------------------------- loading

    /** Server thread: read the world's own file and every datapack's regions. */
    void load(MinecraftServer server, Path worldRoot) {
        file = worldRoot.resolve("diorama").resolve("regions.json");
        synchronized (commands) {
            commands.clear();
            if (Files.isRegularFile(file)) {
                try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    JsonObject all = JsonParser.parseReader(in).getAsJsonObject();
                    for (var e : all.entrySet()) {
                        Owned r = parse(e.getKey(), e.getValue());
                        if (r != null) {
                            commands.put(e.getKey(), r);
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    Diorama.LOGGER.warn("Diorama: {} could not be read ({}); its regions are not on the map", file, e.toString());
                }
            }
        }
        reloadDatapacks(server);
    }

    /** Server thread, on start and on {@code /reload}. */
    void reloadDatapacks(MinecraftServer server) {
        Map<String, Owned> found = new LinkedHashMap<>();
        var resources = server.getResourceManager().listResources("diorama/regions", id -> id.getPath().endsWith(".json"));
        for (var e : resources.entrySet()) {
            Identifier rid = e.getKey();
            String path = rid.getPath();
            String id = rid.getNamespace() + ":" + path.substring("diorama/regions/".length(), path.length() - ".json".length());
            try (Reader in = e.getValue().openAsReader()) {
                Owned r = parse(id, JsonParser.parseReader(in));
                if (r != null) {
                    found.put(id, r);
                }
            } catch (IOException | RuntimeException ex) {
                Diorama.LOGGER.warn("Diorama: region {} in a datapack could not be read: {}", id, ex.toString());
            }
        }
        datapacks = Map.copyOf(found);
        if (!found.isEmpty()) {
            Diorama.LOGGER.info("Diorama: {} region(s) from datapacks", found.size());
        }
    }

    /** One region from JSON; {@code null}, logged, when it cannot be made sense of. */
    private static @Nullable Owned parse(String id, JsonElement json) {
        JsonObject o = json.getAsJsonObject();
        String dim = o.has("dimension") ? o.get("dimension").getAsString() : "minecraft:overworld";
        Identifier dimId = Identifier.tryParse(dim);
        if (dimId == null) {
            Diorama.LOGGER.warn("Diorama: region {} names a dimension that cannot exist: {}", id, dim);
            return null;
        }
        Owned r = new Owned(o.has("label") ? o.get("label").getAsString() : id, ResourceKey.create(Registries.DIMENSION, dimId));
        if (o.has("colour")) {
            Integer c = colour(o.get("colour").getAsString());
            if (c != null) {
                r.colour = c;
            }
        }
        if (o.has("detail")) {
            r.detail = o.get("detail").getAsString();
        }
        if (o.has("y") && !o.get("y").isJsonNull()) {
            r.y = o.get("y").getAsDouble();
        }
        if (o.has("corners")) {
            for (JsonElement c : o.getAsJsonArray("corners")) {
                JsonArray xz = c.getAsJsonArray();
                r.corners.add(new double[] {xz.get(0).getAsDouble(), xz.get(1).getAsDouble()});
            }
        }
        if (o.has("chunks")) {
            for (JsonElement c : o.getAsJsonArray("chunks")) {
                JsonArray xz = c.getAsJsonArray();
                r.chunks.add(ChunkPos.pack(xz.get(0).getAsInt(), xz.get(1).getAsInt()));
            }
        }
        return r;
    }

    /** {@code #e8a33d}, {@code e8a33d}, or {@code 0xe8a33d}; null if it is none of those. */
    static @Nullable Integer colour(String s) {
        String t = s.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.startsWith("#")) {
            t = t.substring(1);
        } else if (t.startsWith("0x")) {
            t = t.substring(2);
        }
        if (!t.matches("[0-9a-f]{6}")) {
            return null;
        }
        return Integer.parseInt(t, 16);
    }

    // ---------------------------------------------------------------- commands (server thread)

    boolean exists(String id) {
        synchronized (commands) {
            return commands.containsKey(id);
        }
    }

    @Nullable Owned get(String id) {
        synchronized (commands) {
            return commands.get(id);
        }
    }

    List<String> ids() {
        synchronized (commands) {
            return List.copyOf(commands.keySet());
        }
    }

    Map<String, Owned> datapackRegions() {
        return datapacks;
    }

    void put(String id, Owned r) {
        synchronized (commands) {
            commands.put(id, r);
        }
        save();
    }

    boolean remove(String id) {
        boolean was;
        synchronized (commands) {
            was = commands.remove(id) != null;
        }
        if (was) {
            save();
        }
        return was;
    }

    /** After any edit. Written whole, through a temporary file, so a crash mid-write loses nothing. */
    void save() {
        if (file == null) {
            return;
        }
        JsonObject all = new JsonObject();
        synchronized (commands) {
            commands.forEach((id, r) -> all.add(id, toJson(r)));
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("regions.json.tmp");
            Files.writeString(tmp, GSON.toJson(all), StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Diorama.LOGGER.warn("Diorama: could not save {}: {}", file, e.toString());
        }
    }

    private static JsonObject toJson(Owned r) {
        JsonObject o = new JsonObject();
        o.addProperty("label", r.label);
        o.addProperty("dimension", r.dimension.identifier().toString());
        o.addProperty("colour", String.format("#%06x", r.colour));
        if (r.detail != null) {
            o.addProperty("detail", r.detail);
        }
        if (r.y != null) {
            o.addProperty("y", r.y);
        }
        JsonArray corners = new JsonArray();
        for (double[] c : r.corners) {
            JsonArray xz = new JsonArray();
            xz.add(c[0]);
            xz.add(c[1]);
            corners.add(xz);
        }
        o.add("corners", corners);
        if (!r.chunks.isEmpty()) {
            JsonArray chunks = new JsonArray();
            for (long k : r.chunks) {
                JsonArray xz = new JsonArray();
                xz.add(ChunkPos.getX(k));
                xz.add(ChunkPos.getZ(k));
                chunks.add(xz);
            }
            o.add("chunks", chunks);
        }
        return o;
    }
}
