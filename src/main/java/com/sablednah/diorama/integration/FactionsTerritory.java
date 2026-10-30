package com.sablednah.diorama.integration;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.sablednah.diorama.api.Markers;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

/**
 * Factions ReForged's territory, one region per faction per world, in the faction's banner colour.
 *
 * <p>Shows what Factions' own JourneyMap layer shows every player — land, colour, name, tag,
 * peaceful, size — and nothing more. Not a faction's home, and not where its standard stands:
 * a public web map that pointed at the flag would be the best raid planner on the server.</p>
 *
 * <p>Reads {@code FactionsApi}'s map calls, which arrived in Factions 1.12.0.</p>
 */
public final class FactionsTerritory implements Markers.MarkerSet {

    private static final String NEEDS = "1.12.0";

    private final MinecraftServer server;
    private final MethodHandle claimsIn, nameOf, tagOf, isPeaceful, colourOf;

    private FactionsTerritory(MinecraftServer server, MethodHandle claimsIn, MethodHandle nameOf, MethodHandle tagOf,
                              MethodHandle isPeaceful, MethodHandle colourOf) {
        this.server = server;
        this.claimsIn = claimsIn;
        this.nameOf = nameOf;
        this.tagOf = tagOf;
        this.isPeaceful = isPeaceful;
        this.colourOf = colourOf;
    }

    /** Register if Factions is here and new enough; say so either way. */
    public static void install(MinecraftServer server, java.util.function.Function<String, String> lang) {
        if (!ModList.get().isLoaded("factions")) {
            return;
        }
        String version = ModList.get().getModContainerById("factions").map(c -> c.getModInfo().getVersion().toString()).orElse("?");
        Class<?> api = Linked.type("com.sablednah.factions.api.FactionsApi");
        MethodHandle claims = api == null ? null : Linked.find(api, "claimsIn", Map.class, MinecraftServer.class, ResourceKey.class);
        MethodHandle name = api == null ? null : Linked.find(api, "nameOf", Optional.class, MinecraftServer.class, String.class);
        MethodHandle tag = api == null ? null : Linked.find(api, "tagOf", String.class, MinecraftServer.class, String.class);
        MethodHandle peaceful = api == null ? null : Linked.find(api, "isPeaceful", boolean.class, MinecraftServer.class, String.class);
        MethodHandle colour = api == null ? null : Linked.find(api, "colourOf", int.class, MinecraftServer.class, String.class);
        if (claims == null || name == null || tag == null || peaceful == null || colour == null) {
            Linked.report("Factions " + version + " is installed but its territory needs Factions " + NEEDS + " or newer; not on the map.");
            return;
        }
        FactionsTerritory set = new FactionsTerritory(server, claims, name, tag, peaceful, colour);
        set.label = lang.apply("marker.factions.layer");
        set.detailChunks = lang.apply("marker.factions.chunks");
        set.detailPeaceful = lang.apply("marker.factions.peaceful");
        Markers.register(set);
        Linked.report("Factions " + version + ": territory on the map.");
    }

    private String label = "Factions", detailChunks = "{n} chunks", detailPeaceful = "peaceful";

    @Override
    public String id() {
        return "factions:territory";
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public Collection<Markers.Marker> markers(ResourceKey<Level> level) {
        return List.of();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Collection<Markers.Region> regions(ResourceKey<Level> level) {
        Map<String, List<ChunkPos>> claims;
        try {
            claims = (Map<String, List<ChunkPos>>) claimsIn.invoke(server, level);
            List<Markers.Region> out = new ArrayList<>();
            for (var e : claims.entrySet()) {
                String id = e.getKey();
                String name = ((Optional<String>) nameOf.invoke(server, id)).orElse(id);
                String tag = (String) tagOf.invoke(server, id);
                boolean peaceful = (boolean) isPeaceful.invoke(server, id);
                int colour = (int) colourOf.invoke(server, id);
                Set<Long> chunks = new HashSet<>();
                for (ChunkPos c : e.getValue()) {
                    chunks.add(c.toLong());
                }
                String detail = detailChunks.replace("{n}", String.valueOf(chunks.size()));
                if (peaceful) {
                    detail = detailPeaceful + " · " + detail;
                }
                out.add(new Markers.Region(id, tag.isEmpty() ? name : name + " [" + tag + "]", colour, detail, new Markers.Chunks(Set.copyOf(chunks))));
            }
            return out;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("FactionsApi call failed", t);
        }
    }
}
