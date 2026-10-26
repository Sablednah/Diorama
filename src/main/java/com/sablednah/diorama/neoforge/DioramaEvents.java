package com.sablednah.diorama.neoforge;

import com.sablednah.diorama.DioramaConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** The game-bus hooks. Each one hands over to {@link DioramaService} and returns. */
public final class DioramaEvents {

    @SubscribeEvent
    public static void serverStarted(ServerStartedEvent event) {
        Lang.load();
        DioramaService.start(event.getServer());
    }

    /** {@code /reload}: fired once with no player, which is the only time to re-read datapacks. */
    @SubscribeEvent
    public static void datapacksReloaded(net.neoforged.neoforge.event.OnDatapackSyncEvent event) {
        if (event.getPlayer() == null && DioramaService.current() != null) {
            OwnerRegions.INSTANCE.reloadDatapacks(event.getPlayerList().getServer());
        }
    }

    @SubscribeEvent
    public static void serverStopping(ServerStoppingEvent event) {
        DioramaService.stop();
    }

    /**
     * The map's whole view of a world being played. The game copied the chunk for its own save
     * already; we keep a reference to that copy, which is free.
     */
    @SubscribeEvent
    public static void chunkSaved(ChunkDataEvent.Save event) {
        DioramaService s = DioramaService.current();
        if (s != null && event.getLevel() instanceof ServerLevel level) {
            s.chunkSaved(level.dimension(), event.getChunk().getPos(), event.getData());
        }
    }

    /**
     * Nearly every real block change notifies its neighbours: placing, breaking, pistons,
     * explosions, flowing fluid. Changes made without a neighbour update (crop growth, {@code /fill})
     * still reach the map, at the next save. Cancelled or not, the block has already changed.
     */
    @SubscribeEvent(receiveCanceled = true)
    public static void blockChanged(BlockEvent.NeighborNotifyEvent event) {
        DioramaService s = DioramaService.current();
        if (s != null && event.getLevel() instanceof ServerLevel level) {
            s.blockChanged(level, event.getPos().getX(), event.getPos().getZ());
        }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        DioramaService s = DioramaService.current();
        if (s != null && event.getServer().getTickCount() % 20 == 0) {
            s.samplePlayers();
            if (event.getServer().getTickCount() % 100 == 0) {
                s.sampleMarkers();
            }
            if (DioramaConfig.LIVE_UPDATES.get()) {
                s.snapshotTouched();
            }
        }
    }

    /** In single player, say where the map is — nobody would otherwise guess it has a port. */
    @SubscribeEvent
    public static void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        DioramaService s = DioramaService.current();
        if (s != null && event.getEntity() instanceof ServerPlayer player
                && !s.server().isDedicatedServer() && s.webRunning()) {
            player.sendSystemMessage(DioramaCommands.link(s));
        }
    }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        DioramaCommands.register(event.getDispatcher());
    }

    private DioramaEvents() {}
}
