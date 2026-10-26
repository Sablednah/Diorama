package com.sablednah.diorama.asset;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sablednah.diorama.Diorama;

/**
 * Fetches the vanilla client jar for this Minecraft version from Mojang, the same way the
 * launcher does: version manifest, then the version's own document, then the jar it names.
 *
 * <p>Only ever called when the owner has set {@code acceptDownload}. The file is checked against
 * the SHA-1 Mojang publishes beside it and is written under a temporary name first, so an
 * interrupted download can never be mistaken for a complete one on the next start.</p>
 */
public final class ClientJarDownload {

    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    /**
     * @return {@code target}, which now holds a verified client jar
     * @throws IOException naming the step that failed — "no such version in the manifest" and
     *         "checksum mismatch" are different problems and the log has to say which
     */
    public static Path fetch(String versionId, Path target) throws IOException {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        JsonObject manifest = json(http, MANIFEST);
        String versionUrl = null;
        for (var v : manifest.getAsJsonArray("versions")) {
            JsonObject o = v.getAsJsonObject();
            if (versionId.equals(o.get("id").getAsString())) {
                versionUrl = o.get("url").getAsString();
                break;
            }
        }
        if (versionUrl == null) {
            throw new IOException("Mojang's version manifest has no entry for '" + versionId + "'");
        }

        JsonObject client = json(http, versionUrl).getAsJsonObject("downloads").getAsJsonObject("client");
        String url = client.get("url").getAsString();
        String sha1 = client.get("sha1").getAsString();

        Diorama.LOGGER.info("Diorama: downloading the {} client jar for its block models and textures", versionId);
        Files.createDirectories(target.getParent());
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        try {
            HttpResponse<InputStream> response = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IOException("client jar download answered HTTP " + response.statusCode());
            }
            try (InputStream in = response.body()) {
                Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("client jar download interrupted", e);
        }

        String actual = sha1(partial);
        if (!actual.equalsIgnoreCase(sha1)) {
            Files.deleteIfExists(partial);
            throw new IOException("client jar checksum mismatch: expected " + sha1 + ", got " + actual);
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    private static JsonObject json(HttpClient http, String url) throws IOException {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException(url + " answered HTTP " + response.statusCode());
            }
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted fetching " + url, e);
        }
    }

    private static String sha1(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private ClientJarDownload() {}
}
