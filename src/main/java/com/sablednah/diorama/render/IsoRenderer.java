package com.sablednah.diorama.render;

import java.util.HashMap;
import java.util.Map;

import com.sablednah.diorama.asset.Assets;
import com.sablednah.diorama.model.BlockModels;
import com.sablednah.diorama.model.Quad;
import com.sablednah.diorama.world.ChunkSnap;
import com.sablednah.diorama.world.ChunkStore;
import net.minecraft.core.Holder;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

/**
 * Draws one isometric tile by casting a ray per pixel through the world.
 *
 * <h2>The projection</h2>
 *
 * <p>The camera sits to the south-east, above, looking north-west — Overviewer's and Dynmap's
 * classic angle. A block is {@code W} pixels wide; its top face is a diamond {@code W} wide and
 * {@code W/2} tall, and a vertical edge is {@code W/2} tall:</p>
 *
 * <pre>
 *   sx = (x − z) · W/2
 *   sy = (x + z) · W/4 − y · W/2
 * </pre>
 *
 * <p>Solve that for a pixel and every world point behind it lies on one line, and the line runs
 * along {@code (−1, −1, −1)}: one block down, one west, one north. Every ray in every tile shares
 * that direction, so the march is the simplest possible voxel walk — all three axes step the same
 * way — and the camera sees exactly three faces of a cube: up, east and south.</p>
 *
 * <h2>Why rays rather than painting blocks</h2>
 *
 * <p>Occlusion is free: the first opaque texel a ray meets ends it, so the stone under a mountain
 * costs nothing however much of it there is. And each pixel is resolved against the real model
 * faces with their real textures, so a stair is a stair and a fence is a fence.</p>
 *
 * <p>Instances are cheap and not thread-safe: one per render thread.</p>
 */
public final class IsoRenderer {

    public static final int TILE = 256;
    private static final int WATER_ALPHA = 190;

    private final BlockModels models;
    private final Assets.Texture lava;
    private final int pixelsPerBlock;

    private final float[] hitAB = new float[2];
    private final Map<Long, ChunkSnap> chunks = new HashMap<>();
    private @Nullable ChunkStore store;

    // The hit list for one voxel, front to back. A voxel can hold several faces a ray passes
    // through (glass in a frame, a waterlogged slab): they composite in order.
    private final float[] hitT = new float[64];
    private final int[] hitColor = new int[64];
    private int hits;

    public IsoRenderer(BlockModels models, int pixelsPerBlock) {
        this.models = models;
        this.pixelsPerBlock = pixelsPerBlock;
        this.lava = models.assets().texture("minecraft:block/lava_still");
    }

    /**
     * @param yTop the highest block top anywhere this tile could see
     * @return the tile as ARGB, or {@code null} when nothing at all was hit
     */
    public int @Nullable [] render(ChunkStore store, int tx, int ty, int yTop, int yBottom) {
        this.store = store;
        chunks.clear();
        int[] out = new int[TILE * TILE];
        boolean any = false;
        float w = pixelsPerBlock;
        for (int py = 0; py < TILE; py++) {
            for (int px = 0; px < TILE; px++) {
                float sx = tx * TILE + px + 0.5f;
                float sy = ty * TILE + py + 0.5f;
                int c = castRay(2f * sx / w, 4f * sy / w, yTop, yBottom);
                out[py * TILE + px] = c;
                any |= c != 0;
            }
        }
        this.store = null;
        return any ? out : null;
    }

    /**
     * {@code u = x − z} and {@code s = x + z − 2y} identify the ray; it starts at {@code yTop}.
     */
    private int castRay(float u, float s, int yTop, int yBottom) {
        // Start point: on the ray, at height yTop.
        float ox = (u + s) * 0.5f + yTop;
        float oy = yTop;
        float oz = (s - u) * 0.5f + yTop;

        // Front-to-back compositing, premultiplied.
        float r = 0, g = 0, b = 0, a = 0;
        boolean inWater = false;

        float t = 0f;
        for (int guard = 0; guard < 4096; guard++) {
            // The voxel the ray is entering: on an exact boundary it is the one below/west/north.
            float x = ox - t, y = oy - t, z = oz - t;
            int vx = ceilMinusOne(x), vy = ceilMinusOne(y), vz = ceilMinusOne(z);
            if (vy < yBottom) {
                break;
            }
            float tExit = t + Math.min(x - vx, Math.min(y - vy, z - vz));

            ChunkSnap chunk = chunk(vx >> 4, vz >> 4);
            if (chunk == null || chunk.section(vy) == null) {
                // Nothing in this 16³ cell: jump to where the ray leaves it.
                int cx = vx & ~15, cy = vy & ~15, cz = vz & ~15;
                float skip = Math.min(x - cx, Math.min(y - cy, z - cz));
                t += Math.max(skip, 1e-4f);
                inWater = false;
                continue;
            }

            BlockState state = chunk.block(vx, vy, vz);
            hits = 0;
            if (!state.isAir()) {
                collectModelHits(state, chunk, vx, vy, vz, ox, oy, oz, t, tExit);
            }
            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty()) {
                boolean isWater = fluid.is(FluidTags.WATER);
                float height = fluidHeight(chunk, vx, vy, vz, fluid);
                float enterY = y - vy;
                float tHit = enterY > height ? t + (enterY - height) : t;
                boolean entered = tHit <= tExit;
                if (entered && !(isWater && inWater)) {
                    int c = fluidColor(isWater, chunk, vx, vy, vz, x - (tHit - t) - vx, z - (tHit - t) - vz, enterY > height);
                    addHit(tHit, c);
                }
                // Only a ray that actually went into the water is "in" it. One that clipped the
                // gap above a surface and left must still see the next block's surface — getting
                // this wrong drew a pale line along every block edge of every lake.
                inWater = isWater && entered;
            } else {
                inWater = false;
            }

            if (hits > 0) {
                sortHits();
                for (int i = 0; i < hits; i++) {
                    int c = hitColor[i];
                    float ca = (c >>> 24) / 255f;
                    float k = (1f - a) * ca;
                    r += k * ((c >> 16) & 0xFF);
                    g += k * ((c >> 8) & 0xFF);
                    b += k * (c & 0xFF);
                    a += k;
                    if (a > 0.995f) {
                        return pack(r, g, b, 1f);
                    }
                }
            }
            t = tExit + 1e-5f;
        }
        return a <= 0.004f ? 0 : pack(r / a, g / a, b / a, a);
    }

    private void collectModelHits(BlockState state, ChunkSnap chunk, int vx, int vy, int vz,
                                  float ox, float oy, float oz, float tEnter, float tExit) {
        BlockModels.Baked baked = models.get(state);
        Quad[] quads = baked.pick(vx, vy, vz);
        if (quads.length == 0) {
            return;
        }
        // Local origin: the ray's start relative to this voxel's corner. t stays global.
        float lx = ox - vx, ly = oy - vy, lz = oz - vz;
        Holder<Biome> biome = null;
        boolean biomeLooked = false;
        for (Quad q : quads) {
            float th = q.intersect(lx, ly, lz, -1f, -1f, -1f, hitAB);
            if (Float.isNaN(th) || th < tEnter - 1e-4f || th > tExit + 1e-4f) {
                continue;
            }
            int texel = q.sample(hitAB[0], hitAB[1]);
            int alpha = texel >>> 24;
            if (alpha < 8) {
                continue; // cutout: leaves, flowers, the gaps in a fence texture
            }
            int c = texel;
            if (q.tint() >= 0) {
                if (!biomeLooked) {
                    biome = chunk.biome(vx, vy, vz);
                    biomeLooked = true;
                }
                c = multiply(c, BlockTints.tint(state, q.tint(), biome, vx, vz));
            }
            addHit(th, scale(c, q.shade()));
        }
    }

    private float fluidHeight(ChunkSnap chunk, int x, int y, int z, FluidState fluid) {
        // A fluid with the same fluid above it fills its block; otherwise its own level decides.
        FluidState above = chunk.block(x, y + 1, z).getFluidState();
        if (!above.isEmpty() && above.getType().isSame(fluid.getType())) {
            return 1f;
        }
        // A still surface is drawn flat at the block top. In game it sits 1/9 lower, and at map
        // scale that sliver only shows up as seams between blocks. Flowing fluid keeps its slope.
        if (fluid.isSource()) {
            return 1f;
        }
        return Math.max(0.1f, fluid.getOwnHeight());
    }

    private int fluidColor(boolean isWater, ChunkSnap chunk, int x, int y, int z, float lx, float lz, boolean top) {
        if (isWater) {
            // Flat biome water, not the water texture. Repeated per block at map scale, the
            // texture reads as a blue-and-white grid; the lake the player sees in game is one
            // sheet. Opaque enough to read as water, clear enough to show the shallows.
            int tint = BlockTints.tint(net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(), 0, chunk.biome(x, y, z), x, z);
            return scale((WATER_ALPHA << 24) | (tint & 0x00FFFFFF), top ? 1f : 0.8f);
        }
        return scale(lava.sample(frac(lx), frac(lz)) | 0xFF000000, top ? 1f : 0.8f);
    }

    private @Nullable ChunkSnap chunk(int cx, int cz) {
        long key = ChunkPos.pack(cx, cz);
        ChunkSnap snap = chunks.get(key);
        if (snap == null && !chunks.containsKey(key)) {
            snap = store.get(cx, cz);
            chunks.put(key, snap);
        }
        return snap;
    }

    private void addHit(float t, int color) {
        if (hits < hitT.length) {
            hitT[hits] = t;
            hitColor[hits] = color;
            hits++;
        }
    }

    private void sortHits() {
        for (int i = 1; i < hits; i++) {
            float kt = hitT[i];
            int kc = hitColor[i];
            int j = i - 1;
            while (j >= 0 && hitT[j] > kt) {
                hitT[j + 1] = hitT[j];
                hitColor[j + 1] = hitColor[j];
                j--;
            }
            hitT[j + 1] = kt;
            hitColor[j + 1] = kc;
        }
    }

    private static int ceilMinusOne(float v) {
        return (int) Math.ceil(v) - 1;
    }

    private static float frac(float v) {
        return v - (float) Math.floor(v);
    }

    static int multiply(int argb, int tint) {
        if (tint == -1) {
            return argb;
        }
        int r = ((argb >> 16) & 0xFF) * ((tint >> 16) & 0xFF) / 255;
        int g = ((argb >> 8) & 0xFF) * ((tint >> 8) & 0xFF) / 255;
        int b = (argb & 0xFF) * (tint & 0xFF) / 255;
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    static int scale(int argb, float k) {
        if (k >= 1f) {
            return argb;
        }
        int r = (int) (((argb >> 16) & 0xFF) * k);
        int g = (int) (((argb >> 8) & 0xFF) * k);
        int b = (int) ((argb & 0xFF) * k);
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static int pack(float r, float g, float b, float a) {
        int ir = Math.min(255, Math.round(r));
        int ig = Math.min(255, Math.round(g));
        int ib = Math.min(255, Math.round(b));
        int ia = Math.min(255, Math.round(a * 255f));
        return (ia << 24) | (ir << 16) | (ig << 8) | ib;
    }
}
