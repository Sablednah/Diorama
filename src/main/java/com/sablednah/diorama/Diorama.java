package com.sablednah.diorama;

import com.mojang.logging.LogUtils;
import com.sablednah.diorama.neoforge.DioramaEvents;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Diorama — an isometric web map of the world, rendered on the server.
 *
 * <p>Server-side by design: the map is a web page, so a vanilla client sees all of it. The same
 * jar in a single-player game runs on the integrated server and serves the page on 127.0.0.1.
 * There is deliberately no in-game map; JourneyMap does that, and does it well.</p>
 *
 * <p>Layout: {@code asset} reads jars and packs, {@code model} turns block states into faces,
 * {@code world} holds chunk copies, {@code render} casts rays and writes tiles, {@code web} serves
 * them, and {@code neoforge} is the glue — the only package that knows the server is running.</p>
 */
@Mod(Diorama.MODID)
public class Diorama {

    /** Must match mod_id in gradle.properties. */
    public static final String MODID = "diorama";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Diorama(IEventBus modEventBus, ModContainer modContainer) {
        // Which bytes, not just which release: the line a bug report can quote.
        LOGGER.info("SableCraft Diorama {}", BuildInfo.describe());
        // 26.3: FML 12.0.8 renamed COMMON to LOCAL, whose default file name is not the old one. The
        // name is passed explicitly so an existing diorama-common.toml is still the file read,
        // rather than every setting silently resetting to its default on upgrade.
        modContainer.registerConfig(ModConfig.Type.LOCAL, DioramaConfig.SPEC, "diorama-common.toml");
        NeoForge.EVENT_BUS.register(DioramaEvents.class);
    }
}
