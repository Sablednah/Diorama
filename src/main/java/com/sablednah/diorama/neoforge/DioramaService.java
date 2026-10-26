package com.sablednah.diorama.neoforge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sablednah.diorama.Diorama;
import com.sablednah.diorama.DioramaConfig;
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
 * chunk reading, ray casting and PNG writing all happen on Diorama's own threads, at minimum
 * priority, so the map can fall behind but the server cannot.</p>
 */
public final class DioramaService {

    /** Why the map is not drawing, for {@code /diorama status} and the web page. */
    public enum State { STARTING, NO_ASSETS, READY, STOPPED }

    private static @Nullable DioramaService current;

    private final MinecraftServer server;
    private final Path worldRoot;
    private final boolean dedicated;
    private volatile State state = State.STARTING;
    private volatile String stateDetail = "";

    private @Nullable BlockModels models;
    /** Filled once, in {@link #setUp}; empty until assets are ready. */
    private volatile Map<ResourceKey<Level>, MapRenderer> maps = Map.of();
    private final ConcurrentLinkedQueue<Saved> saved = new ConcurrentLinkedQueue<>();
    private volatile List<PlayerDot> players = List.of();
    private volatile @Nullable String fullRender;

    private final ExecutorService pool;
    private final ScheduledExecutorService coordinator;
    private @Nullable ThreadLocal<IsoRenderer> renderers;
    private @Nullable MapWebServer web;

    private record Saved(ResourceKey<Level> level, ChunkPos pos, SerializableChunkData data) {}

    public record PlayerDot(String name, String world, double x, double y, double z) {}

    private DioramaService(MinecraftServer server) {
        this.server = server;
        this.worldRoot = server.getWorldPath(LevelResource.ROOT);
        this.dedicated = server.isDedicatedServer();
        this.pool = Executors.newFixedThreadPool(DioramaConfig.RENDER_THREADS.get(), threads("Diorama-render"));
        this.coordinator = Executors.newSingleThreadScheduledExecutor(threads("Diorama-coordinator"));
    }

    public static @Nullable DioramaService current() {
        return current;
    }

    // ---------------------------------------------------------------- lifecycle

    static void start(MinecraftServer server) {
        stop();
        DioramaService service = new DioramaService(server);
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
            Map<ResourceKey<Level>, MapRenderer> built = new LinkedHashMap<>();
            for (ServerLevel level : server.getAllLevels()) {
                built.put(level.dimension(), new MapRenderer(new ChunkStore(level, capacity), tileRoot(level.dimension()), views, ppb));
            }
            maps = java.util.Collections.unmodifiableMap(built);
            state = State.READY;
            Diorama.LOGGER.info("Diorama: ready — {} asset source(s), {} dimension(s)", assets.sources().size(), maps.size());

            saveWorld();
            if (Boolean.getBoolean("diorama.selftest")) {
                SelfTest.run(this);
            }
            // A world with no tiles at all gets drawn once, unasked — as does a view just switched
            // on. After that only changes are.
            for (var e : maps.entrySet()) {
                if (e.getValue().missingTiles()) {
                    renderAll(e.getKey());
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
    void chunkSaved(ResourceKey<Level> level, ChunkPos pos, SerializableChunkData data) {
        if (state == State.READY || state == State.STARTING) {
            saved.add(new Saved(level, pos, data));
        }
    }

    /**
     * Ask the server to save every changed chunk, and take the copies it hands us. Without this a
     * new world is nearly all in memory: the disk holds a handful of chunks until the first
     * autosave, and a full render draws only those. Not flushed — that would stall the server
     * thread on disk writes — and it need not be, because our reads go through the same IOWorker
     * the writes are queued on.
     */
    private void saveWorld() {
        try {
            server.submit(() -> server.saveAllChunks(true, false, false)).join();
        } catch (RuntimeException e) {
            Diorama.LOGGER.warn("Diorama: could not save the world before rendering ({})", e.toString());
        }
        drainSaved();
    }

    private void drainSaved() {
        Saved s;
        while ((s = saved.poll()) != null) {
            MapRenderer map = maps.get(s.level());
            if (map == null) {
                continue;
            }
            ChunkSnap snap = map.store().accept(s.pos(), s.data());
            if (snap != null) {
                map.markChunk(s.pos().x(), s.pos().z(), snap);
            }
        }
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
        fullRender = level.identifier() + ": queued — saving the world first";
        return coordinator.submit(() -> {
            try {
                saveWorld();
                renderAll(level);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // Stopping: see update().
            }
        });
    }

    private void renderAll(ResourceKey<Level> key) {
        MapRenderer map = maps.get(key);
        if (map == null) {
            return;
        }
        String name = key.identifier().toString();
        long start = System.nanoTime();
        int chunks = map.store().scan();
        fullRender = name + ": reading " + chunks + " chunks";
        Diorama.LOGGER.info("Diorama: full render of {} — {} chunks", name, chunks);

        List<Future<?>> reads = new ArrayList<>(chunks);
        AtomicInteger done = new AtomicInteger();
        for (long packed : map.store().existing()) {
            int cx = ChunkPos.getX(packed), cz = ChunkPos.getZ(packed);
            reads.add(pool.submit(() -> {
                ChunkSnap snap = map.store().get(cx, cz);
                if (snap != null) {
                    map.markChunk(cx, cz, snap);
                }
                done.incrementAndGet();
            }));
        }
        for (Future<?> f : reads) {
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
        fullRender = name + ": drawing " + map.pendingTiles() + " tiles";
        int before = map.renderedTiles();
        map.renderDirty(pool, renderers);
        fullRender = null;
        Diorama.LOGGER.info("Diorama: {} drawn — {} tiles in {}s", name, map.renderedTiles() - before,
                (System.nanoTime() - start) / 1_000_000_000L);
    }

    /** Once a second, from the server thread. */
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

    public Map<ResourceKey<Level>, MapRenderer> maps() {
        return maps;
    }

    public List<PlayerDot> players() {
        return players;
    }

    /** The folder holding one folder per view for {@code level}. */
    public Path tileRoot(ResourceKey<Level> level) {
        return worldRoot.resolve("diorama").resolve("tiles").resolve(worldId(level));
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
