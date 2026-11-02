package com.sablednah.diorama.neoforge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;

/**
 * Chunk copies handed over by saves (and live-update copies), waiting for the coordinator.
 *
 * <p><b>The newest copy of each chunk, not every copy.</b> Each entry is a whole
 * {@link SerializableChunkData}; as a FIFO, players exploring during an hours-long render queued
 * one per save per chunk and could push the heap into gigabytes. Keyed by world and chunk, a second
 * save of the same chunk replaces the first — which loses nothing, because the newer copy is a
 * superset of what the map wanted from the older one. The size is bounded by distinct chunks.</p>
 *
 * <p>A chunk that is not {@link ChunkStatus#FULL} is not kept at all: {@code ChunkSnap.of}
 * discards it anyway, and worldgen saves a lot of them.</p>
 */
final class SaveQueue {

    record Key(ResourceKey<Level> level, long pos) {}

    record Saved(ResourceKey<Level> level, ChunkPos pos, SerializableChunkData data) {}

    private final Map<Key, Saved> pending = new ConcurrentHashMap<>();

    /** Any thread. Returns whether the copy was kept; a non-FULL chunk is not. */
    boolean offer(ResourceKey<Level> level, ChunkPos pos, SerializableChunkData data) {
        if (data.chunkStatus() != ChunkStatus.FULL) {
            return false;
        }
        pending.put(new Key(level, pos.pack()), new Saved(level, pos, data));
        return true;
    }

    int size() {
        return pending.size();
    }

    /**
     * Take everything waiting. A copy that arrives while this runs is either taken now or left for
     * the next drain — never lost, because removal is by key and a newer put simply waits.
     */
    void drain(java.util.function.Consumer<Saved> each) {
        for (Key k : pending.keySet()) {
            Saved s = pending.remove(k);
            if (s != null) {
                each.accept(s);
            }
        }
    }
}
