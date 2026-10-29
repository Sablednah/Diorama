package com.sablednah.diorama.render;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
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
    /** Keyed by folder name: {@code se}, or {@code se-night}. */
    private final Map<String, Layer> layers = new java.util.LinkedHashMap<>();
    private final Set<View> views;
    private final int pixelsPerBlock;

    /** One view's tiles, by day or by night. */
    private static final class Layer {
        final View view;
        final boolean night;
        final TileStore tiles;
        /** Level-0 tiles waiting to be drawn. */
        final Set<Long> dirty = ConcurrentHashMap.newKeySet();
        /**
         * Taken from {@link #dirty} into the current batch and not finished yet. Counted as waiting
         * and saved with it: left out, the status read "0 waiting" through a 30,000-tile batch and
         * a restart mid-batch lost the lot.
         */
        final Set<Long> drawing = ConcurrentHashMap.newKeySet();
        /**
         * The highest block top each tile has ever been told about. It only rises: a tile is
         * marked by one changed chunk at a time, and starting its rays at that one chunk's height
         * would cut the top off a taller neighbour that shares the tile.
         */
        final Map<Long, Integer> tops = new ConcurrentHashMap<>();

        Layer(View view, boolean night, TileStore tiles) {
            this.view = view;
            this.night = night;
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

    /**
     * {@link #extents}, kept on disk. Without it every restart began unsurveyed, every ray started
     * at the build limit, and a tile read far more chunks than the cache holds: on a 65,536-chunk
     * CityWorld map the live redraw fell from 23 tiles a second to 4.
     */
    private final Path heightsFile;
    private volatile boolean heightsChanged;
    private volatile long heightsSaved = System.nanoTime();
    private static final int HEIGHTS_MAGIC = 0x44494F31; // "DIO1"

    /** {@code root} holds one folder per view and time: {@code <root>/se/0/…}, {@code <root>/se-night/0/…}. */
    public MapRenderer(ChunkStore store, Path root, Set<View> views, boolean night, int pixelsPerBlock) {
        this.store = store;
        this.views = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(views));
        for (View v : views) {
            layers.put(folder(v, false), new Layer(v, false, new TileStore(root.resolve(folder(v, false)))));
            if (night) {
                layers.put(folder(v, true), new Layer(v, true, new TileStore(root.resolve(folder(v, true)))));
            }
        }
        this.pixelsPerBlock = pixelsPerBlock;
        this.heightsFile = root.resolve("heights.bin");
        Path clean = root.resolve("stopped-cleanly");
        this.cleanStart = java.nio.file.Files.isRegularFile(clean);
        try {
            java.nio.file.Files.deleteIfExists(clean);
        } catch (IOException e) {
            Diorama.LOGGER.warn("Diorama: could not clear {}: {}", clean, e.toString());
        }
        loadHeights();
        loadPending();
    }

    /**
     * Tiles still waiting, kept beside the heights. Held only in memory, a restart threw the queue
     * away: a changed building stayed drawn as it was, and a mass-generated area stayed blank.
     */
    private void savePending() {
        Path file = heightsFile.resolveSibling("pending.bin");
        try {
            if (pendingTiles() == 0) {
                java.nio.file.Files.deleteIfExists(file);
                return;
            }
            Path tmp = file.resolveSibling("pending.bin.tmp");
            java.nio.file.Files.createDirectories(file.getParent());
            try (var out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(java.nio.file.Files.newOutputStream(tmp)))) {
                out.writeInt(HEIGHTS_MAGIC);
                out.writeInt(layers.size());
                for (var e : layers.entrySet()) {
                    java.util.List<Long> tiles = new ArrayList<>(e.getValue().dirty);
                    tiles.addAll(e.getValue().drawing);
                    out.writeUTF(e.getKey());
                    out.writeInt(tiles.size());
                    for (long t : tiles) {
                        out.writeLong(t);
                    }
                }
            }
            java.nio.file.Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.io.IOException e) {
            com.sablednah.diorama.Diorama.LOGGER.warn("Diorama: could not save {}: {}", file, e.toString());
        }
    }

    private void loadPending() {
        Path file = heightsFile.resolveSibling("pending.bin");
        if (!java.nio.file.Files.isRegularFile(file)) {
            return;
        }
        try (var in = new java.io.DataInputStream(new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(file)))) {
            if (in.readInt() != HEIGHTS_MAGIC) {
                return;
            }
            int layerCount = in.readInt();
            for (int i = 0; i < layerCount; i++) {
                Layer l = layers.get(in.readUTF());
                int n = in.readInt();
                for (int j = 0; j < n; j++) {
                    long t = in.readLong();
                    if (l != null) { // a view switched off since: its queue goes with it
                        l.dirty.add(t);
                    }
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            com.sablednah.diorama.Diorama.LOGGER.warn("Diorama: {} could not be read ({}); missing tiles are still found on start",
                    file, e.toString());
        }
    }

    /**
     * Whether the last run stopped cleanly — heights, queue and zoom levels all written. Only
     * after a crash (or the first start of a version that keeps this marker) are the start-up
     * repairs worth what they cost: they list every tile, which on a 4 km map takes minutes.
     */
    private final boolean cleanStart;

    public boolean cleanStart() {
        return cleanStart;
    }

    /** Last thing on stop, after {@link #saveHeights}: the next start can trust what is on disk. */
    public void markStoppedCleanly() {
        Path clean = heightsFile.resolveSibling("stopped-cleanly");
        try {
            java.nio.file.Files.createDirectories(clean.getParent());
            java.nio.file.Files.writeString(clean, "Diorama stopped cleanly. Deleted on start; absent means the start-up repairs run.\n");
        } catch (IOException e) {
            Diorama.LOGGER.warn("Diorama: could not write {}: {}", clean, e.toString());
        }
    }

    /** How many chunks' heights came back from disk; 0 for a map that has never been surveyed. */
    public int loadedHeights() {
        return loadedHeights;
    }

    private int loadedHeights;

    private void loadHeights() {
        if (!java.nio.file.Files.isRegularFile(heightsFile)) {
            return;
        }
        try (var in = new java.io.DataInputStream(new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(heightsFile)))) {
            if (in.readInt() != HEIGHTS_MAGIC) {
                return;
            }
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                long key = in.readLong();
                int low = in.readInt(), high = in.readInt();
                extents.put(key, new int[] {low, high});
                cover(ChunkPos.getX(key), ChunkPos.getZ(key), low, high, null);
            }
            loadedHeights = n;
            // Every chunk that existed when it was written: a chunk generated since has no entry,
            // and its tiles start at the build limit, which is slow but cannot miss anything.
            surveyed = true;
        } catch (java.io.IOException | RuntimeException e) {
            extents.clear();
            for (Layer l : layers.values()) {
                l.tops.clear();
            }
            com.sablednah.diorama.Diorama.LOGGER.warn("Diorama: {} could not be read ({}); this map will be surveyed again",
                    heightsFile, e.toString());
        }
    }

    /**
     * Read and record every chunk on disk that has no heights yet — saved while the queue that
     * carries saves to the map was lost to a crash or a restart. Its tiles are then found by
     * {@link #queueMissing}. Half-generated chunks at the edge are read and dropped each start.
     * Blocks the calling thread, which must not be the server's.
     */
    public int learnUnknown(ExecutorService pool) {
        store.scan();
        List<Future<?>> reads = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger learnt = new java.util.concurrent.atomic.AtomicInteger();
        for (long packed : store.existing()) {
            if (extents.containsKey(packed)) {
                continue;
            }
            int cx = ChunkPos.getX(packed), cz = ChunkPos.getZ(packed);
            reads.add(pool.submit(() -> {
                ChunkSnap snap = store.get(cx, cz);
                if (snap != null) {
                    // Marked, not merely recorded: new to the map, so the tiles beside it —
                    // drawn when this was void — are owed a redraw too. Recorded only, a 4 km
                    // map kept a black seam along its old edge.
                    markChunk(cx, cz, snap);
                    learnt.incrementAndGet();
                }
            }));
        }
        await(reads);
        return learnt.get();
    }

    /**
     * Queue every tile some known chunk should have drawn and that has no file: what a crash,
     * or a restart before pending tiles were kept, left undrawn. Tiles that drew empty at the
     * world's edge are deleted by design and come back here each start; they are few and cheap.
     * Returns how many were queued.
     */
    public int queueMissing() {
        java.util.Map<Layer, java.util.Set<Long>> present = new java.util.HashMap<>();
        for (Layer l : layers.values()) {
            java.util.Set<Long> have = listTiles(l.tiles.root().resolve("0"));
            present.put(l, have);
        }
        int before = pendingTiles();
        for (var e : extents.entrySet()) {
            long key = e.getKey();
            cover(ChunkPos.getX(key), ChunkPos.getZ(key), e.getValue()[0], e.getValue()[1], (l, tile) -> {
                java.util.Set<Long> have = present.get(l);
                if (have != null && !have.contains(tile)) {
                    l.dirty.add(tile);
                }
            });
        }
        return pendingTiles() - before;
    }

    /** Write {@link #extents} if it changed, through a temporary file. Any thread. */
    public synchronized void saveHeights() {
        savePending();
        if (!heightsChanged) {
            return;
        }
        heightsChanged = false;
        heightsSaved = System.nanoTime();
        Path tmp = heightsFile.resolveSibling("heights.bin.tmp");
        try {
            java.nio.file.Files.createDirectories(heightsFile.getParent());
            var copy = new java.util.HashMap<>(extents);
            try (var out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(java.nio.file.Files.newOutputStream(tmp)))) {
                out.writeInt(HEIGHTS_MAGIC);
                out.writeInt(copy.size());
                for (var e : copy.entrySet()) {
                    out.writeLong(e.getKey());
                    out.writeInt(e.getValue()[0]);
                    out.writeInt(e.getValue()[1]);
                }
            }
            java.nio.file.Files.move(tmp, heightsFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.io.IOException e) {
            heightsChanged = true;
            com.sablednah.diorama.Diorama.LOGGER.warn("Diorama: could not save {}: {}", heightsFile, e.toString());
        }
    }

    /** The folder, and the URL segment, for a view by day or night. */
    public static String folder(View view, boolean night) {
        return night ? view.id() + "-night" : view.id();
    }

    public ChunkStore store() {
        return store;
    }

    public Set<View> views() {
        return views;
    }

    /** The tiles for {@code view} by day or night, or null when that is not drawn. */
    public @org.jspecify.annotations.Nullable TileStore tiles(View view, boolean night) {
        Layer l = layers.get(folder(view, night));
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
            n += l.dirty.size() + l.drawing.size();
        }
        return n;
    }

    public void markSurveyed() {
        surveyed = true;
        saveHeights();
    }

    public int renderedTiles() {
        return rendered.get();
    }

    /** Record a chunk's heights for the survey, marking nothing: its tiles are already drawn. */
    public void recordChunk(int cx, int cz, ChunkSnap snap) {
        int low = snap.lowestTop() - 1, high = snap.topY();
        extents.put(ChunkPos.pack(cx, cz), new int[] {low, high});
        heightsChanged = true;
        cover(cx, cz, low, high, null);
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
        heightsChanged = true;
        cover(cx, cz, low, high, (l, tile) -> l.dirty.add(tile));
    }

    /**
     * Raise the tops of every tile a chunk spanning {@code low..high} can affect, and hand each
     * one to {@code each} (null: nothing more).
     */
    private void cover(int cx, int cz, int low, int high, java.util.function.@org.jspecify.annotations.Nullable BiConsumer<Layer, Long> each) {
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
                    if (each != null) {
                        each.accept(l, tile);
                    }
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
        if (heightsChanged && surveyed && System.nanoTime() - heightsSaved > 60_000_000_000L) {
            saveHeights();
        }
    }

    /** Tiles one view draws per pass before the next view, and the next world, get a turn. */
    private static final int BATCH = Integer.getInteger("diorama.batch", 512);

    /** Wall-clock spent drawing tiles and building zoom levels, and zoom tiles built, for the timing line. */
    private final java.util.concurrent.atomic.AtomicLong drawNanos = new java.util.concurrent.atomic.AtomicLong(),
            zoomNanos = new java.util.concurrent.atomic.AtomicLong(), zoomBuilt = new java.util.concurrent.atomic.AtomicLong();

    /** {draw ns, zoom ns, zoom tiles built} since the last call, and resets them. */
    public long[] takeTimings() {
        return new long[] {drawNanos.getAndSet(0), zoomNanos.getAndSet(0), zoomBuilt.getAndSet(0)};
    }

    private void renderDirty(Layer layer, ExecutorService pool, ThreadLocal<IsoRenderer> renderers) {
        Set<Long> dirty = layer.dirty;
        TileStore tiles = layer.tiles;
        if (dirty.isEmpty()) {
            return;
        }
        // Row order keeps neighbouring tiles — which share chunks — close together in time, so
        // the chunk cache holds what the next tile needs.
        List<Long> batch = new ArrayList<>(dirty);
        batch.sort((a, b) -> {
            int c = Integer.compare(unpackY(a), unpackY(b));
            return c != 0 ? c : Integer.compare(unpackX(a), unpackX(b));
        });
        // A capped share per pass, so every view and every world takes turns: uncapped, one
        // view's 30,000-tile backlog held the coordinator for an hour while a whole dimension
        // sat at "0 drawn".
        if (batch.size() > BATCH) {
            batch = new ArrayList<>(batch.subList(0, BATCH));
        }
        layer.drawing.addAll(batch);
        dirty.removeAll(batch);

        int bottom = store.level().getMinY();
        int ceiling = store.level().getMaxY() + 1;
        long t0 = System.nanoTime();
        // What each tile drew, handed to the zoom levels so they need not read it back.
        Map<Long, int[]> drawn = new java.util.concurrent.ConcurrentHashMap<>();
        List<Future<?>> jobs = new ArrayList<>(batch.size());
        for (long key : batch) {
            // A roofed world starts every ray at its cut: rays then pass through the roof's rock
            // to the caverns, which a start at the tallest floor would miss above itself.
            int top = store.ceiling() != Integer.MAX_VALUE ? store.ceiling()
                    : surveyed ? layer.tops.getOrDefault(key, ceiling) : ceiling;
            jobs.add(pool.submit(() -> {
                int tx = unpackX(key), ty = unpackY(key);
                try {
                    int[] argb = renderers.get().render(store, layer.view, layer.night, tx, ty, top, bottom);
                    tiles.write(0, tx, ty, argb);
                    drawn.put(key, argb == null ? TileStore.EMPTY : argb);
                    rendered.incrementAndGet();
                } catch (IOException | RuntimeException ex) {
                    Diorama.LOGGER.warn("Diorama: tile {},{} failed: {}", tx, ty, ex.toString());
                }
            }));
        }
        await(jobs);
        long t1 = System.nanoTime();
        drawNanos.addAndGet(t1 - t0);

        Set<Long> level = new HashSet<>(batch);
        Map<Long, int[]> below = drawn;
        for (int z = 1; z <= TileStore.ZOOM_OUT_LEVELS; z++) {
            Set<Long> parents = new HashSet<>();
            for (long k : level) {
                parents.add(pack(Math.floorDiv(unpackX(k), 2), Math.floorDiv(unpackY(k), 2)));
            }
            int lz = z;
            Map<Long, int[]> known = below, made = new java.util.concurrent.ConcurrentHashMap<>();
            List<Future<?>> up = new ArrayList<>(parents.size());
            for (long k : parents) {
                up.add(pool.submit(() -> {
                    try {
                        tiles.downsample(lz, unpackX(k), unpackY(k), known, made);
                    } catch (IOException | RuntimeException ex) {
                        Diorama.LOGGER.warn("Diorama: zoom tile {}/{},{} failed: {}", lz, unpackX(k), unpackY(k), ex.toString());
                    }
                }));
            }
            await(up);
            zoomBuilt.addAndGet(parents.size());
            level = parents;
            below = made;
        }
        zoomNanos.addAndGet(System.nanoTime() - t1);
        // Only now: a tile drawn but not yet shrunk into the zoom levels is still owed work, and
        // a restart between the two left holes in every zoomed-out view of it. Interrupted (the
        // server stopping), the batch stays owed and is saved with the queue.
        if (!Thread.currentThread().isInterrupted()) {
            layer.drawing.removeAll(batch);
        }
    }

    /**
     * Rebuild every zoom tile that is missing while its children exist, or older than any of them —
     * left by a stop between a pass's tiles and its zoom levels, before {@link Layer#drawing}
     * covered that gap. A stale one is the worse case: it exists, so nothing else will ever
     * revisit it, and it keeps the holes its children had when it was built — a whole ring of a
     * 4 km map stayed black at every zoom but the closest. Levels run bottom-up and are re-listed,
     * so a rebuild carries upwards. File times are compared only with each other, all written by
     * this process, so a clock skew between filesystems (drvfs runs ~40 s behind) cancels out.
     * Returns how many were built. Blocks the calling thread, which must not be the server's.
     */
    public int repairZoom(ExecutorService pool) {
        int built = 0;
        for (Layer l : layers.values()) {
            for (int z = 1; z <= TileStore.ZOOM_OUT_LEVELS; z++) {
                Map<Long, Long> below = tileTimes(l.tiles.root().resolve(Integer.toString(z - 1)));
                Map<Long, Long> here = tileTimes(l.tiles.root().resolve(Integer.toString(z)));
                Map<Long, Long> newestChild = new java.util.HashMap<>();
                for (var e : below.entrySet()) {
                    long k = e.getKey();
                    newestChild.merge(pack(Math.floorDiv(unpackX(k), 2), Math.floorDiv(unpackY(k), 2)), e.getValue(), Math::max);
                }
                Set<Long> owed = new HashSet<>();
                newestChild.forEach((parent, t) -> {
                    Long mine = here.get(parent);
                    if (mine == null || mine < t) {
                        owed.add(parent);
                    }
                });
                int lz = z;
                List<Future<?>> jobs = new ArrayList<>(owed.size());
                for (long k : owed) {
                    jobs.add(pool.submit(() -> {
                        try {
                            l.tiles.downsample(lz, unpackX(k), unpackY(k));
                        } catch (IOException | RuntimeException ex) {
                            Diorama.LOGGER.warn("Diorama: zoom tile {}/{},{} failed: {}", lz, unpackX(k), unpackY(k), ex.toString());
                        }
                    }));
                }
                await(jobs);
                built += owed.size();
            }
        }
        return built;
    }

    /** Each tile in one zoom level's folder, packed, with its modification time; empty when there is no folder. */
    private static Map<Long, Long> tileTimes(Path dir) {
        Map<Long, Long> out = new java.util.HashMap<>();
        if (!java.nio.file.Files.isDirectory(dir)) {
            return out;
        }
        try (var files = java.nio.file.Files.newDirectoryStream(dir, "*.png")) {
            for (Path f : files) {
                String name = f.getFileName().toString();
                int us = name.indexOf('_'), dot = name.lastIndexOf(".png");
                if (us <= 0 || dot <= us) {
                    continue;
                }
                try {
                    long key = pack(Integer.parseInt(name.substring(0, us)), Integer.parseInt(name.substring(us + 1, dot)));
                    out.put(key, java.nio.file.Files.getLastModifiedTime(f).toMillis());
                } catch (NumberFormatException | IOException ignored) {
                    // not a tile, or gone since the listing
                }
            }
        } catch (IOException e) {
            Diorama.LOGGER.warn("Diorama: could not list {}: {}", dir, e.toString());
        }
        return out;
    }

    /** The tiles in one zoom level's folder, packed; empty when it does not exist. */
    private static Set<Long> listTiles(Path dir) {
        Set<Long> have = new HashSet<>();
        if (!java.nio.file.Files.isDirectory(dir)) {
            return have;
        }
        try (var files = java.nio.file.Files.list(dir)) {
            files.forEach(f -> {
                String name = f.getFileName().toString();
                int us = name.indexOf('_'), dot = name.lastIndexOf(".png");
                if (us > 0 && dot > us) {
                    try {
                        have.add(pack(Integer.parseInt(name.substring(0, us)), Integer.parseInt(name.substring(us + 1, dot))));
                    } catch (NumberFormatException ignored) {
                        // not a tile
                    }
                }
            });
        } catch (IOException e) {
            Diorama.LOGGER.warn("Diorama: could not list {}: {}", dir, e.toString());
        }
        return have;
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
