package com.sablednah.diorama.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Put things on the map: homes, warps, claims, quest givers — anything with a place.
 *
 * <p><b>Pull, not push.</b> A mod registers a {@link MarkerSet} once, and Diorama asks it for its
 * markers every few seconds, on the server thread. There is no add or remove to call, so there is
 * nothing to forget to take down: the series learned that from JourneyMap waypoints, where every
 * push minted a new pin and a captured banner stayed pinned where it no longer stood. Whatever the
 * set returns <em>now</em> is the whole truth.</p>
 *
 * <p>Registration <b>replaces by id</b>. Statics outlive a server: a single-player game that opens
 * a second world registers again in the same JVM, and an append-only list would draw every marker
 * twice.</p>
 *
 * <p>Optional for the caller: guard with {@code ModList.get().isLoaded("diorama")} and keep the
 * call in a class that is only loaded behind that guard.</p>
 */
public final class Markers {

    private static final Map<String, MarkerSet> SETS = new ConcurrentHashMap<>();

    private Markers() {}

    /** Add or replace the set with this id. Any thread. */
    public static void register(MarkerSet set) {
        SETS.put(set.id(), set);
    }

    public static void unregister(String id) {
        SETS.remove(id);
    }

    /** Everything registered, for Diorama itself. */
    public static Collection<MarkerSet> sets() {
        return List.copyOf(SETS.values());
    }

    /** One layer on the map, which a viewer can switch off. */
    public interface MarkerSet {
        /** Stable and namespaced, {@code standards:warps}. */
        String id();

        /** What the layer toggle says: "Warps". */
        String label();

        /** Shown when the page opens, or only when switched on. */
        default boolean shownByDefault() {
            return true;
        }

        /**
         * The markers in {@code level} right now. Called on the server thread every few seconds,
         * so read state, do not compute it; an exception drops this set for one refresh and is
         * logged once.
         */
        Collection<Marker> markers(ResourceKey<Level> level);
    }

    /**
     * One marker. {@code y} matters: an isometric map puts a point at a different place on screen
     * for every height, so a marker without its real height floats beside the thing it marks.
     *
     * @param id      unique within its set, so the page can keep a marker steady between refreshes
     * @param label   shown on hover, plain text (never parsed for colour codes or HTML)
     * @param colour  {@code 0xRRGGBB}
     * @param detail  an optional second line
     */
    public record Marker(String id, String label, double x, double y, double z, int colour, @Nullable String detail) {
        public Marker(String id, String label, double x, double y, double z, int colour) {
            this(id, label, x, y, z, colour, null);
        }
    }
}
