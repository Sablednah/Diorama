package com.sablednah.diorama.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.sablednah.diorama.world.ChunkSnap;
import com.sablednah.diorama.world.ChunkStore;
import net.minecraft.world.level.ChunkPos;

/**
 * The border of a set of chunks, laid on the ground.
 *
 * <p>An isometric map cannot draw an area flat: the same x, z lands somewhere different on screen
 * for every height, so a border drawn at sea level slides away from a hilltop claim it is meant to
 * outline. So every boundary edge is walked a block at a time and placed on top of the column just
 * inside the land — up hills, onto roofs, down to the water — with a vertical step wherever the
 * ground does. That is the same rule as Factions' own border walls in game.</p>
 *
 * <p>An edge is a chunk side whose neighbour is not in the set; a shared side between two chunks of
 * the same land is interior and draws nothing. Every edge is its own polyline. Merging them into a
 * traced ring would save points nobody counts and would need the holes handled.</p>
 */
public final class RegionOutline {

    private RegionOutline() {}

    /**
     * One polyline per boundary edge, each a flat {@code x, y, z, x, y, z, …} array in world
     * coordinates. A chunk the store cannot supply is placed at {@code fallbackY}.
     */
    public static List<double[]> trace(Set<Long> chunks, ChunkStore store, int fallbackY) {
        List<double[]> out = new ArrayList<>();
        for (long key : chunks) {
            int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
            ChunkSnap snap = store.get(cx, cz);
            int x0 = cx * 16, z0 = cz * 16;
            if (!chunks.contains(ChunkPos.pack(cx, cz - 1))) {
                out.add(edge(snap, fallbackY, x0, z0, 1, 0, x0, z0));               // north side
            }
            if (!chunks.contains(ChunkPos.pack(cx, cz + 1))) {
                out.add(edge(snap, fallbackY, x0, z0 + 16, 1, 0, x0, z0 + 15));     // south side
            }
            if (!chunks.contains(ChunkPos.pack(cx - 1, cz))) {
                out.add(edge(snap, fallbackY, x0, z0, 0, 1, x0, z0));               // west side
            }
            if (!chunks.contains(ChunkPos.pack(cx + 1, cz))) {
                out.add(edge(snap, fallbackY, x0 + 16, z0, 0, 1, x0 + 15, z0));     // east side
            }
        }
        return out;
    }

    /**
     * Sixteen blocks of one side, starting at line point {@code (lx, lz)} and stepping
     * {@code (sx, sz)}; heights come from the columns starting at {@code (cx, cz)} inside the land.
     * Consecutive blocks at one height collapse into a single segment.
     */
    private static double[] edge(ChunkSnap snap, int fallbackY, int lx, int lz, int sx, int sz, int cx, int cz) {
        List<Double> p = new ArrayList<>();
        int prev = Integer.MIN_VALUE;
        for (int i = 0; i < 16; i++) {
            int h = height(snap, fallbackY, cx + sx * i, cz + sz * i);
            double ax = lx + sx * i, az = lz + sz * i;
            if (h != prev) {
                if (prev != Integer.MIN_VALUE) {
                    add(p, ax, prev, az);   // finish the run at the old height: a vertical step follows
                }
                add(p, ax, h, az);
                prev = h;
            }
        }
        add(p, lx + sx * 16, prev, lz + sz * 16);
        double[] out = new double[p.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = p.get(i);
        }
        return out;
    }

    private static int height(ChunkSnap snap, int fallbackY, int x, int z) {
        if (snap == null) {
            return fallbackY;
        }
        int h = snap.surface(x, z);
        return h == Integer.MIN_VALUE ? fallbackY : h;
    }

    private static void add(List<Double> p, double x, double y, double z) {
        p.add(x);
        p.add(y);
        p.add(z);
    }

    /**
     * A polygon's outline as one closed polyline, sampled a block at a time along every edge so it
     * follows the ground — or flat at {@code fixedY} when the owner gave one (a region in the sky,
     * or one that should not ride over rooftops).
     */
    public static double[] tracePolygon(List<double[]> corners, Double fixedY, ChunkStore store, int fallbackY) {
        List<Double> p = new ArrayList<>();
        int n = corners.size();
        int prev = Integer.MIN_VALUE;
        for (int i = 0; i <= n; i++) {
            double[] a = corners.get(i % n);
            if (i == n) {
                // Close the ring at the height it was walking at.
                add(p, a[0], prev == Integer.MIN_VALUE ? heightAt(store, fixedY, fallbackY, a[0], a[1]) : prev, a[1]);
                break;
            }
            double[] b = corners.get((i + 1) % n);
            double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
            int steps = Math.max(1, (int) Math.ceil(len));
            for (int k = 0; k < steps; k++) {
                double t = (double) k / steps;
                double x = a[0] + (b[0] - a[0]) * t, z = a[1] + (b[1] - a[1]) * t;
                int h = heightAt(store, fixedY, fallbackY, x, z);
                if (h != prev) {
                    if (prev != Integer.MIN_VALUE) {
                        add(p, x, prev, z);
                    }
                    add(p, x, h, z);
                    prev = h;
                } else if (k == 0) {
                    // A corner is a turn whatever the ground does: skipping it at an unchanged
                    // height collapsed a flat polygon to a single point.
                    add(p, x, h, z);
                }
            }
        }
        double[] out = new double[p.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = p.get(i);
        }
        return out;
    }

    /** The middle of a polygon's corners, on the ground. Good enough for any shape a town is. */
    public static double[] anchor(List<double[]> corners, Double fixedY, ChunkStore store, int fallbackY) {
        double x = 0, z = 0;
        for (double[] c : corners) {
            x += c[0];
            z += c[1];
        }
        x /= corners.size();
        z /= corners.size();
        return new double[] {x, heightAt(store, fixedY, fallbackY, x, z), z};
    }

    private static int heightAt(ChunkStore store, Double fixedY, int fallbackY, double x, double z) {
        if (fixedY != null) {
            return (int) Math.round(fixedY);
        }
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        return height(store.get(bx >> 4, bz >> 4), fallbackY, bx, bz);
    }

    /**
     * The land as fill: one flat quad per chunk, at the chunk's typical ground height (the median
     * of five columns, so one tower does not lift the whole chunk). The page draws them all as a
     * single shape, so where neighbouring quads at different heights overlap on screen the fill is
     * not doubled into a darker patch.
     */
    public static List<double[]> fill(Set<Long> chunks, ChunkStore store, int fallbackY) {
        List<double[]> out = new ArrayList<>(chunks.size());
        for (long key : chunks) {
            int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
            ChunkSnap snap = store.get(cx, cz);
            int x0 = cx * 16, z0 = cz * 16;
            int[] h = {
                height(snap, fallbackY, x0 + 8, z0 + 8), height(snap, fallbackY, x0 + 2, z0 + 2),
                height(snap, fallbackY, x0 + 13, z0 + 2), height(snap, fallbackY, x0 + 2, z0 + 13),
                height(snap, fallbackY, x0 + 13, z0 + 13)};
            java.util.Arrays.sort(h);
            double y = h[2];
            out.add(new double[] {x0, y, z0, x0 + 16, y, z0, x0 + 16, y, z0 + 16, x0, y, z0 + 16});
        }
        return out;
    }

    /** A point inside the land to hang its name on: the claimed chunk nearest the middle of them all. */
    public static double[] anchor(Set<Long> chunks, ChunkStore store, int fallbackY) {
        double mx = 0, mz = 0;
        for (long k : chunks) {
            mx += ChunkPos.getX(k);
            mz += ChunkPos.getZ(k);
        }
        mx /= chunks.size();
        mz /= chunks.size();
        long best = 0;
        double bestD = Double.MAX_VALUE;
        for (long k : chunks) {
            double d = Math.pow(ChunkPos.getX(k) - mx, 2) + Math.pow(ChunkPos.getZ(k) - mz, 2);
            if (d < bestD) {
                bestD = d;
                best = k;
            }
        }
        int x = ChunkPos.getX(best) * 16 + 8, z = ChunkPos.getZ(best) * 16 + 8;
        return new double[] {x, height(store.get(ChunkPos.getX(best), ChunkPos.getZ(best)), fallbackY, x, z), z};
    }
}
