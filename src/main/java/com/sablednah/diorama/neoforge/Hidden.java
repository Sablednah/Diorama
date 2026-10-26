package com.sablednah.diorama.neoforge;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import org.jspecify.annotations.Nullable;

import com.sablednah.diorama.Diorama;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

/**
 * Which players the map must not show.
 *
 * <p>A web map is the easiest way there is to find somebody, so it hides whoever the game is
 * hiding: spectators, anyone invisible, and anyone {@code /vanish}ed by SableCraft Standards. That
 * last one is looked up by reflection, once: Diorama is a standalone mod and must start without
 * Standards, so it cannot compile against it.</p>
 *
 * <p>Only the question is needed, not Standards' {@code VanishEvent}: the player list is sampled
 * every second, so somebody vanishing mid-session is gone from the page by the next poll.</p>
 */
final class Hidden {

    private static final @Nullable MethodHandle VANISHED = find();

    private Hidden() {}

    static boolean hidden(ServerPlayer player) {
        if (player.isSpectator() || player.isInvisible()) {
            return true;
        }
        if (VANISHED != null) {
            try {
                return (boolean) VANISHED.invokeExact(player);
            } catch (Throwable t) {
                // A Standards that changed its API: showing a vanished player is the worse
                // failure, so err towards hiding.
                return true;
            }
        }
        return false;
    }

    private static @Nullable MethodHandle find() {
        if (!ModList.get().isLoaded("standards")) {
            return null;
        }
        try {
            Class<?> vanish = Class.forName("com.sablednah.standards.api.vanish.Vanish");
            MethodHandle h = MethodHandles.publicLookup().findStatic(vanish, "isVanished",
                    MethodType.methodType(boolean.class, ServerPlayer.class));
            Diorama.LOGGER.info("Diorama: hiding players vanished by Standards");
            return h;
        } catch (ReflectiveOperationException | LinkageError e) {
            Diorama.LOGGER.warn("Diorama: Standards is loaded but its vanish API was not found ({}); vanished players may show on the map", e.toString());
            return null;
        }
    }
}
