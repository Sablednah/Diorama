package com.sablednah.diorama.world;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.sablednah.diorama.Diorama;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * Every chunk of one dimension, as far as the map is concerned.
 *
 * <p>Two sources, one shape. Chunks the server saves arrive through {@link #accept} — that is how
 * the map follows the world while it is played. Everything else is read from the region files on
 * a render thread, through the game's own {@code IOWorker}, which already serialises against
 * pending writes; we never open a {@code .mca} for its contents ourselves.</p>
 *
 * <p>What we do read directly is the 4&nbsp;KiB header of each region file, because that is the
 * only cheap way to ask "which chunks exist" without loading them.</p>
 */
public final class ChunkStore {

    private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    /** Cached as "known not to be renderable", so a missing chunk is not re-read every ray. */
    private static final Object NONE = new Object();

    private final ServerLevel level;
    /** Whether snapshots keep light: only when night tiles are drawn. */
    private final boolean keepLight;
    private final Set<Long> existing = ConcurrentHashMap.newKeySet();
    private final Map<Long, Object> cache;

    /** Reads answered from memory and from disk, for the timing line a full render logs. */
    private final java.util.concurrent.atomic.AtomicLong hits = new java.util.concurrent.atomic.AtomicLong(),
            reads = new java.util.concurrent.atomic.AtomicLong();

    /** {hits, disk reads} since the last call, and resets them. */
    public long[] takeCounts() {
        return new long[] {hits.getAndSet(0), reads.getAndSet(0)};
    }

    public ChunkStore(ServerLevel level, int capacity, boolean keepLight) {
        this.level = level;
        this.keepLight = keepLight;
        var type = level.dimensionType();
        this.ceiling = type.hasCeiling() ? level.getMinY() + type.logicalHeight() - 10 : Integer.MAX_VALUE;
        this.cache = java.util.Collections.synchronizedMap(new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Object> eldest) {
                return size() > capacity;
            }
        });
    }

    /**
     * Where this world is cut open: a little under the logical height of a roofed dimension (the
     * vanilla nether's bedrock sits at 123–127 with netherrack below it), {@link Integer#MAX_VALUE}
     * for a world open to the sky. Decided by the dimension type, not the dimension's name, so a
     * mod's roofed world is cut and a roofless nether (CityWorld's) is drawn from the sky.
     */
    private final int ceiling;

    public int ceiling() {
        return ceiling;
    }

    public ServerLevel level() {
        return level;
    }

    public Set<Long> existing() {
        return existing;
    }

    /** A freshly saved chunk: replaces whatever we held. Called from a render thread, not the server's. */
    public @Nullable ChunkSnap accept(ChunkPos pos, SerializableChunkData data) {
        ChunkSnap snap = ChunkSnap.of(data, keepLight, ceiling);
        long key = pos.pack();
        cache.put(key, snap == null ? NONE : snap);
        if (snap != null) {
            existing.add(key);
        }
        return snap;
    }

    /** The chunk, from memory or disk, or {@code null} when it does not exist or is not fully generated. */
    public @Nullable ChunkSnap get(int cx, int cz) {
        long key = ChunkPos.pack(cx, cz);
        Object held = cache.get(key);
        if (held != null) {
            hits.incrementAndGet();
            return held == NONE ? null : (ChunkSnap) held;
        }
        if (!existing.contains(key)) {
            if (scanned) {
                return null;
            }
            // Never answer "no such chunk" out of ignorance: a tile drawn before the first scan saw
            // every neighbour as missing, drew cut-away walls of stone, and an all-missing redraw
            // deleted a good tile outright — the grey squares and holes of a restarted server.
            ensureScanned();
            if (!existing.contains(key)) {
                return null;
            }
        }
        reads.incrementAndGet();
        ChunkSnap snap = load(new ChunkPos(cx, cz));
        // putIfAbsent, never put: while this read was on disk, a fresher save may have arrived
        // through accept(). Overwriting it with the older disk copy — often the half-generated
        // one, which reads as "no chunk" — left tiles drawn empty and deleted, inside the map.
        Object fresher = cache.putIfAbsent(key, snap == null ? NONE : snap);
        if (fresher != null) {
            return fresher == NONE ? null : (ChunkSnap) fresher;
        }
        return snap;
    }

    private @Nullable ChunkSnap load(ChunkPos pos) {
        ChunkMap chunkMap = level.getChunkSource().chunkMap;
        try {
            Optional<CompoundTag> read = chunkMap.read(pos).join();
            if (read.isEmpty()) {
                return null;
            }
            CompoundTag tag = read.get();
            int current = SharedConstants.getCurrentVersion().dataVersion().version();
            if (NbtUtils.getDataVersion(tag, -1) < current) {
                // A chunk nobody has visited since an upgrade. The game upgrades it on load; so must
                // we, or the palette names blocks that no longer exist.
                tag = chunkMap.upgradeChunkTag(tag, -1,
                        ChunkMap.getChunkDataFixContextTag(level.dimension(), level.getChunkSource().getGenerator().getTypeNameForDataFixer()),
                        current);
            }
            return ChunkSnap.of(SerializableChunkData.parse(level, level.palettedContainerFactory(), tag), keepLight, ceiling);
        } catch (RuntimeException e) {
            Diorama.LOGGER.debug("Diorama: could not read chunk {} in {}: {}", pos, level.dimension().identifier(), e.toString());
            return null;
        }
    }

    /** Fill {@link #existing} from the region headers. Returns how many chunks were found. */
    private volatile boolean scanned;

    private synchronized void ensureScanned() {
        if (!scanned) {
            scan();
        }
    }

    public int scan() {
        Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
        Path regions = DimensionType.getStorageFolder(level.dimension(), worldRoot).resolve("region");
        if (!Files.isDirectory(regions)) {
            scanned = true;
            return 0;
        }
        int found = 0;
        try (Stream<Path> files = Files.list(regions)) {
            for (Path file : files.toList()) {
                Matcher m = REGION.matcher(file.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                int rx = Integer.parseInt(m.group(1));
                int rz = Integer.parseInt(m.group(2));
                found += scanRegion(file, rx, rz);
            }
        } catch (IOException e) {
            Diorama.LOGGER.warn("Diorama: cannot list {} ({})", regions, e.getMessage());
        }
        scanned = true;
        return found;
    }

    private int scanRegion(Path file, int rx, int rz) {
        int found = 0;
        try (InputStream raw = Files.newInputStream(file); DataInputStream in = new DataInputStream(raw)) {
            for (int i = 0; i < 1024; i++) {
                // Sector offset and count, packed. Zero means the chunk was never written.
                if (in.readInt() != 0) {
                    existing.add(ChunkPos.pack((rx << 5) + (i & 31), (rz << 5) + (i >> 5)));
                    found++;
                }
            }
        } catch (IOException e) {
            // A region file shorter than its header is one the game is creating right now.
            Diorama.LOGGER.debug("Diorama: short region header {} ({})", file, e.getMessage());
        }
        return found;
    }
}
