package com.sablednah.diorama.render;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * Where the camera stands: one of four quarter-turns around the world.
 *
 * <p>Each view is a rotation into "view space" — {@code x' = a·x + b·z}, {@code z' = c·x + d·z} —
 * chosen so the camera always sits at {@code +x', +z'}. The projection then never changes; only
 * the world the ray walks through is turned. Every rotation here is proper (determinant +1): a
 * mirror would put the sun on the wrong side and draw every staircase backwards.</p>
 *
 * <p>The name is the corner the camera stands at, Dynmap's convention: {@code se} looks
 * north-west and sees the south and east faces.</p>
 */
public enum View {
    SE(1, 0, 0, 1),
    SW(0, 1, -1, 0),
    NW(-1, 0, 0, -1),
    NE(0, -1, 1, 0);

    private final int a, b, c, d;

    View(int a, int b, int c, int d) {
        this.a = a;
        this.b = b;
        this.c = c;
        this.d = d;
    }

    /** The path segment, {@code se}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static @Nullable View byId(String id) {
        for (View v : values()) {
            if (v.id().equals(id)) {
                return v;
            }
        }
        return null;
    }

    public double viewX(double x, double z) {
        return a * x + b * z;
    }

    public double viewZ(double x, double z) {
        return c * x + d * z;
    }

    /** View space back to the world: the transpose, since the rotation is orthonormal. */
    public double worldX(double vx, double vz) {
        return a * vx + c * vz;
    }

    public double worldZ(double vx, double vz) {
        return b * vx + d * vz;
    }

    /** The ray's step in world X: every ray in view space runs along (−1, −1, −1). */
    public int rayX() {
        return -(a + c);
    }

    public int rayZ() {
        return -(b + d);
    }
}
