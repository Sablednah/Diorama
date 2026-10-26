package com.sablednah.diorama.world;

import java.util.List;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.jspecify.annotations.Nullable;

/**
 * A chunk's blocks and biomes, frozen, for the render threads to read.
 *
 * <p>Built from {@link SerializableChunkData}, which is already a copy: the game makes it on the
 * server thread for every save, and hands it to us through {@code ChunkDataEvent.Save} before
 * encoding it on a background thread. The same type comes out of parsing a chunk from disk, so a
 * live chunk and a stored one are one code path.</p>
 *
 * <p>Nothing here may be written after construction — every render thread reads it at once.</p>
 */
public final class ChunkSnap {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final int minSectionY;
    private final @Nullable LevelChunkSection[] sections;
    /**
     * Light, per section, kept only when night tiles are drawn: up to 4 KiB a section, which across
     * a whole chunk cache is real heap. {@code null} arrays mean "not kept"; a {@code null} entry
     * means the save stored no layer for that section.
     */
    private final @Nullable DataLayer @Nullable [] sky;
    private final @Nullable DataLayer @Nullable [] blockLight;
    /** One above the highest non-air block, in world Y; {@code Integer.MIN_VALUE} for an empty chunk. */
    private final int topY;
    /** The lowest column top: no ray can meet this chunk's first block below it. */
    private final int lowestTop;

    private ChunkSnap(int minSectionY, @Nullable LevelChunkSection[] sections,
                      @Nullable DataLayer @Nullable [] sky, @Nullable DataLayer @Nullable [] blockLight) {
        this.minSectionY = minSectionY;
        this.sections = sections;
        this.sky = sky;
        this.blockLight = blockLight;
        int top = Integer.MIN_VALUE;
        int low = Integer.MAX_VALUE;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int t = columnTop(x, z);
                top = Math.max(top, t);
                if (t != Integer.MIN_VALUE) {
                    low = Math.min(low, t);
                }
            }
        }
        this.topY = top;
        this.lowestTop = low == Integer.MAX_VALUE ? top : low;
    }

    /** Only full chunks: a half-generated one at the world's edge is not something a player saw. */
    public static @Nullable ChunkSnap of(SerializableChunkData data) {
        return of(data, false);
    }

    /** As {@link #of(SerializableChunkData)}, keeping the light layers when {@code keepLight}. */
    public static @Nullable ChunkSnap of(SerializableChunkData data, boolean keepLight) {
        if (data.chunkStatus() != ChunkStatus.FULL) {
            return null;
        }
        List<SerializableChunkData.SectionData> list = data.sectionData();
        int min = data.minSectionY();
        int max = min;
        for (var s : list) {
            max = Math.max(max, s.y());
        }
        LevelChunkSection[] sections = new LevelChunkSection[max - min + 1];
        DataLayer[] sky = keepLight ? new DataLayer[sections.length] : null;
        DataLayer[] block = keepLight ? new DataLayer[sections.length] : null;
        for (var s : list) {
            if (s.y() < min) {
                continue;
            }
            int i = s.y() - min;
            if (s.chunkSection() != null) {
                sections[i] = s.chunkSection();
            }
            if (keepLight) {
                sky[i] = s.skyLight();
                block[i] = s.blockLight();
            }
        }
        return new ChunkSnap(min, sections, sky, block);
    }

    public int topY() {
        return topY;
    }

    public int lowestTop() {
        return lowestTop;
    }

    /** The section holding world Y {@code y}, or {@code null} when it is all air. */
    public @Nullable LevelChunkSection section(int y) {
        int i = (y >> 4) - minSectionY;
        if (i < 0 || i >= sections.length) {
            return null;
        }
        LevelChunkSection s = sections[i];
        return s == null || s.hasOnlyAir() ? null : s;
    }

    public BlockState block(int x, int y, int z) {
        LevelChunkSection s = section(y);
        return s == null ? AIR : s.getBlockState(x & 15, y & 15, z & 15);
    }

    /**
     * Sky light 0..15 at a block. A section with no stored layer is read as open sky when it holds
     * no blocks (the air above the terrain, where the game keeps nothing) and as dark when it does
     * (sealed rock). Above the stored range is open sky. Without kept light, always 15.
     */
    public int skyLight(int x, int y, int z) {
        if (sky == null) {
            return 15;
        }
        int i = (y >> 4) - minSectionY;
        if (i >= sky.length) {
            return 15;
        }
        if (i < 0) {
            return 0;
        }
        DataLayer l = sky[i];
        if (l == null) {
            return section(y) == null ? 15 : 0;
        }
        return l.get(x & 15, y & 15, z & 15);
    }

    /** Block light 0..15 — torches, lamps, lava. */
    public int blockLight(int x, int y, int z) {
        if (blockLight == null) {
            return 0;
        }
        int i = (y >> 4) - minSectionY;
        if (i < 0 || i >= blockLight.length || blockLight[i] == null) {
            return 0;
        }
        return blockLight[i].get(x & 15, y & 15, z & 15);
    }

    public @Nullable Holder<Biome> biome(int x, int y, int z) {
        int i = (y >> 4) - minSectionY;
        if (i < 0 || i >= sections.length || sections[i] == null) {
            return null;
        }
        return sections[i].getNoiseBiome((x & 15) >> 2, (y & 15) >> 2, (z & 15) >> 2);
    }

    private int columnTop(int x, int z) {
        for (int i = sections.length - 1; i >= 0; i--) {
            LevelChunkSection s = sections[i];
            if (s == null || s.hasOnlyAir()) {
                continue;
            }
            for (int y = 15; y >= 0; y--) {
                if (!s.getBlockState(x, y, z).isAir()) {
                    return ((i + minSectionY) << 4) + y + 1;
                }
            }
        }
        return Integer.MIN_VALUE;
    }
}
