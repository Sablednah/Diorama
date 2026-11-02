package com.sablednah.diorama.neoforge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sablednah.diorama.Diorama;
import com.sablednah.diorama.DioramaConfig;
import com.sablednah.diorama.api.Markers;
import com.sablednah.diorama.asset.AssetSource;
import com.sablednah.diorama.asset.Assets;
import com.sablednah.diorama.asset.ClientJarDownload;
import com.sablednah.diorama.model.BlockModels;
import com.sablednah.diorama.render.BlockTints;
import com.sablednah.diorama.render.IsoRenderer;
import com.sablednah.diorama.render.MapRenderer;
import com.sablednah.diorama.render.TileStore;
import com.sablednah.diorama.web.MapWebServer;
import com.sablednah.diorama.world.ChunkSnap;
import com.sablednah.diorama.world.ChunkStore;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import org.jspecify.annotations.Nullable;

/**
 * Everything Diorama runs while a server is up — one per server, single player included.
 *
 * <p><b>Nothing here runs on the server thread except handing work over.</b> The save hook queues
 * a chunk copy the game already made; the player list is sampled once a second. Asset loading,
 * chunk reading, ray casting and PNG writing all happen on Diorama's own threads, which stand
 * aside while the server's ticks run long ({@link RenderPool}) — minimum priority alone means
 * nothing on Linux — so the map can fall behind but the server cannot.</p>
 */
public final class DioramaService {

    /** Why the map is not drawing, for {@code /diorama status} and the web page. */
    public enum State { STARTING, NO_ASSETS, READY, STOPPED }

    private static @Nullable DioramaService current;

    private final MinecraftServer server;
    private final Path worldRoot;
    /** Where this world's tiles live: beside the server, not in the save. See {@link #tilesHome}. */
    private final Path tilesBase;
    private final boolean dedicated;
    private volatile State state = State.STARTING;
    private volatile String stateDetail = "";

    private @Nullable BlockModels models;
    /** Filled once, in {@link #setUp}; empty until assets are ready. */
    private volatile Map<ResourceKey<Level>, MapRenderer> maps = Map.of();
    /** The newest copy of each chunk saved and not yet taken in. See {@link SaveQueue}. */
    private final SaveQueue saved = new SaveQueue();
    private volatile List<PlayerDot> players = List.of();
    /**
     * Chunks with a block changed since we last copied them, oldest first. Server thread only: the
     * event that fills it and the tick that drains it both run there.
     */
    private final Map<ResourceKey<Level>, java.util.LinkedHashSet<Long>> touched = new java.util.HashMap<>();
    private volatile @Nullable String fullRender;

    private final ExecutorService pool;
    private final java.util.concurrent.ScheduledThreadPoolExecutor coordinator;
    private @Nullable ThreadLocal<IsoRenderer> renderers;
    private @Nullable MapWebServer web;

    public record PlayerDot(String name, String world, double x, double y, double z) {}

    /** One marker set as last sampled: every world's markers and regions, tagged with the world's id. */
    public record MarkerLayer(String id, String label, boolean shown, List<Placed> markers, List<PlacedRegion> regions) {}

    public record Placed(String world, Markers.Marker marker) {}

    /** A region with its outline laid on the ground: polylines of flat {@code x, y, z} triples. */
    public record PlacedRegion(String world, Markers.Region region, List<double[]> lines, List<double[]> fills, double[] anchor) {}

    /** What the server thread hands the coordinator: the sets' raw answers, nothing traced yet. */
    record RawLayer(String id, String label, boolean shown, List<Placed> markers, List<RawRegion> regions) {}

    record RawRegion(ResourceKey<Level> level, Markers.Region region) {}

    /** Traced outlines, reused while the shape is unchanged, re-laid every minute for the terrain. */
    private final Map<String, PlacedRegion> outlines = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long> outlineAge = new java.util.concurrent.ConcurrentHashMap<>();

    private volatile List<MarkerLayer> markers = List.of();
    /**
     * The one marker sample waiting for the coordinator, if any. Only the latest is ever held: a
     * publish queued behind a long render used to wait there with every sample after it, each a
     * copy of every marker set.
     */
    private final AtomicReference<@Nullable List<RawLayer>> pendingMarkers = new AtomicReference<>();
    /** Sets that have thrown once, so a broken one logs once rather than every five seconds. */
    private final java.util.Set<String> complained = new java.util.HashSet<>();

    private DioramaService(MinecraftServer server) {
        this.server = server;
        this.worldRoot = server.getWorldPath(LevelResource.ROOT);
        this.tilesBase = tilesHome(worldRoot);
        this.dedicated = server.isDedicatedServer();
        this.pool = new RenderPool(DioramaConfig.RENDER_THREADS.get(), threads("Diorama-render"), server);
        this.coordinator = new java.util.concurrent.ScheduledThreadPoolExecutor(1, threads("Diorama-coordinator"));
    }

    public static @Nullable DioramaService current() {
        return current;
    }

    // ---------------------------------------------------------------- lifecycle

    static void start(MinecraftServer server) {
        stop();
        Markers.register(SpawnMarkers.INSTANCE);
        Markers.register(OwnerRegions.INSTANCE);
        com.sablednah.diorama.integration.Linked.clear();
        com.sablednah.diorama.integration.FactionsTerritory.install(server, Lang::text);
        com.sablednah.diorama.integration.StandardsPlaces.install(server, Lang::text);
        DioramaService service = new DioramaService(server);
        OwnerRegions.INSTANCE.load(server, service.worldRoot);
        com.sablednah.diorama.integration.CityWorldInfo.install(server, service.worldRoot, Lang::text);
        current = service;
        service.startWeb();
        service.coordinator.execute(service::setUp);
    }

    static void stop() {
        DioramaService s = current;
        current = null;
        if (s == null) {
            return;
        }
        s.state = State.STOPPED;
        if (s.web != null) {
            s.web.stop();
        }
        s.coordinator.shutdownNow();
        s.pool.shutdownNow();
        // Saves not yet taken in: recorded now, so their heights and tiles are kept below
        // rather than dropped with the queue. A whole night of generation once went that way.
        try {
            s.drainSaved();
        } catch (RuntimeException e) {
            Diorama.LOGGER.warn("Diorama: could not take in the last saves: {}", e.toString());
        }
        // Only after the pool has finished: a pass cut off mid-way leaves its tiles in the saved
        // queue, but a zoom level it was writing would be stale, and only the repair finds that.
        boolean finished;
        try {
            finished = s.pool.awaitTermination(10, TimeUnit.SECONDS) && s.coordinator.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        for (MapRenderer map : s.maps.values()) {
            map.saveHeights();
            if (finished) {
                map.markStoppedCleanly();
            }
        }
    }

    private void startWeb() {
        if (!DioramaConfig.WEB_ENABLED.get()) {
            return;
        }
        String bind = DioramaConfig.WEB_BIND.get().trim();
        if (bind.isEmpty()) {
            bind = dedicated ? "0.0.0.0" : "127.0.0.1";
        }
        try {
            web = new MapWebServer(this, bind, DioramaConfig.WEB_PORT.get());
            web.start();
            Diorama.LOGGER.info("Diorama: map served at {}", url());
        } catch (IOException e) {
            // Most often the port: another server, or a second single-player world opened before
            // the first one's server let go of it.
            Diorama.LOGGER.error("Diorama: cannot serve the map on {}:{} ({}). Tiles still render; set web.port in config/diorama-common.toml.",
                    bind, DioramaConfig.WEB_PORT.get(), e.getMessage());
            web = null;
        }
    }

    private void setUp() {
        try {
            Assets assets = loadAssets();
            if (assets == null) {
                return;
            }
            BlockTints.initColorMaps(assets);
            BlockModels m = new BlockModels(assets);
            models = m;
            int ppb = DioramaConfig.PIXELS_PER_BLOCK.get();
            renderers = ThreadLocal.withInitial(() -> new IsoRenderer(m, ppb));
            int capacity = DioramaConfig.CHUNK_CACHE.get();
            var views = DioramaConfig.views();
            boolean night = DioramaConfig.NIGHT.get();
            Map<ResourceKey<Level>, MapRenderer> built = new LinkedHashMap<>();
            for (ServerLevel level : server.getAllLevels()) {
                built.put(level.dimension(), new MapRenderer(new ChunkStore(level, capacity, night), tileRoot(level.dimension()), views, night, ppb));
            }
            maps = java.util.Collections.unmodifiableMap(built);
            built.forEach((dim, map) -> {
                if (map.loadedHeights() > 0) {
                    Diorama.LOGGER.info("Diorama: {} — heights of {} chunks from disk", dim.identifier(), map.loadedHeights());
                }
            });
            state = State.READY;
            Diorama.LOGGER.info("Diorama: ready — {} asset source(s), {} dimension(s)", assets.sources().size(), maps.size());

            // Only a map with no tiles yet needs the world saved first: its first full render reads
            // the region files, and a brand-new world is nearly all in memory. Everywhere else the
            // game's eager saves (every changed chunk, ~10 s after it changed) deliver within seconds.
            if (maps.values().stream().anyMatch(MapRenderer::missingTiles)) {
                saveWorld();
            }
            if (Boolean.getBoolean("diorama.selftest")) {
                SelfTest.run(this);
            }
            // A world with no tiles at all gets drawn once, unasked — as does a view just switched
            // on. After that only changes are.
            for (var e : maps.entrySet()) {
                if (e.getValue().missingTiles()) {
                    renderAll(e.getKey());
                } else if (e.getValue().loadedHeights() == 0) {
                    // Drawn before heights were kept: learn them once, without redrawing anything.
                    renderAll(e.getKey(), false);
                } else if (!e.getValue().cleanStart()) {
                    // A crash, or the first start of this version: check what is on disk.
                    Diorama.LOGGER.info("Diorama: {} did not stop cleanly last time; checking its tiles", e.getKey().identifier());
                    int learnt = e.getValue().learnUnknown(pool);
                    if (learnt > 0) {
                        Diorama.LOGGER.info("Diorama: {} — {} chunk(s) saved but never mapped, read", e.getKey().identifier(), learnt);
                    }
                    int zoom = e.getValue().repairZoom(pool);
                    if (zoom > 0) {
                        Diorama.LOGGER.info("Diorama: {} — {} zoomed-out tile(s) missing or out of date, rebuilt", e.getKey().identifier(), zoom);
                    }
                    int missing = e.getValue().queueMissing();
                    if (missing > 0) {
                        Diorama.LOGGER.info("Diorama: {} — {} tile(s) never drawn, queued", e.getKey().identifier(), missing);
                    }
                }
            }
            int every = DioramaConfig.UPDATE_SECONDS.get();
            coordinator.scheduleWithFixedDelay(this::update, every, every, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            state = State.NO_ASSETS;
            stateDetail = e.toString();
            Diorama.LOGGER.error("Diorama: setup failed", e);
        }
    }

    private @Nullable Assets loadAssets() {
        Path home = FMLPaths.GAMEDIR.get().resolve("diorama");
        List<AssetSource> sources = new ArrayList<>(Assets.overrides(home.resolve("assets")));
        ModList.get().forEachModFile(file -> sources.add(AssetSource.mod(file.getId(), file.getContents())));
        Assets assets = new Assets(sources);
        if (assets.hasVanilla()) {
            return assets;
        }

        String version = SharedConstants.getCurrentVersion().id();
        Path jar = home.resolve("cache").resolve("client-" + version + ".jar");
        if (!Files.isRegularFile(jar)) {
            if (!DioramaConfig.ACCEPT_DOWNLOAD.get()) {
                state = State.NO_ASSETS;
                stateDetail = "This server has no block textures. Set assets.acceptDownload = true in config/diorama-common.toml "
                        + "to fetch the " + version + " client jar from Mojang, or put one in diorama/assets/.";
                Diorama.LOGGER.warn("Diorama: {}", stateDetail);
                return null;
            }
            try {
                ClientJarDownload.fetch(version, jar);
            } catch (IOException e) {
                state = State.NO_ASSETS;
                stateDetail = "Downloading the client jar failed: " + e.getMessage();
                Diorama.LOGGER.error("Diorama: {}", stateDetail);
                return null;
            }
        }
        try {
            sources.add(AssetSource.zip(jar));
        } catch (IOException e) {
            state = State.NO_ASSETS;
            stateDetail = "Cannot open " + jar + ": " + e.getMessage();
            Diorama.LOGGER.error("Diorama: {}", stateDetail);
            return null;
        }
        return new Assets(sources);
    }

    // ---------------------------------------------------------------- work

    /** From the save hook, on the server thread: queue it and return. */
    /** Server thread. A block changed: remember its chunk, cheaply. */
    void blockChanged(ServerLevel level, int blockX, int blockZ) {
        if (state != State.READY) {
            return;
        }
        touched.computeIfAbsent(level.dimension(), k -> new java.util.LinkedHashSet<>())
                .add(ChunkPos.pack(blockX >> 4, blockZ >> 4));
    }

    /**
     * Server thread, once a second. Copy up to {@code render.liveChunksPerSecond} touched chunks the
     * way a save does, and queue the copies exactly as if a save had handed them over. The budget
     * is what keeps a TNT crater or a long piston run from turning into a server-thread stall; the
     * rest wait their turn, oldest first, and a later save delivers anything that is still waiting.
     */
    void snapshotTouched() {
        if (touched.isEmpty()) {
            return;
        }
        int budget = DioramaConfig.LIVE_CHUNKS_PER_SECOND.get();
        var levels = touched.entrySet().iterator();
        while (levels.hasNext() && budget > 0) {
            var e = levels.next();
            ServerLevel level = server.getLevel(e.getKey());
            var chunks = e.getValue().iterator();
            while (chunks.hasNext() && budget > 0) {
                long key = chunks.next();
                chunks.remove();
                if (level == null) {
                    continue;
                }
                var chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
                if (chunk != null && chunk.canBeSerialized()) {
                    saved.offer(e.getKey(), chunk.getPos(), SerializableChunkData.copyOf(level, chunk));
                    budget--;
                }
            }
            if (e.getValue().isEmpty()) {
                levels.remove();
            }
        }
    }

    void chunkSaved(ResourceKey<Level> level, ChunkPos pos, SerializableChunkData data) {
        if (state == State.READY || state == State.STARTING) {
            saved.offer(level, pos, data);
        }
    }

    /**
     * Ask the server to save every changed chunk, and take the copies it hands us. Without this a
     * new world is nearly all in memory and its first full render draws only the handful of chunks
     * on disk. Not flushed — that would stall the server thread on disk writes — and it need not
     * be, because our reads go through the same IOWorker the writes are queued on.
     *
     * <p>Only for a map with no tiles yet. It is an autosave-sized task on the server thread (every
     * dimension's chunks and saved data), and anywhere else the game's own eager saves hand every
     * changed chunk over within seconds. The wait is bounded: a server paused or lagging must not
     * hold the map, nor the map the server.</p>
     */
    private void saveWorld() {
        try {
            server.submit(() -> server.saveAllChunks(true, false, false)).get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            Diorama.LOGGER.warn("Diorama: the world save before the first render took over 30 s; rendering what is on disk, the rest follows as it saves");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | RuntimeException e) {
            Diorama.LOGGER.warn("Diorama: could not save the world before rendering ({})", e.toString());
        }
        drainSaved();
    }

    private void drainSaved() {
        saved.drain(s -> {
            MapRenderer map = maps.get(s.level());
            if (map == null) {
                return;
            }
            ChunkSnap snap = map.store().accept(s.pos(), s.data());
            if (snap != null) {
                map.markChunk(s.pos().x(), s.pos().z(), snap);
            }
        });
    }

    private void update() {
        try {
            drainSaved();
            for (MapRenderer map : maps.values()) {
                map.renderDirty(pool, renderers);
            }
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // The server is stopping and the pool went first. Nothing is wrong.
        } catch (RuntimeException e) {
            Diorama.LOGGER.error("Diorama: update failed", e);
        }
    }

    /**
     * Read every chunk that exists, mark every tile, draw them all. Runs on the coordinator, so it
     * queues behind (and holds up) live updates rather than racing them.
     */
    public Future<?> requestFullRender(ResourceKey<Level> level) {
        return requestFullRender(level, null);
    }

    /**
     * As {@link #requestFullRender(ResourceKey)}, but only the chunks within {@code radius}
     * chunks of block {@code x, z} when {@code area} is {x, z, radius}: for redrawing one place,
     * and for timing the renderer in minutes rather than a whole map's hours.
     */
    public Future<?> requestFullRender(ResourceKey<Level> level, int @org.jspecify.annotations.Nullable [] area) {
        // No world save first: changed chunks reach the map within seconds through the game's own
        // eager saves and live updates, and a save on demand was an autosave-sized server spike.
        fullRender = level.identifier() + ": queued";
        return coordinator.submit(() -> {
            try {
                renderAll(level, true, area);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // Stopping: see update().
            }
        });
    }

    private void renderAll(ResourceKey<Level> key) {
        renderAll(key, true, null);
    }

    private void renderAll(ResourceKey<Level> key, boolean draw) {
        renderAll(key, draw, null);
    }

    /** With {@code draw} false, only a survey: every chunk's heights recorded, no tile redrawn. */
    private void renderAll(ResourceKey<Level> key, boolean draw, int @org.jspecify.annotations.Nullable [] area) {
        MapRenderer map = maps.get(key);
        if (map == null) {
            return;
        }
        String name = key.identifier().toString();
        long start = System.nanoTime();
        int chunks = map.store().scan();
        fullRender = name + ": reading " + chunks + " chunks";
        Diorama.LOGGER.info(draw ? "Diorama: full render of {} — {} chunks" : "Diorama: surveying {} — {} chunks", name, chunks);

        List<Future<?>> reads = new ArrayList<>(chunks);
        AtomicInteger done = new AtomicInteger();
        for (long packed : map.store().existing()) {
            int cx = ChunkPos.getX(packed), cz = ChunkPos.getZ(packed);
            if (area != null && (Math.abs(cx - (area[0] >> 4)) > area[2] || Math.abs(cz - (area[1] >> 4)) > area[2])) {
                continue;
            }
            reads.add(pool.submit(() -> {
                ChunkSnap snap = map.store().get(cx, cz);
                if (snap != null) {
                    if (draw) {
                        map.markChunk(cx, cz, snap);
                    } else {
                        map.recordChunk(cx, cz, snap);
                    }
                }
                done.incrementAndGet();
            }));
        }
        int waited = 0;
        for (Future<?> f : reads) {
            // Saves keep arriving during a long read: take them in as we go rather than letting
            // them pile up until the render ends. markChunk is already called from many threads here.
            if (++waited % 1024 == 0) {
                drainSaved();
            }
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.util.concurrent.ExecutionException e) {
                Diorama.LOGGER.debug("Diorama: chunk read failed", e.getCause());
            }
        }
        map.markSurveyed();
        if (!draw) {
            fullRender = null;
            Diorama.LOGGER.info("Diorama: {} surveyed in {}s", name, (System.nanoTime() - start) / 1_000_000_000L);
            return;
        }
        fullRender = name + ": drawing " + map.pendingTiles() + " tiles";
        int before = map.renderedTiles();
        long drawStart = System.nanoTime();
        map.takeTimings();
        map.store().takeCounts();
        // Each pass draws a capped share per view; a full render keeps going until it is done.
        while (map.pendingTiles() > 0 && !Thread.currentThread().isInterrupted()) {
            // Between batches, as update() does: an hours-long render must not hold every chunk
            // saved meanwhile in memory, nor keep them off the map until it ends.
            drainSaved();
            map.renderDirty(pool, renderers);
            fullRender = name + ": drawing, " + map.pendingTiles() + " tiles to go";
        }
        fullRender = null;
        int drawn = map.renderedTiles() - before;
        double secs = (System.nanoTime() - drawStart) / 1e9;
        long[] t = map.takeTimings(), c = map.store().takeCounts();
        Diorama.LOGGER.info("Diorama: {} drawn — {} tiles in {}s", name, drawn, (System.nanoTime() - start) / 1_000_000_000L);
        // The line a speed question starts from: where the time went, and how often the chunk
        // cache had to go to disk for something it had held and dropped.
        Diorama.LOGGER.info("Diorama: {} timing — {} tiles/s; drawing {}s, zoom levels {}s ({} built); chunks {} from memory, {} from disk ({} cache)",
                name, String.format("%.1f", drawn / Math.max(secs, 0.001)), t[0] / 1_000_000_000L, t[1] / 1_000_000_000L, t[2],
                c[0], c[1], com.sablednah.diorama.DioramaConfig.CHUNK_CACHE.get());
    }

    /**
     * Server thread, every five seconds: ask every set, then trace off the server thread. While the
     * last sample is still waiting for the coordinator (behind a long render), nothing is asked:
     * the sets are not called for an answer nobody will read, and only one sample is ever held.
     */
    void sampleMarkers() {
        if (pendingMarkers.get() != null) {
            return;
        }
        pendingMarkers.set(collectMarkers());
        try {
            coordinator.execute(() -> {
                List<RawLayer> raw = pendingMarkers.getAndSet(null);
                if (raw != null) {
                    publishMarkers(raw);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            pendingMarkers.set(null);
        }
    }

    /** Server thread. Each set reports its whole truth; nothing is kept between. */
    List<RawLayer> collectMarkers() {
        List<RawLayer> out = new ArrayList<>();
        for (Markers.MarkerSet set : Markers.sets()) {
            List<Placed> placed = new ArrayList<>();
            List<RawRegion> regions = new ArrayList<>();
            try {
                for (ServerLevel level : server.getAllLevels()) {
                    String world = worldId(level.dimension());
                    for (Markers.Marker m : set.markers(level.dimension())) {
                        placed.add(new Placed(world, m));
                    }
                    for (Markers.Region r : set.regions(level.dimension())) {
                        regions.add(new RawRegion(level.dimension(), r));
                    }
                }
            } catch (RuntimeException | LinkageError e) {
                if (complained.add(set.id())) {
                    Diorama.LOGGER.warn("Diorama: marker set {} failed; skipping it until it recovers", set.id(), e);
                }
                continue;
            }
            complained.remove(set.id());
            out.add(new RawLayer(set.id(), set.label(), set.shownByDefault(), List.copyOf(placed), List.copyOf(regions)));
        }
        return out;
    }

    /** Coordinator thread: lay every region's outline on the ground and publish the lot. */
    void publishMarkers(List<RawLayer> raw) {
        long now = System.currentTimeMillis();
        var cityworld = com.sablednah.diorama.integration.CityWorldInfo.current();
        if (cityworld != null) {
            cityworld.saveIfChanged();
        }
        java.util.Set<String> live = new java.util.HashSet<>();
        List<MarkerLayer> out = new ArrayList<>();
        for (RawLayer layer : raw) {
            List<PlacedRegion> regions = new ArrayList<>();
            for (RawRegion rr : layer.regions()) {
                MapRenderer map = maps.get(rr.level());
                if (map == null) {
                    continue;
                }
                String key = layer.id() + "|" + worldId(rr.level()) + "|" + rr.region().id();
                live.add(key);
                PlacedRegion cached = outlines.get(key);
                boolean fresh = cached != null && sameShape(cached.region(), rr.region())
                        && now - outlineAge.getOrDefault(key, 0L) < 60_000;
                if (fresh) {
                    // Label, colour or detail may have changed without the land moving.
                    regions.add(new PlacedRegion(cached.world(), rr.region(), cached.lines(), cached.fills(), cached.anchor()));
                    continue;
                }
                PlacedRegion traced = trace(map, rr);
                if (traced != null) {
                    outlines.put(key, traced);
                    outlineAge.put(key, now);
                    regions.add(traced);
                }
            }
            out.add(new MarkerLayer(layer.id(), layer.label(), layer.shown(), layer.markers(), List.copyOf(regions)));
        }
        outlines.keySet().retainAll(live);
        outlineAge.keySet().retainAll(live);
        markers = List.copyOf(out);
    }

    private static @Nullable PlacedRegion trace(MapRenderer map, RawRegion rr) {
        int sea = map.store().level().getSeaLevel();
        String world = worldId(rr.level());
        if (rr.region().shape() instanceof Markers.Chunks c) {
            if (c.chunks().isEmpty()) {
                return null;
            }
            return new PlacedRegion(world, rr.region(), com.sablednah.diorama.render.RegionOutline.trace(c.chunks(), map.store(), sea),
                    com.sablednah.diorama.render.RegionOutline.fill(c.chunks(), map.store(), sea),
                    com.sablednah.diorama.render.RegionOutline.anchor(c.chunks(), map.store(), sea));
        }
        Markers.Polygon p = (Markers.Polygon) rr.region().shape();
        if (p.corners().size() < 3) {
            return null;
        }
        double[] ring = com.sablednah.diorama.render.RegionOutline.tracePolygon(p.corners(), p.y(), map.store(), sea);
        // A polygon's own outline already follows the ground, so it is its fill too.
        return new PlacedRegion(world, rr.region(), List.of(ring), List.of(ring),
                com.sablednah.diorama.render.RegionOutline.anchor(p.corners(), p.y(), map.store(), sea));
    }

    /** Records compare arrays by identity, so a polygon's corners are compared by content here. */
    private static boolean sameShape(Markers.Region a, Markers.Region b) {
        if (a.shape() instanceof Markers.Polygon pa && b.shape() instanceof Markers.Polygon pb) {
            return java.util.Objects.equals(pa.y(), pb.y())
                    && java.util.Arrays.deepEquals(pa.corners().toArray(), pb.corners().toArray());
        }
        return a.shape().equals(b.shape());
    }

    public List<MarkerLayer> markers() {
        return markers;
    }

    void samplePlayers() {
        List<PlayerDot> dots = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (Hidden.hidden(p)) {
                continue;
            }
            dots.add(new PlayerDot(p.getGameProfile().name(), worldId(p.level().dimension()), p.getX(), p.getY(), p.getZ()));
        }
        players = List.copyOf(dots);
    }

    // ---------------------------------------------------------------- accessors

    public State state() {
        return state;
    }

    public String stateDetail() {
        return stateDetail;
    }

    public @Nullable String fullRenderProgress() {
        return fullRender;
    }

    public @Nullable BlockModels models() {
        return models;
    }

    public @Nullable ThreadLocal<IsoRenderer> renderers() {
        return renderers;
    }

    public MinecraftServer server() {
        return server;
    }

    /** Tasks queued on the coordinator behind the one running, for the self-test. */
    int coordinatorBacklog() {
        return coordinator.getQueue().size();
    }

    /** Whether a marker sample is waiting for the coordinator, for the self-test. */
    boolean markersPending() {
        return pendingMarkers.get() != null;
    }

    /** The render pool, for the self-test's repairs. */
    ExecutorService pool() {
        return pool;
    }

    public Map<ResourceKey<Level>, MapRenderer> maps() {
        return maps;
    }

    public List<PlayerDot> players() {
        return players;
    }

    /** The folder holding one folder per view for {@code level}. */
    public Path tileRoot(ResourceKey<Level> level) {
        return tilesBase.resolve(worldId(level));
    }

    /**
     * {@code <game dir>/diorama/tiles/<save folder>/}: one map per world, as before, but outside
     * the save. Tiles are a cache, and inside the save they cost twice: vanilla walks every file
     * of the world on start looking for symbolic links — over 700,000 tiles held a 4 km map's
     * boot for more than ten minutes — and every world backup carried gigabytes of them.
     *
     * <p>Tiles from before this sit in {@code <save>/diorama/tiles} and are moved here once. If
     * the move fails (another drive), they are left where they are and used from there.</p>
     */
    static Path tilesHome(Path worldRoot) {
        Path save = worldRoot.toAbsolutePath().normalize();
        Path home = FMLPaths.GAMEDIR.get().resolve("diorama").resolve("tiles").resolve(save.getFileName().toString());
        Path old = save.resolve("diorama").resolve("tiles");
        if (java.nio.file.Files.isDirectory(old) && !java.nio.file.Files.exists(home)) {
            try {
                java.nio.file.Files.createDirectories(home.getParent());
                java.nio.file.Files.move(old, home);
                Diorama.LOGGER.info("Diorama: map tiles moved out of the save, to {}", home);
            } catch (java.io.IOException e) {
                Diorama.LOGGER.warn("Diorama: could not move the map tiles from {} to {} ({}); using them where they are."
                        + " Move the folder by hand while the server is stopped: inside the save, every start walks every tile.",
                        old, home, e.toString());
                return old;
            }
        }
        return home;
    }

    /** {@code minecraft:overworld} → {@code minecraft_overworld}: safe in a URL and a path. */
    public static String worldId(ResourceKey<Level> level) {
        return level.identifier().toString().replace(':', '_').replace('/', '_');
    }

    public String url() {
        String configured = DioramaConfig.WEB_PUBLIC_URL.get().trim();
        if (!configured.isEmpty()) {
            return configured;
        }
        String bind = DioramaConfig.WEB_BIND.get().trim();
        String host = bind.isEmpty() || bind.equals("0.0.0.0") ? (dedicated ? "<this server>" : "127.0.0.1") : bind;
        return "http://" + host + ":" + DioramaConfig.WEB_PORT.get() + "/";
    }

    public boolean webRunning() {
        return web != null;
    }

    private static ThreadFactory threads(String name) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, name + "-" + n.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        };
    }
}
