package com.sablednah.diorama.neoforge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.sablednah.diorama.Diorama;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Every string Diorama shows a player, from {@code config/diorama/messages.yml}.
 *
 * <p>The series rule, from SableCraft Standards: text is resolved on the server, not with vanilla
 * translatable components, because a vanilla client does not carry our lang file and would show
 * the raw key.</p>
 *
 * <p>The file is written whole on first run. After that, keys added by an upgrade are
 * <em>appended</em> — but only keys this installation has never been offered, which
 * {@code messages.known} records. "Absent from the file" cannot mean "new": the header invites
 * trimming the file to just your changes, and re-adding trimmed keys every start would undo that.
 * Standards learned the merge half the hard way — writing only when absent left 146 of 222 keys
 * uncustomisable on a server that predated them, with nothing to say they existed.</p>
 */
public final class Lang {

    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
    private static volatile Map<String, String> active = Map.of();

    static {
        DEFAULTS.put("prefix", "&7Diorama:&r");
        DEFAULTS.put("link.url", "{prefix} &b&n{url}");
        DEFAULTS.put("link.port", "{prefix} &7the map is on port &f{port}&7 of this server. Set web.publicUrl and this becomes a link.");
        DEFAULTS.put("link.off", "&cThe map is not being served. Ask whoever runs this place, who may already know.");
        DEFAULTS.put("status.not_running", "&cDiorama is not running.");
        DEFAULTS.put("status.state", "{prefix} &f{state}&7{detail}");
        DEFAULTS.put("status.full_render", "&7  full render: &f{progress}");
        DEFAULTS.put("status.fallbacks", "&7  block states drawn from their shape (no model): &f{count}");
        DEFAULTS.put("status.world", "&7  {world}: &f{drawn}&7 tiles drawn this session, &f{waiting}&7 waiting");
        DEFAULTS.put("status.web", "&7  web: &f{url}");
        DEFAULTS.put("status.web_off", "off");
        DEFAULTS.put("render.not_ready", "&cDiorama is not ready{detail}");
        DEFAULTS.put("marker.spawn.layer", "Spawn");
        DEFAULTS.put("marker.spawn", "World spawn");
        DEFAULTS.put("status.linked", "&7  {line}");
        DEFAULTS.put("marker.regions.layer", "Regions");
        DEFAULTS.put("marker.factions.layer", "Factions");
        DEFAULTS.put("marker.factions.chunks", "{n} chunks");
        DEFAULTS.put("marker.factions.peaceful", "peaceful");
        DEFAULTS.put("marker.warps.layer", "Warps");
        DEFAULTS.put("marker.warps.spawn", "Spawn (/spawn)");
        DEFAULTS.put("region.none", "{prefix} &7No regions yet. &f/diorama region create <id> [label]&7, then stand at each corner and &f/diorama region corner <id>&7.");
        DEFAULTS.put("region.list_line", "&7  &f{id}&7 — {label} &7({corners} corners, {world})");
        DEFAULTS.put("region.list_pack", "&7  &f{id}&7 — {label} &7(datapack, {world})");
        DEFAULTS.put("region.bad_id", "&cRegion ids are lower-case letters, digits, - and _. &f{id}&c is none of those things.");
        DEFAULTS.put("region.exists", "&cThere is already a region called &f{id}&c.");
        DEFAULTS.put("region.created", "{prefix} &7region &f{id}&7 started. Walk to each corner in turn and run &f/diorama region corner {id}&7.");
        DEFAULTS.put("region.wrong_world", "&cThat region is in {world}. Corners have to be in the same world, oddly enough.");
        DEFAULTS.put("region.corner_more", "{prefix} &7corner {n} at &f{x}, {z}&7. {left} more before it is a shape.");
        DEFAULTS.put("region.corner", "{prefix} &7corner {n} at &f{x}, {z}&7. On the map within a few seconds.");
        DEFAULTS.put("region.no_corners", "&cIt has no corners to take back.");
        DEFAULTS.put("region.undone", "{prefix} &7last corner removed; {n} left.");
        DEFAULTS.put("region.bad_colour", "&c{colour} is not a colour. Try &f#e8a33d&c.");
        DEFAULTS.put("region.saved", "{prefix} &7region &f{id}&7 saved.");
        DEFAULTS.put("region.deleted", "{prefix} &7region &f{id}&7 deleted.");
        DEFAULTS.put("region.unknown", "&cNo region called &f{id}&c. &f/diorama region list&c says what there is.");
        DEFAULTS.put("region.from_pack", "&c{id} comes from a datapack. Edit the pack; this command only edits regions it made.");
        DEFAULTS.put("render.started", "{prefix} &7redrawing &f{world}&7. It runs in the background; &f/diorama status&7 says how far it got.");
    }

    private Lang() {}

    /** Read the owner's file, writing or extending it first. Safe to call again. */
    public static void load() {
        Path dir = FMLPaths.CONFIGDIR.get().resolve("diorama");
        Path file = dir.resolve("messages.yml");
        Path known = dir.resolve("messages.known");
        Map<String, String> read = new LinkedHashMap<>();
        try {
            Files.createDirectories(dir);
            Set<String> seen = new HashSet<>();
            if (Files.isRegularFile(known)) {
                for (String line : Files.readAllLines(known, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) {
                        seen.add(line.strip());
                    }
                }
            }
            if (!Files.isRegularFile(file)) {
                Files.writeString(file, header() + body(DEFAULTS.keySet()), StandardCharsets.UTF_8);
            } else {
                Set<String> fresh = new java.util.LinkedHashSet<>(DEFAULTS.keySet());
                fresh.removeAll(seen);
                // No record at all is a file from before the bookkeeping: offering every key once
                // beats never offering the new ones, and this same run marks them all seen.
                if (!fresh.isEmpty()) {
                    read.putAll(parse(Files.readAllLines(file, StandardCharsets.UTF_8)));
                    fresh.removeAll(read.keySet());
                    if (!fresh.isEmpty()) {
                        Files.writeString(file, "\n# Added by an upgrade:\n" + body(fresh), StandardCharsets.UTF_8,
                                java.nio.file.StandardOpenOption.APPEND);
                    }
                }
            }
            Files.writeString(known, String.join("\n", DEFAULTS.keySet()) + "\n", StandardCharsets.UTF_8);
            read.clear();
            read.putAll(parse(Files.readAllLines(file, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            Diorama.LOGGER.warn("Diorama: could not read or write {} ({}); using built-in messages", file, e.toString());
        }
        active = Map.copyOf(read);
    }

    /** The message for {@code key}, placeholders filled from {@code pairs} (name, value, …). */
    public static MutableComponent get(String key, Object... pairs) {
        return Component.literal(colour(text(key, pairs)));
    }

    /** As {@link #get} but plain text, for embedding in another message. */
    public static String text(String key, Object... pairs) {
        String s = active.getOrDefault(key, DEFAULTS.getOrDefault(key, key));
        s = s.replace("{prefix}", active.getOrDefault("prefix", DEFAULTS.get("prefix")));
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            s = s.replace("{" + pairs[i] + "}", String.valueOf(pairs[i + 1]));
        }
        return s;
    }

    /**
     * {@code &} codes to section signs, only where a real code follows. Only ever applied to the
     * owner's templates, never to text a player typed: Standards' first version coloured player
     * text too, and the first person to type {@code &r} dressed their words as a server message.
     */
    static String colour(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length() && "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(s.charAt(i + 1)) >= 0) {
                out.append('§');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String header() {
        return """
                # Diorama messages — every string it shows a player.
                # Edit freely: translation, tone, colours ('&' codes). {curly} placeholders are filled
                # at runtime and {prefix} pulls in the prefix line. Deleted keys fall back to the
                # built-in text, so trimming this to just your changes is fine; an upgrade appends
                # only keys it has never offered before. Applied on restart.

                """;
    }

    private static String body(Iterable<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            sb.append(k).append(": \"").append(DEFAULTS.get(k).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"\n");
        }
        return sb.toString();
    }

    /** {@code key: "value"} or {@code key: value}, one per line; {@code #} comments. */
    static Map<String, String> parse(Iterable<String> lines) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).strip();
            String value = line.substring(colon + 1).strip();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
                boolean dq = value.charAt(0) == '"';
                value = value.substring(1, value.length() - 1);
                if (dq) {
                    value = value.replace("\\\"", "\"").replace("\\\\", "\\");
                } else {
                    value = value.replace("''", "'");
                }
            }
            out.put(key, value);
        }
        return out;
    }
}
