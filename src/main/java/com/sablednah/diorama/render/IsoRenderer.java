package com.sablednah.diorama.render;

import java.util.HashMap;
import java.util.Map;

import com.sablednah.diorama.asset.Assets;
import com.sablednah.diorama.model.BlockModels;
import com.sablednah.diorama.model.Quad;
import com.sablednah.diorama.world.ChunkSnap;
import com.sablednah.diorama.world.ChunkStore;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
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
    private int lastCx, lastCz;
    private @Nullable ChunkSnap lastSnap;
    private boolean lastValid;

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
    public int @Nullable [] render(ChunkStore store, View view, boolean night, int tx, int ty, int yTop, int yBottom) {
        this.store = store;
        this.night = night;
        this.dx = view.rayX();
        this.dz = view.rayZ();
        chunks.clear();
        lastValid = false;
        int[] out = new int[TILE * TILE];
        boolean any = false;
        float w = pixelsPerBlock;
        for (int py = 0; py < TILE; py++) {
            for (int px = 0; px < TILE; px++) {
                double sx = (double) tx * TILE + px + 0.5;
                double sy = (double) ty * TILE + py + 0.5;
                // u = x' − z' and s = x' + z' − 2y identify the ray in view space; start it at yTop.
                double u = 2.0 * sx / w, s = 4.0 * sy / w;
                double vxs = (u + s) * 0.5 + yTop;
                double vzs = (s - u) * 0.5 + yTop;
                int c = castRay(view.worldX(vxs, vzs), yTop, view.worldZ(vxs, vzs), yBottom);
                out[py * TILE + px] = c;
                any |= c != 0;
            }
        }
        this.store = null;
        return any ? out : null;
    }

    /** Whether this tile is lit by night. Day tiles are drawn exactly as they always were. */
    private boolean night;

    /** The world step of the current view's rays: Y is always −1, X and Z are ±1. */
    private int dx = -1, dz = -1;

    /**
     * March from {@code (ox, oy, oz)} along {@code (dx, −1, dz)}. Every component is ±1, so the
     * distance to the next boundary on an axis is measured in the same units as {@code t}.
     *
     * <p>Positions are doubles. A float holds a coordinate a few thousand blocks out to about a
     * millimetre, and the nudge past each boundary is smaller than that, so far from spawn a float
     * ray stops advancing and spins out its step budget drawing nothing.</p>
     */
    private int castRay(double ox, double oy, double oz, int yBottom) {
        // Front-to-back compositing, premultiplied.
        float r = 0, g = 0, b = 0, a = 0;
        boolean inWater = false;

        double t = 0;
        for (int guard = 0; guard < 4096; guard++) {
            double x = ox + dx * t, y = oy - t, z = oz + dz * t;
            // The voxel the ray is entering. On an exact boundary that is the one it is moving
            // into, which is below on a falling axis and above on a rising one.
            int vx = enter(x, dx), vy = enter(y, -1), vz = enter(z, dz);
            if (vy < yBottom) {
                break;
            }
            double tExit = t + Math.min(toEdge(x, vx, dx, 1), Math.min(y - vy, toEdge(z, vz, dz, 1)));

            ChunkSnap chunk = chunk(vx >> 4, vz >> 4);
            if (chunk == null || chunk.section(vy) == null) {
                // Nothing in this 16³ cell: jump to where the ray leaves it.
                double skip = Math.min(toEdge(x, vx & ~15, dx, 16), Math.min(y - (vy & ~15), toEdge(z, vz & ~15, dz, 16)));
                t += Math.max(skip, 1e-4f);
                inWater = false;
                continue;
            }

            BlockState state = chunk.block(vx, vy, vz);
            if (state.isAir()) {
                // Most steps. Air holds no fluid and no faces: nothing to look up at all.
                inWater = false;
                t = tExit + 1e-5f;
                continue;
            }
            if (chunk.roof(vx, vy, vz)) {
                // The roof's rock: lifted off, so the cavern floors below show.
                t = tExit + 1e-5f;
                continue;
            }
            hits = 0;
            collectModelHits(state, chunk, vx, vy, vz, ox, oy, oz, (float) t, (float) tExit);
            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty()) {
                // A type comparison, not a tag lookup: the tag version was a visible slice of the profile.
                boolean isWater = fluid.getType().isSame(Fluids.WATER);
                float height = fluidHeight(chunk, vx, vy, vz, fluid);
                double enterY = y - vy;
                double tHit = enterY > height ? t + (enterY - height) : t;
                boolean entered = tHit <= tExit;
                if (entered && !(isWater && inWater)) {
                    float lx = (float) (ox + dx * tHit - vx), lz = (float) (oz + dz * tHit - vz);
                    addHit((float) tHit, fluidColor(isWater, chunk, vx, vy, vz, lx, lz, enterY > height));
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
                if (night) {
                    // Light comes from the block the ray crossed just before this one: the air in
                    // front of a face, which is the value the game lights that face by too.
                    double back = t - 1e-3;
                    int bx = enter(ox + dx * back, dx), by = enter(oy - back, -1), bz = enter(oz + dz * back, dz);
                    ChunkSnap lit = chunk(bx >> 4, bz >> 4);
                    int sky = lit == null ? 15 : lit.skyLight(bx, by, bz);
                    int block = lit == null ? 0 : lit.blockLight(bx, by, bz);
                    nightLight(sky, block);
                    for (int i = 0; i < hits; i++) {
                        hitColor[i] = lightUp(hitColor[i]);
                    }
                }
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

    /** The cell a ray at {@code p} is entering along {@code dir}. */
    static int enter(double p, int dir) {
        return dir < 0 ? (int) Math.ceil(p) - 1 : (int) Math.floor(p);
    }

    /** Distance along the ray to leave a cell of {@code size} whose low corner is {@code low}. */
    static double toEdge(double p, int low, int dir, int size) {
        return dir < 0 ? p - low : low + size - p;
    }

    private void collectModelHits(BlockState state, ChunkSnap chunk, int vx, int vy, int vz,
                                  double ox, double oy, double oz, float tEnter, float tExit) {
        BlockModels.Baked baked = models.get(state);
        Quad[] quads = baked.pick(vx, vy, vz);
        if (quads.length == 0) {
            return;
        }
        // Local origin: the ray's start relative to this voxel's corner. t stays global.
        float lx = (float) (ox - vx), ly = (float) (oy - vy), lz = (float) (oz - vz);
        Holder<Biome> biome = null;
        boolean biomeLooked = false;
        for (Quad q : quads) {
            float th = q.intersect(lx, ly, lz, dx, -1f, dz, hitAB);
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

    /**
     * The chunk a ray step is in. A ray crosses a chunk boundary about once every sixteen steps, so
     * the last answer is almost always the right one; the map behind it was the single largest
     * cost in the profile (boxing a {@code Long} key on every step).
     */
    private @Nullable ChunkSnap chunk(int cx, int cz) {
        if (cx == lastCx && cz == lastCz && lastValid) {
            return lastSnap;
        }
        long key = ChunkPos.pack(cx, cz);
        ChunkSnap snap = chunks.get(key);
        if (snap == null && !chunks.containsKey(key)) {
            snap = store.get(cx, cz);
            chunks.put(key, snap);
        }
        lastCx = cx;
        lastCz = cz;
        lastSnap = snap;
        lastValid = true;
        return snap;
    }

    private float lightR, lightG, lightB;

    /**
     * Night light for a face: the brighter of torchlight (warm) and moonlight (cool and dim), on
     * the game's own brightness curve {@code f / (4 − 3f)}, so a torch-lit street reads the way it
     * does in game and open ground falls to a blue near-dark.
     */
    private void nightLight(int sky, int block) {
        float s = curve(sky) * 0.32f, k = curve(block);
        lightR = Math.min(1f, Math.max(k, s * 0.55f) + 0.03f);
        lightG = Math.min(1f, Math.max(k * 0.88f, s * 0.62f) + 0.03f);
        lightB = Math.min(1f, Math.max(k * 0.68f, s * 0.95f) + 0.04f);
    }

    private static float curve(int level) {
        float f = level / 15f;
        return f / (4f - 3f * f);
    }

    private int lightUp(int c) {
        int r = (int) (((c >> 16) & 0xFF) * lightR);
        int g = (int) (((c >> 8) & 0xFF) * lightG);
        int b = (int) ((c & 0xFF) * lightB);
        return (c & 0xFF000000) | (r << 16) | (g << 8) | b;
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
