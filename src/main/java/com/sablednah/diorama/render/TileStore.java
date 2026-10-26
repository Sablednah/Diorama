package com.sablednah.diorama.render;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import javax.imageio.ImageIO;

import org.jspecify.annotations.Nullable;

/**
 * Tiles on disk: {@code <root>/<level>/<tx>_<ty>.png}, level 0 the most detailed.
 *
 * <p>Each zoom level out is the four tiles beneath it at half size, so only level 0 is ever ray
 * cast. Written through a temporary file and a rename, so a browser fetching a tile mid-write gets
 * the old one or the new one and never half of each.</p>
 */
public final class TileStore {

    /** Levels above 0. Eight halvings: a level-8 tile spans 256 detailed ones. */
    public static final int ZOOM_OUT_LEVELS = 8;

    private final Path root;

    public TileStore(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public Path file(int level, int tx, int ty) {
        return root.resolve(Integer.toString(level)).resolve(tx + "_" + ty + ".png");
    }

    /** Write, or delete when {@code argb} is null — a tile that went empty must not linger. */
    public void write(int level, int tx, int ty, int @Nullable [] argb) throws IOException {
        Path file = file(level, tx, ty);
        if (argb == null) {
            Files.deleteIfExists(file);
            return;
        }
        Files.createDirectories(file.getParent());
        BufferedImage image = new BufferedImage(IsoRenderer.TILE, IsoRenderer.TILE, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, IsoRenderer.TILE, IsoRenderer.TILE, argb, 0, IsoRenderer.TILE);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        ImageIO.write(image, "png", tmp.toFile());
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public int @Nullable [] read(int level, int tx, int ty) {
        Path file = file(level, tx, ty);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            BufferedImage image = ImageIO.read(file.toFile());
            if (image == null) {
                return null;
            }
            return image.getRGB(0, 0, IsoRenderer.TILE, IsoRenderer.TILE, null, 0, IsoRenderer.TILE);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Rebuild one zoomed-out tile from its four children, averaging in premultiplied alpha. */
    public void downsample(int level, int tx, int ty) throws IOException {
        int t = IsoRenderer.TILE;
        int half = t / 2;
        int[] out = new int[t * t];
        boolean any = false;
        for (int q = 0; q < 4; q++) {
            int[] child = read(level - 1, tx * 2 + (q & 1), ty * 2 + (q >> 1));
            if (child == null) {
                continue;
            }
            any = true;
            int ox = (q & 1) * half;
            int oy = (q >> 1) * half;
            for (int y = 0; y < half; y++) {
                for (int x = 0; x < half; x++) {
                    int i = (y * 2) * t + x * 2;
                    out[(oy + y) * t + ox + x] = average(child[i], child[i + 1], child[i + t], child[i + t + 1]);
                }
            }
        }
        write(level, tx, ty, any ? out : null);
    }

    private static int average(int c0, int c1, int c2, int c3) {
        int a0 = c0 >>> 24, a1 = c1 >>> 24, a2 = c2 >>> 24, a3 = c3 >>> 24;
        int a = a0 + a1 + a2 + a3;
        if (a == 0) {
            return 0;
        }
        int r = (((c0 >> 16) & 0xFF) * a0 + ((c1 >> 16) & 0xFF) * a1 + ((c2 >> 16) & 0xFF) * a2 + ((c3 >> 16) & 0xFF) * a3) / a;
        int g = (((c0 >> 8) & 0xFF) * a0 + ((c1 >> 8) & 0xFF) * a1 + ((c2 >> 8) & 0xFF) * a2 + ((c3 >> 8) & 0xFF) * a3) / a;
        int b = ((c0 & 0xFF) * a0 + (c1 & 0xFF) * a1 + (c2 & 0xFF) * a2 + (c3 & 0xFF) * a3) / a;
        return ((a / 4) << 24) | (r << 16) | (g << 8) | b;
    }
}
