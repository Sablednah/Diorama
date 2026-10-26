package com.sablednah.diorama;

import java.util.List;

import com.sablednah.diorama.render.View;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * {@code config/diorama-common.toml}.
 *
 * <p>COMMON rather than SERVER because single player needs the same settings before any world is
 * open, and a SERVER config lives inside each save — an owner would have to set the port again for
 * every world.</p>
 */
public final class DioramaConfig {

    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue WEB_ENABLED;
    public static final ModConfigSpec.IntValue WEB_PORT;
    public static final ModConfigSpec.ConfigValue<String> WEB_BIND;
    public static final ModConfigSpec.ConfigValue<String> WEB_PUBLIC_URL;

    public static final ModConfigSpec.BooleanValue ACCEPT_DOWNLOAD;

    public static final ModConfigSpec.IntValue RENDER_THREADS;
    public static final ModConfigSpec.IntValue PIXELS_PER_BLOCK;
    public static final ModConfigSpec.IntValue UPDATE_SECONDS;
    public static final ModConfigSpec.IntValue CHUNK_CACHE;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> VIEWS;
    public static final ModConfigSpec.BooleanValue LIVE_UPDATES;
    public static final ModConfigSpec.IntValue LIVE_CHUNKS_PER_SECOND;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("The map's web server.").push("web");
        WEB_ENABLED = b.comment("Serve the map at all. Off still renders tiles to disk, for owners who serve them with their own web server.")
                .define("enabled", true);
        WEB_PORT = b.comment("The port the map is served on. Open http://<server>:<port>/ in a browser.")
                .defineInRange("port", 8124, 1, 65535);
        WEB_BIND = b.comment(
                        "The address to listen on. Empty means: every interface on a dedicated server,",
                        "and 127.0.0.1 in single player — so a world you are playing alone is never",
                        "published to your network by accident.")
                .define("bindAddress", "");
        WEB_PUBLIC_URL = b.comment(
                        "The link players are offered in chat, e.g. https://map.example.com/.",
                        "Empty means work it out from the bind address and port, which is right in",
                        "single player and usually wrong behind a proxy.")
                .define("publicUrl", "");
        b.pop();

        b.comment("Where block models and textures come from.").push("assets");
        ACCEPT_DOWNLOAD = b.comment(
                        "A dedicated server carries no textures. Set this true to let Diorama download the",
                        "vanilla client jar for this Minecraft version from Mojang (checked against Mojang's",
                        "own SHA-1) and read block models and textures from it. The jar is Mojang's and is",
                        "covered by the Minecraft EULA, which is why this is your decision and is off until",
                        "you make it. Single player never needs it: the game already has the textures.",
                        "Alternatively put a client jar or a resource pack at diorama/assets/ yourself.")
                .define("acceptDownload", false);
        b.pop();

        b.comment("The renderer.").push("render");
        RENDER_THREADS = b.comment("Worker threads drawing tiles. They never touch the server thread, but they do share the CPU with it.")
                .defineInRange("threads", Math.max(1, Runtime.getRuntime().availableProcessors() / 2), 1, 64);
        PIXELS_PER_BLOCK = b.comment(
                        "Width of one block on the most zoomed-in tiles. 32 shows every texel of a top face;",
                        "16 renders four times faster and still reads well. Changing it invalidates every tile.")
                .defineInRange("pixelsPerBlock", 32, 8, 64);
        UPDATE_SECONDS = b.comment("How often changed chunks are re-rendered.")
                .defineInRange("updateSeconds", 15, 1, 3600);
        LIVE_UPDATES = b.comment(
                        "Copy a chunk for the map as soon as a block in it changes, instead of waiting for the",
                        "server to save it (up to five minutes). The copy is made on the server thread.")
                .define("liveUpdates", true);
        LIVE_CHUNKS_PER_SECOND = b.comment("At most this many changed chunks are copied per second; the rest wait their turn.")
                .defineInRange("liveChunksPerSecond", 32, 1, 1024);
        CHUNK_CACHE = b.comment("Chunks held in memory while rendering. More is faster for a full render and costs heap.")
                .defineInRange("chunkCache", 4096, 256, 65536);
        VIEWS = b.comment(
                        "Which corners the camera can stand at: se, sw, nw, ne. Each one is a full set of tiles,",
                        "so each costs as much rendering and disk as the first. Turning one on draws it on the next start.")
                .defineList("views", List.of("se", "sw", "nw", "ne"), () -> "se",
                        o -> o instanceof String s && View.byId(s) != null);
        b.pop();

        SPEC = b.build();
    }

    /** The configured views, in config order, duplicates dropped; never empty. */
    public static java.util.Set<View> views() {
        java.util.Set<View> out = new java.util.LinkedHashSet<>();
        for (String id : VIEWS.get()) {
            View v = View.byId(id);
            if (v != null) {
                out.add(v);
            }
        }
        if (out.isEmpty()) {
            out.add(View.SE);
        }
        return out;
    }

    private DioramaConfig() {}
}
