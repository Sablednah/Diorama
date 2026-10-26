package com.sablednah.diorama.integration;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.sablednah.diorama.api.Markers;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

/**
 * SableCraft Standards' warps and {@code /spawn}, as markers.
 *
 * <p>Warps are public by design — everybody may {@code /warp} and {@code /warps} lists them — so a
 * public map gives nothing away. Homes are absent on purpose, and Standards' API does not offer
 * them: a home is where somebody sleeps.</p>
 *
 * <p>Reads {@code api.places.Places}, which arrived in Standards 1.12.0.</p>
 */
public final class StandardsPlaces implements Markers.MarkerSet {

    private static final String NEEDS = "1.12.0";
    private static final int WARP = 0x6CC4FF, SPAWN = 0xF5D76E;

    private final MinecraftServer server;
    private final MethodHandle warps, spawn, name, dimension, x, y, z;
    private String label = "Warps", spawnLabel = "Spawn (/spawn)";

    private StandardsPlaces(MinecraftServer server, MethodHandle warps, MethodHandle spawn, MethodHandle name,
                            MethodHandle dimension, MethodHandle x, MethodHandle y, MethodHandle z) {
        this.server = server;
        this.warps = warps;
        this.spawn = spawn;
        this.name = name;
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static void install(MinecraftServer server, java.util.function.Function<String, String> lang) {
        if (!ModList.get().isLoaded("standards")) {
            return;
        }
        String version = ModList.get().getModContainerById("standards").map(c -> c.getModInfo().getVersion().toString()).orElse("?");
        Class<?> api = Linked.type("com.sablednah.standards.api.places.Places");
        Class<?> place = Linked.type("com.sablednah.standards.api.places.Places$Place");
        MethodHandle warps = api == null ? null : Linked.find(api, "warps", List.class, MinecraftServer.class);
        MethodHandle spawn = api == null ? null : Linked.find(api, "spawn", Optional.class, MinecraftServer.class);
        MethodHandle name = accessor(place, "name", String.class);
        MethodHandle dim = accessor(place, "dimension", ResourceKey.class);
        MethodHandle x = accessor(place, "x", double.class), y = accessor(place, "y", double.class), z = accessor(place, "z", double.class);
        if (warps == null || spawn == null || name == null || dim == null || x == null || y == null || z == null) {
            Linked.report("Standards " + version + " is installed but its warps need Standards " + NEEDS + " or newer; not on the map.");
            return;
        }
        StandardsPlaces set = new StandardsPlaces(server, warps, spawn, name, dim, x, y, z);
        set.label = lang.apply("marker.warps.layer");
        set.spawnLabel = lang.apply("marker.warps.spawn");
        Markers.register(set);
        Linked.report("Standards " + version + ": warps and /spawn on the map.");
    }

    private static MethodHandle accessor(Class<?> owner, String name, Class<?> type) {
        if (owner == null) {
            return null;
        }
        try {
            return MethodHandles.publicLookup().findVirtual(owner, name, MethodType.methodType(type));
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    @Override
    public String id() {
        return "standards:warps";
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public Collection<Markers.Marker> markers(ResourceKey<Level> level) {
        try {
            List<Markers.Marker> out = new ArrayList<>();
            for (Object p : (List<?>) warps.invoke(server)) {
                if (dimension.invoke(p) == level) {
                    String n = (String) name.invoke(p);
                    out.add(new Markers.Marker("warp:" + n, n, (double) x.invoke(p), (double) y.invoke(p), (double) z.invoke(p), WARP, "/warp " + n));
                }
            }
            Optional<?> s = (Optional<?>) spawn.invoke(server);
            if (s.isPresent() && dimension.invoke(s.get()) == level) {
                Object p = s.get();
                out.add(new Markers.Marker("spawn", spawnLabel, (double) x.invoke(p), (double) y.invoke(p), (double) z.invoke(p), SPAWN, "/spawn"));
            }
            return out;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("Standards Places call failed", t);
        }
    }
}
