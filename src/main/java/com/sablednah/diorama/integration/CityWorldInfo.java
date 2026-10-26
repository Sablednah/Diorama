package com.sablednah.diorama.integration;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sablednah.diorama.Diorama;
import com.sablednah.diorama.api.Markers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import org.jspecify.annotations.Nullable;

/**
 * CityWorld's plan, through its existing public API — nothing was added on CityWorld's side.
 *
 * <ul>
 *   <li><b>What is here</b>, for the info bar: {@code CityWorldAPI.lotAt} names the district, the
 *       building ("Courthouse"), the street a road is on, a shop or a schematic. It is derived from
 *       the seed, so it answers for ground nobody has generated.</li>
 *   <li><b>Landmarks</b>, as a layer: {@code MapMarkers.addListener} announces each named place as
 *       it is planned — the same feed CityWorld's JourneyMap waypoints come from. The announcement
 *       happens once, at planning, so what has been heard is kept in
 *       {@code <world>/diorama/cityworld-landmarks.json}; without that the layer would empty on
 *       every restart while the landmarks stood there.</li>
 * </ul>
 *
 * <p>No compile dependency: the API is reached by method handles, and the listener interface is
 * implemented with a {@link Proxy}.</p>
 */
public final class CityWorldInfo implements Markers.MarkerSet {

    private static final int LANDMARK = 0xC792EA;
    private static final Gson GSON = new Gson();
    private static volatile @Nullable CityWorldInfo current;

    private final MinecraftServer server;
    private final MethodHandle lotAt, family, interior, schematic, shop, isRoad, lotClass, describe;
    private final Map<String, Landmark> landmarks = new ConcurrentHashMap<>();
    private final Path file;
    private volatile boolean dirty;
    private String label = "CityWorld landmarks";

    record Landmark(ResourceKey<Level> dimension, String kind, String title, int x, int y, int z) {}

    private CityWorldInfo(MinecraftServer server, Path worldRoot, MethodHandle lotAt, MethodHandle family, MethodHandle interior,
                          MethodHandle schematic, MethodHandle shop, MethodHandle isRoad, MethodHandle lotClass, MethodHandle describe) {
        this.server = server;
        this.file = worldRoot.resolve("diorama").resolve("cityworld-landmarks.json");
        this.lotAt = lotAt;
        this.family = family;
        this.interior = interior;
        this.schematic = schematic;
        this.shop = shop;
        this.isRoad = isRoad;
        this.lotClass = lotClass;
        this.describe = describe;
    }

    /** The live instance, for the info bar; null without CityWorld. */
    public static @Nullable CityWorldInfo current() {
        return current;
    }

    public static void install(MinecraftServer server, Path worldRoot, java.util.function.Function<String, String> lang) {
        current = null;
        if (!ModList.get().isLoaded("cityworld")) {
            return;
        }
        String version = ModList.get().getModContainerById("cityworld").map(c -> c.getModInfo().getVersion().toString()).orElse("?");
        Class<?> api = Linked.type("me.daddychurchill.CityWorld.api.CityWorldAPI");
        Class<?> info = Linked.type("me.daddychurchill.CityWorld.api.LotInfo");
        Class<?> shopType = Linked.type("me.daddychurchill.CityWorld.api.ShopType");
        Class<?> markers = Linked.type("me.daddychurchill.CityWorld.api.MapMarkers");
        Class<?> listener = Linked.type("me.daddychurchill.CityWorld.api.MapMarkers$Listener");
        MethodHandle lotAt = api == null ? null : Linked.find(api, "lotAt", Optional.class, ServerLevel.class, BlockPos.class);
        MethodHandle family = virtual(info, "contextFamily", null);
        MethodHandle interior = virtual(info, "interior", String.class);
        MethodHandle schematic = virtual(info, "schematicName", String.class);
        MethodHandle shop = virtual(info, "shop", shopType);
        MethodHandle isRoad = virtual(info, "isRoad", boolean.class);
        MethodHandle lotClass = virtual(info, "lotClass", String.class);
        MethodHandle describe = virtual(shopType, "describe", String.class);
        if (lotAt == null || family == null || interior == null || schematic == null || shop == null || isRoad == null
                || lotClass == null || describe == null) {
            Linked.report("CityWorld " + version + " is installed but its API is not the one Diorama reads; no lot info.");
            return;
        }
        CityWorldInfo cw = new CityWorldInfo(server, worldRoot, lotAt, family, interior, schematic, shop, isRoad, lotClass, describe);
        cw.label = lang.apply("marker.cityworld.layer");
        cw.load();
        current = cw;
        Markers.register(cw);
        boolean listening = cw.listen(markers, listener);
        Linked.report("CityWorld " + version + ": lot info in the info bar" + (listening ? ", landmarks on the map (" + cw.landmarks.size() + " known)." : "; landmarks unavailable."));
    }

    private static @Nullable MethodHandle virtual(@Nullable Class<?> owner, String name, @Nullable Class<?> type) {
        if (owner == null) {
            return null;
        }
        try {
            var m = owner.getMethod(name);
            if (type != null && m.getReturnType() != type) {
                return null;
            }
            return MethodHandles.publicLookup().unreflect(m);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- landmarks

    /**
     * A proxy, registered once per JVM: CityWorld's listener list has no remove, and a second world
     * in the same game must not add a second listener. It forwards to whichever instance is current.
     */
    private static volatile boolean registered;

    private boolean listen(@Nullable Class<?> markers, @Nullable Class<?> listener) {
        if (markers == null || listener == null) {
            return false;
        }
        if (registered) {
            return true;
        }
        try {
            Class<?> landmarkType = Class.forName("me.daddychurchill.CityWorld.api.MapMarkers$Landmark");
            MethodHandle dim = MethodHandles.publicLookup().unreflect(landmarkType.getMethod("dimension"));
            MethodHandle kind = MethodHandles.publicLookup().unreflect(landmarkType.getMethod("kind"));
            MethodHandle title = MethodHandles.publicLookup().unreflect(landmarkType.getMethod("title"));
            MethodHandle x = MethodHandles.publicLookup().unreflect(landmarkType.getMethod("x"));
            MethodHandle y = MethodHandles.publicLookup().unreflect(landmarkType.getMethod("y"));
            MethodHandle z = MethodHandles.publicLookup().unreflect(landmarkType.getMethod("z"));
            Object proxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[] {listener}, (self, method, args) -> {
                switch (method.getName()) {
                    case "onLandmark" -> {
                        CityWorldInfo cw = current;
                        Object l = args[0];
                        if (cw != null) {
                            @SuppressWarnings("unchecked")
                            var d = (ResourceKey<Level>) dim.invoke(l);
                            cw.heard(new Landmark(d, (String) kind.invoke(l), (String) title.invoke(l),
                                    (int) x.invoke(l), (int) y.invoke(l), (int) z.invoke(l)));
                        }
                        return null;
                    }
                    case "drawsCityPlan", "equals" -> {
                        return method.getName().equals("equals") ? self == args[0] : false;
                    }
                    case "hashCode" -> {
                        return System.identityHashCode(self);
                    }
                    case "toString" -> {
                        return "Diorama CityWorld landmarks";
                    }
                    default -> {
                        return null;
                    }
                }
            });
            markers.getMethod("addListener", listener).invoke(null, proxy);
            registered = true;
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Diorama.LOGGER.warn("Diorama: could not listen for CityWorld landmarks: {}", e.toString());
            return false;
        }
    }

    /** Worldgen threads. Cheap: a map put; the file is written by {@link #saveIfChanged}. */
    void heard(Landmark l) {
        String key = l.dimension().identifier() + "|" + l.x() + "|" + l.z() + "|" + l.title();
        if (landmarks.putIfAbsent(key, l) == null) {
            dirty = true;
        }
    }

    /** Coordinator thread, with the marker sampling. */
    public void saveIfChanged() {
        if (!dirty) {
            return;
        }
        dirty = false;
        JsonArray all = new JsonArray();
        for (Landmark l : landmarks.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("dimension", l.dimension().identifier().toString());
            o.addProperty("kind", l.kind());
            o.addProperty("title", l.title());
            o.addProperty("x", l.x());
            o.addProperty("y", l.y());
            o.addProperty("z", l.z());
            all.add(o);
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(all), StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            dirty = true;
            Diorama.LOGGER.warn("Diorama: could not save {}: {}", file, e.toString());
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            for (var e : JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray()) {
                JsonObject o = e.getAsJsonObject();
                Identifier dim = Identifier.tryParse(o.get("dimension").getAsString());
                if (dim != null) {
                    heard(new Landmark(ResourceKey.create(Registries.DIMENSION, dim), o.get("kind").getAsString(),
                            o.get("title").getAsString(), o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt()));
                }
            }
            dirty = false;
        } catch (IOException | RuntimeException e) {
            Diorama.LOGGER.warn("Diorama: {} could not be read ({}); landmarks will be relearned as they are planned", file, e.toString());
        }
    }

    @Override
    public String id() {
        return "cityworld:landmarks";
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public Collection<Markers.Marker> markers(ResourceKey<Level> level) {
        List<Markers.Marker> out = new ArrayList<>();
        for (Landmark l : landmarks.values()) {
            if (l.dimension() == level) {
                // y is street level, which is where the lot's ground is: on an iso map, the right place.
                out.add(new Markers.Marker(l.kind() + ":" + l.x() + "," + l.z(), l.title(), l.x() + 0.5, l.y(), l.z() + 0.5, LANDMARK,
                        pretty(l.kind())));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- the info bar

    /**
     * What CityWorld planned at this block, as one line ("Midrise · Courthouse"), or null where it
     * planned nothing worth saying (wilderness, or not a CityWorld world).
     *
     * <p>Called straight from the web thread. {@code CityWorldAPI} says "server thread", but
     * {@code MapMarkers} documents the same lookup as deterministic, thread-safe and cached for map
     * mods — and hopping to the server thread does not work: an empty dedicated server pauses its
     * tick, so every request timed out, and an empty server is exactly when people browse a map.</p>
     */
    public @Nullable String describe(ResourceKey<Level> dimension, int x, int y, int z) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            return null;
        }
        return describeNow(level, new BlockPos(x, y, z));
    }

    private @Nullable String describeNow(ServerLevel level, BlockPos pos) {
        try {
            Optional<?> lot = (Optional<?>) lotAt.invoke(level, pos);
            if (lot.isEmpty()) {
                return null;
            }
            Object i = lot.get();
            String fam = String.valueOf(family.invoke(i));
            boolean road = (boolean) isRoad.invoke(i);
            String what = (String) interior.invoke(i);
            if (what == null) {
                what = (String) schematic.invoke(i);
            }
            if (what == null) {
                Object s = shop.invoke(i);
                what = s == null ? null : (String) describe.invoke(s);
            }
            boolean wild = fam.equals("NATURE") || fam.equals("OUTLAND") || fam.equals("ASTRAL");
            if (what == null && !road && !wild) {
                what = splitCamel(((String) lotClass.invoke(i)).replaceAll("Lot$", ""));
            }
            String district = wild ? null : pretty(fam);
            if (district == null) {
                return what;
            }
            return what == null || what.isBlank() ? district : district + " · " + what;
        } catch (Throwable t) {
            complain(t);
            return null;
        }
    }

    private volatile boolean complained;

    /** Once: a lookup that fails every time must say so, or the info bar just looks empty. */
    private void complain(Throwable t) {
        if (!complained) {
            complained = true;
            Diorama.LOGGER.warn("Diorama: asking CityWorld what is at a block failed; the info bar will lack lot names", t);
        }
    }

    private static String pretty(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.toLowerCase(Locale.ROOT).split("[_ ]")) {
            if (!w.isEmpty()) {
                sb.append(sb.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return sb.toString();
    }

    /** {@code StoreBuilding} → {@code Store Building}. */
    private static String splitCamel(String s) {
        return s.replaceAll("(?<=[a-z])(?=[A-Z])", " ");
    }
}
