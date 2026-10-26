package com.sablednah.diorama.world;

import java.util.List;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
    /** One above the highest non-air block, in world Y; {@code Integer.MIN_VALUE} for an empty chunk. */
    private final int topY;
    /** The lowest column top: no ray can meet this chunk's first block below it. */
    private final int lowestTop;

    private ChunkSnap(int minSectionY, @Nullable LevelChunkSection[] sections) {
        this.minSectionY = minSectionY;
        this.sections = sections;
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
        for (var s : list) {
            if (s.y() >= min && s.chunkSection() != null) {
                sections[s.y() - min] = s.chunkSection();
            }
        }
        return new ChunkSnap(min, sections);
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
