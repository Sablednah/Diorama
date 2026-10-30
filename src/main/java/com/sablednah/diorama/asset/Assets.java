package com.sablednah.diorama.asset;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sablednah.diorama.Diorama;
import org.jspecify.annotations.Nullable;

/**
 * The stack of sources block models and textures are read from, first match wins:
 *
 * <ol>
 * <li><b>{@code diorama/assets/}</b> — anything the owner drops there: a resource pack (zip or
 *     folder) to restyle the map, or a client jar fetched by hand where the server cannot reach
 *     Mojang.</li>
 * <li><b>Every loaded mod.</b> Mod jars carry their {@code assets/} even on a dedicated server, so
 *     modded blocks draw from their own models. In single player the {@code minecraft} mod file is
 *     the client itself, which is why single player needs no download.</li>
 * <li><b>The downloaded client jar</b>, only when nothing above carries vanilla's models and the
 *     owner accepted the download.</li>
 * </ol>
 *
 * <p>The whole point, and the lesson from Schemy: every block is drawn from the JSON the game
 * itself draws it from. Nothing here knows what a stair looks like.</p>
 */
public final class Assets {

    /** The probe for "do we have vanilla's models": a block every version has had. */
    public static final String VANILLA_PROBE = "assets/minecraft/blockstates/stone.json";

    private final List<AssetSource> sources;
    private final Map<String, Texture> textures = new ConcurrentHashMap<>();
    private volatile @Nullable Map<String, String> atlasAliases;

    public Assets(List<AssetSource> sources) {
        this.sources = List.copyOf(sources);
    }

    public List<AssetSource> sources() {
        return sources;
    }

    public boolean hasVanilla() {
        return read(VANILLA_PROBE) != null;
    }

    public byte @Nullable [] read(String path) {
        for (AssetSource source : sources) {
            try {
                byte[] bytes = source.read(path);
                if (bytes != null) {
                    return bytes;
                }
            } catch (IOException e) {
                Diorama.LOGGER.debug("Diorama: {} failed reading {}", source.name(), path, e);
            }
        }
        return null;
    }

    /** {@code kind} is the folder: {@code blockstates} or {@code models}. */
    public @Nullable JsonObject json(String kind, String namespace, String path) {
        byte[] bytes = read("assets/" + namespace + "/" + kind + "/" + path + ".json");
        if (bytes == null) {
            return null;
        }
        try {
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            Diorama.LOGGER.warn("Diorama: unreadable {} {}:{} ({})", kind, namespace, path, e.getMessage());
            return null;
        }
    }

    /**
     * A texture by its model id, {@code minecraft:block/stone}. Never null: a missing texture comes
     * back as the magenta checkerboard, the same signal the game itself gives.
     */
    public Texture texture(String id) {
        return textures.computeIfAbsent(id, k -> loadTexture(k, true));
    }

    private Texture loadTexture(String id, boolean followAlias) {
        int colon = id.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : id.substring(0, colon);
        String path = colon < 0 ? id : id.substring(colon + 1);
        String file = "assets/" + namespace + "/textures/" + path + ".png";
        byte[] bytes = read(file);
        if (bytes == null) {
            // One hop, read directly: texture() here would re-enter the map's computeIfAbsent.
            String target = followAlias ? atlasAliases().get(namespace + ":" + path) : null;
            return target == null ? Texture.MISSING : loadTexture(target, false);
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                return Texture.MISSING;
            }
            // Animated textures are a vertical strip of frames. The map is a still, so the first
            // frame; frames are square unless the .mcmeta says otherwise, and the stock ones never do.
            int w = image.getWidth();
            int h = Math.min(image.getHeight(), w);
            int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
            return new Texture(w, h, argb);
        } catch (IOException | RuntimeException e) {
            Diorama.LOGGER.warn("Diorama: unreadable texture {} ({})", file, e.getMessage());
            return Texture.MISSING;
        }
    }

    /**
     * Sprite names the block atlas gives to textures stored elsewhere. A {@code single} source in
     * {@code atlases/blocks.json} may rename its file — Lootr's trophy model asks for
     * {@code lootr:block/trophy_chest}, which is {@code lootr:entity/chest/normal_opened} — so a
     * model can name a texture that no {@code textures/} path holds. Every source contributes its
     * own copy of that file; first source wins per sprite, as everywhere else here.
     */
    private Map<String, String> atlasAliases() {
        Map<String, String> aliases = atlasAliases;
        if (aliases != null) {
            return aliases;
        }
        aliases = new java.util.HashMap<>();
        for (AssetSource source : sources) {
            try {
                byte[] bytes = source.read("assets/minecraft/atlases/blocks.json");
                if (bytes == null) {
                    continue;
                }
                var root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                for (var e : root.getAsJsonArray("sources")) {
                    JsonObject o = e.getAsJsonObject();
                    String type = o.has("type") ? o.get("type").getAsString() : "";
                    if ((type.equals("minecraft:single") || type.equals("single")) && o.has("resource") && o.has("sprite")) {
                        aliases.putIfAbsent(qualify(o.get("sprite").getAsString()), qualify(o.get("resource").getAsString()));
                    }
                }
            } catch (IOException | RuntimeException e) {
                Diorama.LOGGER.debug("Diorama: {} has an unreadable block atlas", source.name(), e);
            }
        }
        atlasAliases = aliases;
        return aliases;
    }

    private static String qualify(String id) {
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    /** An image as packed ARGB, row-major. Used for the colour maps too. */
    public record Texture(int width, int height, int[] argb) {

        public static final Texture MISSING = checkerboard();

        /** Nearest texel for {@code u, v} in 0..1 across the image. */
        public int sample(float u, float v) {
            int x = (int) (u * width);
            int y = (int) (v * height);
            x = x < 0 ? 0 : x >= width ? width - 1 : x;
            y = y < 0 ? 0 : y >= height ? height - 1 : y;
            return argb[y * width + x];
        }

        private static Texture checkerboard() {
            int[] px = new int[16 * 16];
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    px[y * 16 + x] = ((x < 8) == (y < 8)) ? 0xFFF800F8 : 0xFF000000;
                }
            }
            return new Texture(16, 16, px);
        }
    }

    /** Owner overrides in {@code dir}: every zip, jar and folder, alphabetically. */
    public static List<AssetSource> overrides(Path dir) {
        List<AssetSource> found = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return found;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path p : entries.sorted().toList()) {
                String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                try {
                    if (Files.isDirectory(p)) {
                        found.add(AssetSource.folder(p));
                    } else if (name.endsWith(".zip") || name.endsWith(".jar")) {
                        found.add(AssetSource.zip(p));
                    }
                } catch (IOException e) {
                    Diorama.LOGGER.warn("Diorama: cannot open {} ({})", p, e.getMessage());
                }
            }
        } catch (IOException e) {
            Diorama.LOGGER.warn("Diorama: cannot list {} ({})", dir, e.getMessage());
        }
        return found;
    }
}
