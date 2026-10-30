package com.sablednah.diorama.model;

import com.sablednah.diorama.asset.Assets.Texture;

/**
 * One baked face, in block-local coordinates (a full cube is 0..1 on every axis).
 *
 * <p>Vertices are in vanilla's own order ({@code FaceInfo}), so {@code v0→v1} and {@code v0→v3}
 * are the two edges and every quad is a parallelogram. That is what lets the ray caster turn a hit
 * into a texture coordinate with two dot products instead of a general quad inversion.</p>
 *
 * @param o      vertex 0
 * @param e1     vertex 1 minus vertex 0
 * @param e3     vertex 3 minus vertex 0
 * @param n      outward normal, {@code e1 × e3}, not normalised
 * @param uv     u0 v0 u1 v1 u3 v3, each 0..1 across the texture
 * @param tint   the model's {@code tintindex}, or -1
 * @param shade  the directional light this face gets, 0..1; by the face's {@code shade_direction_override} where it has one
 */
public record Quad(float[] o, float[] e1, float[] e3, float[] n, float[] uv, Texture texture, int tint, float shade) {

    /**
     * Where along a ray this face is hit, writing {@code a, b} (the position across the two edges)
     * into {@code out}; {@code NaN} for a miss or a back face.
     *
     * <p>The direction is passed as three floats because every ray in a tile shares it.</p>
     */
    public float intersect(float px, float py, float pz, float dx, float dy, float dz, float[] out) {
        float denom = n[0] * dx + n[1] * dy + n[2] * dz;
        // Back faces are culled, as the game culls them. A face seen from behind is the inside of
        // something and would draw the wrong side of every thin model.
        if (denom >= 0f) {
            return Float.NaN;
        }
        float t = (n[0] * (o[0] - px) + n[1] * (o[1] - py) + n[2] * (o[2] - pz)) / denom;
        float hx = px + dx * t - o[0];
        float hy = py + dy * t - o[1];
        float hz = pz + dz * t - o[2];
        // Solve h = a·e1 + b·e3 through the Gram matrix, which is right for any parallelogram —
        // a rotated element scaled by "rescale" need not stay a rectangle.
        float d11 = e1[0] * e1[0] + e1[1] * e1[1] + e1[2] * e1[2];
        float d33 = e3[0] * e3[0] + e3[1] * e3[1] + e3[2] * e3[2];
        float d13 = e1[0] * e3[0] + e1[1] * e3[1] + e1[2] * e3[2];
        float h1 = hx * e1[0] + hy * e1[1] + hz * e1[2];
        float h3 = hx * e3[0] + hy * e3[1] + hz * e3[2];
        float det = d11 * d33 - d13 * d13;
        if (det == 0f) {
            return Float.NaN;
        }
        float a = (h1 * d33 - h3 * d13) / det;
        float b = (h3 * d11 - h1 * d13) / det;
        if (a < 0f || a > 1f || b < 0f || b > 1f) {
            return Float.NaN;
        }
        out[0] = a;
        out[1] = b;
        return t;
    }

    /** The texel at a hit, as ARGB. */
    public int sample(float a, float b) {
        float u = uv[0] + a * (uv[2] - uv[0]) + b * (uv[4] - uv[0]);
        float v = uv[1] + a * (uv[3] - uv[1]) + b * (uv[5] - uv[1]);
        return texture.sample(u, v);
    }
}
