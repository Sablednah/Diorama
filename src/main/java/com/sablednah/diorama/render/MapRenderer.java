package com.sablednah.diorama.render;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.sablednah.diorama.Diorama;
import com.sablednah.diorama.world.ChunkSnap;
import com.sablednah.diorama.world.ChunkStore;
import net.minecraft.world.level.ChunkPos;

/**
 * One dimension's map: which tiles are stale, and making them fresh.
 *
 * <h2>From chunks to tiles</h2>
 *
 * <p>A chunk's blocks can only appear inside one screen-space box: its sixteen columns span a
 * fixed range of {@code x − z} and {@code x + z}, and its height span stretches that box
 * vertically. Every tile that box touches is one this chunk can change, so a changed chunk marks
 * exactly those tiles dirty. The height span runs from the chunk's lowest column top to its highest
 * — a ray cannot reach a block below every column's surface without passing through the surface
 * first.</p>
 *
 * <p>The same bookkeeping answers where a ray must start: a tile's {@code yTop} is the highest top
 * of any chunk that marked it, since any block the tile can show belongs to such a chunk.</p>
 *
 * <p>Each {@link View} is its own set of tiles with its own dirty list and heights; a changed chunk
 * marks all of them. The chunk's box is computed in the view's rotated coordinates, so the same
 * arithmetic serves every view.</p>
 */
public final class MapRenderer {

    private final ChunkStore store;
    private final Map<View, Layer> layers = new EnumMap<>(View.class);
    private final int pixelsPerBlock;

    /** One view's tiles. */
    private static final class Layer {
        final View view;
        final TileStore tiles;
        /** Level-0 tiles waiting to be drawn. */
        final Set<Long> dirty = ConcurrentHashMap.newKeySet();
        /**
         * The highest block top each tile has ever been told about. It only rises: a tile is
         * marked by one changed chunk at a time, and starting its rays at that one chunk's height
         * would cut the top off a taller neighbour that shares the tile.
         */
        final Map<Long, Integer> tops = new ConcurrentHashMap<>();

        Layer(View view, TileStore tiles) {
            this.view = view;
            this.tiles = tiles;
        }
    }
    /**
     * Whether {@link #tops} has heard from every chunk — true after a full render. Until then a
     * tile's rays start at the build limit, which costs a few skipped empty sections per ray and
     * cannot miss anything.
     */
    private volatile boolean surveyed;
    /** The last height span marked for each chunk, so a building torn down clears its old tiles too. */
    private final Map<Long, int[]> extents = new ConcurrentHashMap<>();

    private final AtomicInteger rendered = new AtomicInteger();

    /** {@code root} holds one folder per view: {@code <root>/se/0/…}. */
    public MapRenderer(ChunkStore store, Path root, Set<View> views, int pixelsPerBlock) {
        this.store = store;
        for (View v : views) {
            layers.put(v, new Layer(v, new TileStore(root.resolve(v.id()))));
        }
        this.pixelsPerBlock = pixelsPerBlock;
    }

    public ChunkStore store() {
        return store;
    }

    public Set<View> views() {
        return layers.keySet();
    }

    /** The tiles for {@code view}, or null when that view is not drawn. */
    public @org.jspecify.annotations.Nullable TileStore tiles(View view) {
        Layer l = layers.get(view);
        return l == null ? null : l.tiles;
    }

    /** Whether any drawn view has never written a tile — a new world, or a view just switched on. */
    public boolean missingTiles() {
        for (Layer l : layers.values()) {
            if (!java.nio.file.Files.isDirectory(l.tiles.root())) {
                return true;
            }
        }
        return false;
    }

    public int pendingTiles() {
        int n = 0;
        for (Layer l : layers.values()) {
            n += l.dirty.size();
        }
        return n;
    }

    public void markSurveyed() {
        surveyed = true;
    }

    public int renderedTiles() {
        return rendered.get();
    }

    /** Record a chunk's current contents and mark every tile it can affect. */
    public void markChunk(int cx, int cz, ChunkSnap snap) {
        long key = ChunkPos.pack(cx, cz);
        int low = snap.lowestTop() - 1;
        int high = snap.topY();
        int[] previous = extents.put(key, new int[] {low, high});
        if (previous != null) {
            low = Math.min(low, previous[0]);
            high = Math.max(high, previous[1]);
        }
        if (high == Integer.MIN_VALUE) {
            return;
        }
        float w = pixelsPerBlock;
        int x0 = cx * 16, z0 = cz * 16;
        for (Layer l : layers.values()) {
            // The chunk's footprint in view space. A quarter-turn keeps it an axis-aligned square,
            // so two opposite corners give its bounds.
            double ax = l.view.viewX(x0, z0), bx = l.view.viewX(x0 + 16, z0 + 16);
            double az = l.view.viewZ(x0, z0), bz = l.view.viewZ(x0 + 16, z0 + 16);
            double xMin = Math.min(ax, bx), xMax = Math.max(ax, bx);
            double zMin = Math.min(az, bz), zMax = Math.max(az, bz);
            int sxMin = (int) Math.floor((xMin - zMax) * w / 2f);
            int sxMax = (int) Math.ceil((xMax - zMin) * w / 2f);
            int syMin = (int) Math.floor((xMin + zMin) * w / 4f - high * w / 2f);
            int syMax = (int) Math.ceil((xMax + zMax) * w / 4f - low * w / 2f);
            int t = IsoRenderer.TILE;
            for (int ty = Math.floorDiv(syMin, t); ty <= Math.floorDiv(syMax, t); ty++) {
                for (int tx = Math.floorDiv(sxMin, t); tx <= Math.floorDiv(sxMax, t); tx++) {
                    long tile = pack(tx, ty);
                    l.tops.merge(tile, high, Math::max);
                    l.dirty.add(tile);
                }
            }
        }
    }

    /**
     * Render every dirty tile on {@code pool}, then rebuild the zoom levels above them. Blocks the
     * calling thread, which must not be the server's.
     */
    public void renderDirty(ExecutorService pool, ThreadLocal<IsoRenderer> renderers) {
        for (Layer l : layers.values()) {
            renderDirty(l, pool, renderers);
        }
    }

    private void renderDirty(Layer layer, ExecutorService pool, ThreadLocal<IsoRenderer> renderers) {
        Set<Long> dirty = layer.dirty;
        TileStore tiles = layer.tiles;
        if (dirty.isEmpty()) {
            return;
        }
        List<Long> batch = new ArrayList<>(dirty);
        dirty.removeAll(batch);
        // Row order keeps neighbouring tiles — which share chunks — close together in time, so
        // the chunk cache holds what the next tile needs.
        batch.sort((a, b) -> {
            int c = Integer.compare(unpackY(a), unpackY(b));
            return c != 0 ? c : Integer.compare(unpackX(a), unpackX(b));
        });

        int bottom = store.level().getMinY();
        int ceiling = store.level().getMaxY() + 1;
        List<Future<?>> jobs = new ArrayList<>(batch.size());
        for (long key : batch) {
            int top = surveyed ? layer.tops.getOrDefault(key, ceiling) : ceiling;
            jobs.add(pool.submit(() -> {
                int tx = unpackX(key), ty = unpackY(key);
                try {
                    tiles.write(0, tx, ty, renderers.get().render(store, layer.view, tx, ty, top, bottom));
                    rendered.incrementAndGet();
                } catch (IOException | RuntimeException ex) {
                    Diorama.LOGGER.warn("Diorama: tile {},{} failed: {}", tx, ty, ex.toString());
                }
            }));
        }
        await(jobs);

        Set<Long> level = new HashSet<>(batch);
        for (int z = 1; z <= TileStore.ZOOM_OUT_LEVELS; z++) {
            Set<Long> parents = new HashSet<>();
            for (long k : level) {
                parents.add(pack(Math.floorDiv(unpackX(k), 2), Math.floorDiv(unpackY(k), 2)));
            }
            int lz = z;
            List<Future<?>> up = new ArrayList<>(parents.size());
            for (long k : parents) {
                up.add(pool.submit(() -> {
                    try {
                        tiles.downsample(lz, unpackX(k), unpackY(k));
                    } catch (IOException | RuntimeException ex) {
                        Diorama.LOGGER.warn("Diorama: zoom tile {}/{},{} failed: {}", lz, unpackX(k), unpackY(k), ex.toString());
                    }
                }));
            }
            await(up);
            level = parents;
        }
    }

    private static void await(List<Future<?>> jobs) {
        for (Future<?> f : jobs) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.util.concurrent.ExecutionException e) {
                Diorama.LOGGER.warn("Diorama: render job failed", e.getCause());
            }
        }
    }

    static long pack(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }

    static int unpackX(long k) {
        return (int) (k >> 32);
    }

    static int unpackY(long k) {
        return (int) k;
    }
}
