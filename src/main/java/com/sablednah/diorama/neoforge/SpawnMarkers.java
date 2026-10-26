package com.sablednah.diorama.neoforge;

import java.util.Collection;
import java.util.List;

import com.sablednah.diorama.api.Markers;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Diorama's own marker set: where a new player appears. Also the seam's first consumer, so the
 * path from {@link Markers} to the page is exercised on every server, not only on one that has a
 * mod reporting into it.
 */
final class SpawnMarkers implements Markers.MarkerSet {

    static final SpawnMarkers INSTANCE = new SpawnMarkers();

    private SpawnMarkers() {}

    @Override
    public String id() {
        return "diorama:spawn";
    }

    @Override
    public String label() {
        return Lang.text("marker.spawn.layer");
    }

    @Override
    public Collection<Markers.Marker> markers(ResourceKey<Level> level) {
        DioramaService s = DioramaService.current();
        if (s == null) {
            return List.of();
        }
        var spawn = s.server().overworld().getRespawnData().globalPos();
        if (spawn.dimension() != level) {
            return List.of();
        }
        var p = spawn.pos();
        return List.of(new Markers.Marker("spawn", Lang.text("marker.spawn"), p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0xF5D76E));
    }
}
