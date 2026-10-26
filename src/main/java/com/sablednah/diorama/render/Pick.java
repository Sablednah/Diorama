package com.sablednah.diorama.render;

import com.sablednah.diorama.world.ChunkSnap;
import com.sablednah.diorama.world.ChunkStore;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * What is under the cursor: the first block the ray through one map pixel meets.
 *
 * <p>A point on an isometric map is a whole diagonal line through the world, so the page cannot
 * know which block it is pointing at — guessing a height (sea level) put the readout tens of blocks
 * off on any hill or roof. The server holds the blocks, so it casts the same ray the renderer does,
 * from the same projection, and stops at the first block that is not air. A tuft of grass or a pane
 * of glass counts: it is what you are looking at.</p>
 */
public final class Pick {

    /** The block hit and the biome it stands in. */
    public record Hit(int x, int y, int z, BlockState block, @Nullable Holder<Biome> biome) {}

    private Pick() {}

    /**
     * @param sx, sy  the pixel at the most detailed zoom, in the same units the tiles are cut in
     */
    public static @Nullable Hit at(ChunkStore store, View view, int pixelsPerBlock, double sx, double sy) {
        int yTop = store.level().getMaxY() + 1, yBottom = store.level().getMinY();
        double u = 2.0 * sx / pixelsPerBlock, s = 4.0 * sy / pixelsPerBlock;
        double vxs = (u + s) * 0.5 + yTop, vzs = (s - u) * 0.5 + yTop;
        double ox = view.worldX(vxs, vzs), oy = yTop, oz = view.worldZ(vxs, vzs);
        int dx = view.rayX(), dz = view.rayZ();
        double t = 0;
        for (int guard = 0; guard < 4096; guard++) {
            double x = ox + dx * t, y = oy - t, z = oz + dz * t;
            int vx = IsoRenderer.enter(x, dx), vy = IsoRenderer.enter(y, -1), vz = IsoRenderer.enter(z, dz);
            if (vy < yBottom) {
                return null;
            }
            ChunkSnap chunk = store.get(vx >> 4, vz >> 4);
            if (chunk == null || chunk.section(vy) == null) {
                double skip = Math.min(IsoRenderer.toEdge(x, vx & ~15, dx, 16),
                        Math.min(y - (vy & ~15), IsoRenderer.toEdge(z, vz & ~15, dz, 16)));
                t += Math.max(skip, 1e-4);
                continue;
            }
            BlockState state = chunk.block(vx, vy, vz);
            if (!state.isAir()) {
                return new Hit(vx, vy, vz, state, chunk.biome(vx, vy, vz));
            }
            t += Math.min(IsoRenderer.toEdge(x, vx, dx, 1), Math.min(y - vy, IsoRenderer.toEdge(z, vz, dz, 1))) + 1e-5;
        }
        return null;
    }
}
