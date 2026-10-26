package com.sablednah.diorama.asset;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.neoforged.fml.jarcontents.JarContents;
import org.jspecify.annotations.Nullable;

/**
 * One place block models and textures can come from: a jar, a resource pack, a mod.
 *
 * <p>Paths are jar-relative and always forward-slashed: {@code assets/minecraft/models/block/stone.json}.
 * Reads happen on render threads, so every implementation must be safe to call concurrently —
 * {@link ZipFile} is, and so is NeoForge's {@link JarContents}.</p>
 */
public interface AssetSource {

    /** The bytes at {@code path}, or {@code null} when this source does not carry it. */
    byte @Nullable [] read(String path) throws IOException;

    /** For logs: which jar or pack answered. */
    String name();

    static AssetSource zip(Path file) throws IOException {
        ZipFile zip = new ZipFile(file.toFile());
        String name = file.getFileName().toString();
        return new AssetSource() {
            @Override
            public byte @Nullable [] read(String path) throws IOException {
                ZipEntry entry = zip.getEntry(path);
                if (entry == null) {
                    return null;
                }
                try (var in = zip.getInputStream(entry)) {
                    return in.readAllBytes();
                }
            }

            @Override
            public String name() {
                return name;
            }
        };
    }

    static AssetSource folder(Path root) {
        String name = root.getFileName().toString() + "/";
        return new AssetSource() {
            @Override
            public byte @Nullable [] read(String path) throws IOException {
                Path file = root.resolve(path);
                return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
            }

            @Override
            public String name() {
                return name;
            }
        };
    }

    /**
     * A mod as NeoForge loaded it. This is how modded blocks get their real models, and in single
     * player it is also how the vanilla textures are found: the {@code minecraft} mod file is the
     * client jar there.
     */
    static AssetSource mod(String id, JarContents contents) {
        // Indexed once. Most lookups are misses — a vanilla model asked of every mod before the
        // client jar answers — and a miss against a dev mod's classes folder is a filesystem stat,
        // which on /mnt/d cost three minutes over one bake of every block state.
        java.util.Set<String> index = new java.util.HashSet<>();
        contents.visitContent((path, resource) -> {
            if (path.startsWith("assets/")) {
                index.add(path);
            }
        });
        return new AssetSource() {
            @Override
            public byte @Nullable [] read(String path) throws IOException {
                return index.contains(path) ? contents.readFile(path) : null;
            }

            @Override
            public String name() {
                return "mod:" + id;
            }
        };
    }
}
