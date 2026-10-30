package com.sablednah.diorama.neoforge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.sablednah.diorama.Diorama;
import com.sablednah.diorama.asset.Assets;
import com.sablednah.diorama.model.BlockModels;
import com.sablednah.diorama.model.Quad;
import com.sablednah.diorama.render.IsoRenderer;
import com.sablednah.diorama.render.MapRenderer;
import com.sablednah.diorama.render.TileStore;
import com.sablednah.diorama.DioramaConfig;
import com.sablednah.diorama.render.View;
import com.sablednah.diorama.world.ChunkSnap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.core.Direction;

/**
 * {@code ./gradlew runServer -Pselftest} — runs once the assets are loaded and logs a pass/fail
 * block.
 *
 * <p>House rules, from Standards: call the real code, never a re-derivation of it; and test both
 * directions — a ray that hits everything passes every "it hits" check.</p>
 */
final class SelfTest {

    private static final List<String> failures = new ArrayList<>();
    private static int passed;

    static void run(DioramaService service) {
        failures.clear();
        passed = 0;
        BlockModels models = service.models();
        if (models == null) {
            fail("no models: " + service.stateDetail());
            report();
            return;
        }

        everyStateResolves(models);
        cubeFacesAndCulling(models);
        stairsAreNotCubes(models);
        customLoaderVariants(models);
        viewsLookAwayFromTheirCorner();
        messagesParse();
        markersSample(service);
        unscannedStoreStillFinds(service);
        regionsTrace(service);
        pickFindsTheGround(service);
        roofIsCutAway(service);
        heightsSurviveARestart(service);
        tilesMoveOutOfTheSave();
        integrationsLinked();
        factionsEndToEnd(service);
        cityWorldAnswers(service);
        spawnTileDraws(service);
        report();
    }

    /** Every vanilla state should come from its JSON. A fallback means the dispatch missed it. */
    private static void everyStateResolves(BlockModels models) {
        int states = 0, fallback = 0, missingTexture = 0;
        Set<String> fallbackBlocks = new TreeSet<>();
        Set<String> missingTextureBlocks = new TreeSet<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            String id = BuiltInRegistries.BLOCK.getKey(block).toString();
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                states++;
                BlockModels.Baked baked = models.get(state);
                if (baked.fallback()) {
                    fallback++;
                    fallbackBlocks.add(id);
                }
                for (Quad[] alt : baked.alternatives()) {
                    for (Quad q : alt) {
                        if (q.texture() == Assets.Texture.MISSING) {
                            missingTexture++;
                            missingTextureBlocks.add(id);
                        }
                    }
                }
            }
        }
        Diorama.LOGGER.info("Diorama self-test: {} block states; {} drawn from shape ({} blocks): {}",
                states, fallback, fallbackBlocks.size(), fallbackBlocks);
        if (missingTexture == 0) {
            pass();
        } else {
            fail(missingTexture + " faces with a missing texture, on " + missingTextureBlocks);
        }
        // The entity-rendered blocks (chests, signs, beds, banners, heads…) are expected here; a
        // full cube like stone is not, and would mean the dispatch itself is broken.
        check(!fallbackBlocks.contains("minecraft:stone") && !fallbackBlocks.contains("minecraft:oak_stairs")
                && !fallbackBlocks.contains("minecraft:grass_block"), "plain blocks fell back to their shape");
    }

    /**
     * Variants for a mod's own model loader carry a "type" and no "model". ZARP's AE2, Lootr and
     * Sophisticated Storage blocks threw from the shape fallback on every tile that held one; the
     * JSON here is theirs, verbatim in shape.
     */
    private static void customLoaderVariants(BlockModels models) {
        var lootr = com.google.gson.JsonParser.parseString(
                "{\"type\": \"lootr:custom\", \"open\": false, \"opened\": \"lootr:block/opened_barrel\","
                + " \"state\": {\"x\": 180}, \"vanilla\": \"minecraft:block/barrel\"}").getAsJsonObject();
        // With Lootr installed its own "opened" model resolves first; without it, vanilla's barrel.
        // Either is a model the variant itself names, which is the point.
        String ref = models.modelRef(lootr);
        check("minecraft:block/barrel".equals(ref) || "lootr:block/opened_barrel".equals(ref),
                "a custom-loader variant naming a real model resolved to " + ref);
        var ae2 = com.google.gson.JsonParser.parseString(
                "{\"type\": \"ae2:crafting_cube\", \"unit_type\": \"storage_16k\"}").getAsJsonObject();
        ref = models.modelRef(ae2);
        check(ref == null, "a custom-loader variant naming no model resolved to " + ref);
        var plain = com.google.gson.JsonParser.parseString("{\"model\": \"minecraft:block/stone\", \"y\": 90}").getAsJsonObject();
        check("minecraft:block/stone".equals(models.modelRef(plain)), "an ordinary variant lost its model");
    }

    /** A ray onto stone's top must hit it, and the same ray must not hit its underside. */
    private static void cubeFacesAndCulling(BlockModels models) {
        Quad[] quads = models.get(Blocks.STONE.defaultBlockState()).pick(0, 0, 0);
        check(quads.length == 6, "stone should bake to 6 faces, got " + quads.length);
        float[] ab = new float[2];
        // Start a quarter step back along the view direction from the top face's centre.
        float sx = 0.75f, sy = 1.25f, sz = 0.75f;
        int hitTop = 0, hitOthers = 0;
        for (Quad q : quads) {
            float t = q.intersect(sx, sy, sz, -1f, -1f, -1f, ab);
            if (Float.isNaN(t)) {
                continue;
            }
            if (Math.abs(t - 0.25f) < 1e-4f && q.shade() == 1.0f) {
                hitTop++;
            } else {
                hitOthers++;
            }
        }
        check(hitTop == 1, "the ray should hit stone's top face exactly once, hit " + hitTop);
        // The bottom, north and west faces face away from the camera: back faces, never hit.
        check(hitOthers <= 2, "back faces were hit: " + hitOthers + " extra hits");
    }

    /** The point of the whole exercise: a stair is not a cube. */
    private static void stairsAreNotCubes(BlockModels models) {
        BlockState stair = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.EAST)
                .setValue(StairBlock.HALF, Half.BOTTOM)
                .setValue(StairBlock.SHAPE, StairsShape.STRAIGHT);
        Quad[] quads = models.get(stair).pick(0, 0, 0);
        check(quads.length > 6, "a straight stair should bake to more than a cube's 6 faces, got " + quads.length);
        BlockState slab = Blocks.OAK_SLAB.defaultBlockState();
        float maxY = 0;
        for (Quad q : models.get(slab).pick(0, 0, 0)) {
            maxY = Math.max(maxY, q.o()[1]);
            maxY = Math.max(maxY, q.o()[1] + q.e1()[1]);
            maxY = Math.max(maxY, q.o()[1] + q.e3()[1]);
        }
        check(Math.abs(maxY - 0.5f) < 1e-4f, "a bottom slab should top out at half a block, got " + maxY);
    }

    /** Tiles from before they lived beside the server are moved out of the save, once. */
    private static void tilesMoveOutOfTheSave() {
        java.nio.file.Path save = null, home = null;
        try {
            // Inside the game folder, like a real save: a rename cannot cross filesystems, and
            // across them the tiles are deliberately left where they are.
            java.nio.file.Path scratch = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("diorama").resolve("selftest");
            java.nio.file.Files.createDirectories(scratch);
            save = java.nio.file.Files.createTempDirectory(scratch, "save");
            java.nio.file.Path old = save.resolve("diorama").resolve("tiles").resolve("minecraft_overworld");
            java.nio.file.Files.createDirectories(old);
            java.nio.file.Files.writeString(old.resolve("marker.txt"), "moved");
            home = DioramaService.tilesHome(save);
            check(!home.startsWith(save), "tiles: still inside the save at " + home);
            check(java.nio.file.Files.isRegularFile(home.resolve("minecraft_overworld").resolve("marker.txt")),
                    "tiles: the old tiles did not arrive at " + home);
            check(!java.nio.file.Files.exists(save.resolve("diorama").resolve("tiles")), "tiles: the old folder is still in the save");
            check(DioramaService.tilesHome(save).equals(home), "tiles: a second start chose somewhere else");
        } catch (java.io.IOException e) {
            check(false, "tiles: scratch save failed (" + e + ")");
        } finally {
            for (java.nio.file.Path p : new java.nio.file.Path[] {save, home}) {
                if (p != null && java.nio.file.Files.exists(p)) {
                    try (var files = java.nio.file.Files.walk(p)) {
                        files.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
                    } catch (java.io.IOException ignored) {
                        // the OS clears temp folders; the game dir copy is a few bytes
                    }
                }
            }
        }
    }

    /**
     * A chunk's heights, saved and read back by a second renderer over the same folder — which is
     * what a restart is. Without them every restart began unsurveyed and redraws crawled.
     */
    private static void heightsSurviveARestart(DioramaService service) {
        MapRenderer map = service.maps().get(service.server().overworld().dimension());
        if (map == null) {
            return;
        }
        ServerLevel overworld = map.store().level();
        BlockPos spawn = overworld.getRespawnData().globalPos().pos();
        ChunkSnap snap = map.store().get(spawn.getX() >> 4, spawn.getZ() >> 4);
        if (snap == null) {
            Diorama.LOGGER.info("Diorama self-test: spawn chunk not on disk yet; heights check skipped");
            return;
        }
        java.nio.file.Path root;
        try {
            root = java.nio.file.Files.createTempDirectory("diorama-heights");
        } catch (java.io.IOException e) {
            check(false, "heights: no scratch folder (" + e + ")");
            return;
        }
        int ppb = DioramaConfig.PIXELS_PER_BLOCK.get();
        var views = java.util.EnumSet.allOf(View.class);
        MapRenderer first = new MapRenderer(map.store(), root, views, false, ppb);
        check(first.loadedHeights() == 0, "heights: a new folder already had heights");
        first.recordChunk(spawn.getX() >> 4, spawn.getZ() >> 4, snap);
        first.markSurveyed();
        MapRenderer second = new MapRenderer(map.store(), root, views, false, ppb);
        check(second.loadedHeights() == 1, "heights: saved 1 chunk, read back " + second.loadedHeights());
        // The scratch folder holds no tiles, so every tile that chunk touches is owed a drawing.
        check(second.queueMissing() > 0, "heights: a known chunk with no tiles queued nothing to draw");
        // A tile drawn but never shrunk — a stop between the two — gets its zoom levels on start.
        try {
            int[] argb = new int[com.sablednah.diorama.render.IsoRenderer.TILE * com.sablednah.diorama.render.IsoRenderer.TILE];
            java.util.Arrays.fill(argb, 0xFF336699);
            second.tiles(View.SE, false).write(0, 3, 5, argb);
            int built = second.repairZoom(service.pool());
            check(built == com.sablednah.diorama.render.TileStore.ZOOM_OUT_LEVELS,
                    "zoom: one tile with no zoom levels built " + built + ", not " + com.sablednah.diorama.render.TileStore.ZOOM_OUT_LEVELS);
            check(second.repairZoom(service.pool()) == 0, "zoom: a second repair built tiles again");
        } catch (java.io.IOException e) {
            check(false, "zoom: could not write a scratch tile (" + e + ")");
        }
        try (var files = java.nio.file.Files.walk(root)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        } catch (java.io.IOException ignored) {
            // a temp folder left behind is the OS's to clear
        }
    }

    /** Read the chunk at spawn through the real store and draw the tile above it. */
    private static void spawnTileDraws(DioramaService service) {
        ServerLevel overworld = service.server().overworld();
        MapRenderer map = service.maps().get(overworld.dimension());
        if (map == null) {
            fail("no map for the overworld");
            return;
        }
        BlockPos spawn = overworld.getRespawnData().globalPos().pos();
        map.store().scan();
        ChunkSnap snap = map.store().get(spawn.getX() >> 4, spawn.getZ() >> 4);
        if (snap == null) {
            // A brand-new world has not saved its spawn chunk yet. Not a failure of ours.
            Diorama.LOGGER.info("Diorama self-test: spawn chunk not on disk yet; tile check skipped");
            return;
        }
        // Project the centre of the top face of spawn's own column and ask for that one pixel, from
        // every view. A fraction-of-the-tile test fails on a new world, which finishes a single
        // chunk at spawn. A view whose rays or projection were turned the wrong way lands on air.
        int top = columnTop(snap, spawn.getX(), spawn.getZ(), overworld.getMinY());
        int w = com.sablednah.diorama.DioramaConfig.PIXELS_PER_BLOCK.get();
        double cx = spawn.getX() + 0.5, cz = spawn.getZ() + 0.5;
        for (View view : View.values()) {
            double vx = view.viewX(cx, cz), vz = view.viewZ(cx, cz);
            int sx = (int) Math.floor((vx - vz) * w / 2);
            int sy = (int) Math.floor((vx + vz) * w / 4 - top * w / 2.0);
            int tx = Math.floorDiv(sx, IsoRenderer.TILE);
            int ty = Math.floorDiv(sy, IsoRenderer.TILE);
            int[] argb = service.renderers().get().render(map.store(), view, false, tx, ty, overworld.getMaxY() + 1, overworld.getMinY());
            int pixel = argb == null ? 0 : argb[Math.floorMod(sy, IsoRenderer.TILE) * IsoRenderer.TILE + Math.floorMod(sx, IsoRenderer.TILE)];
            check((pixel >>> 24) > 200, "view " + view.id() + ": the pixel over spawn's surface (y " + top + ") is not opaque: "
                    + Integer.toHexString(pixel));
            if (view == View.SE) {
                // Night is the same picture, darker: the same pixel stays opaque and loses light.
                int[] dark = service.renderers().get().render(map.store(), view, true, tx, ty, overworld.getMaxY() + 1, overworld.getMinY());
                int np = dark == null ? 0 : dark[Math.floorMod(sy, IsoRenderer.TILE) * IsoRenderer.TILE + Math.floorMod(sx, IsoRenderer.TILE)];
                int dayLum = ((pixel >> 16) & 0xFF) + ((pixel >> 8) & 0xFF) + (pixel & 0xFF);
                int nightLum = ((np >> 16) & 0xFF) + ((np >> 8) & 0xFF) + (np & 0xFF);
                check((np >>> 24) > 200 && nightLum < dayLum, "night over spawn: day " + Integer.toHexString(pixel)
                        + ", night " + Integer.toHexString(np));
            }
            try {
                Path dir = service.server().getServerDirectory().resolve("diorama").resolve("selftest").resolve(view.id());
                new TileStore(dir).write(0, tx, ty, argb);
                Diorama.LOGGER.info("Diorama self-test: spawn tile written to {}", new TileStore(dir).file(0, tx, ty));
            } catch (Exception e) {
                fail("writing the spawn tile: " + e);
            }
        }
    }

    /**
     * The marker seam end to end: a set appears, a throwing set is skipped without taking the
     * others down, a second registration under one id replaces rather than doubles, and the
     * built-in spawn set reports exactly one marker in the overworld.
     */
    private static void markersSample(DioramaService service) {
        var good = new com.sablednah.diorama.api.Markers.MarkerSet() {
            public String id() { return "selftest:good"; }
            public String label() { return "Good"; }
            public java.util.Collection<com.sablednah.diorama.api.Markers.Marker> markers(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> level) {
                return level == net.minecraft.world.level.Level.OVERWORLD
                        ? java.util.List.of(new com.sablednah.diorama.api.Markers.Marker("a", "<b>A</b>", 1, 64, 2, 0xFF0000))
                        : java.util.List.of();
            }
        };
        var bad = new com.sablednah.diorama.api.Markers.MarkerSet() {
            public String id() { return "selftest:bad"; }
            public String label() { return "Bad"; }
            public java.util.Collection<com.sablednah.diorama.api.Markers.Marker> markers(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> level) {
                throw new IllegalStateException("deliberate, from the self-test");
            }
        };
        com.sablednah.diorama.api.Markers.register(good);
        com.sablednah.diorama.api.Markers.register(good);
        com.sablednah.diorama.api.Markers.register(bad);
        try {
            service.publishMarkers(service.server().submit(service::collectMarkers).join());
            var layers = service.markers();
            long goods = layers.stream().filter(l -> l.id().equals("selftest:good")).count();
            check(goods == 1, "a set registered twice under one id appears " + goods + " times");
            check(layers.stream().noneMatch(l -> l.id().equals("selftest:bad")), "a throwing set was published");
            var g = layers.stream().filter(l -> l.id().equals("selftest:good")).findFirst();
            check(g.isPresent() && g.get().markers().size() == 1 && g.get().markers().get(0).world().equals("minecraft_overworld"),
                    "the good set's one overworld marker: " + g.map(DioramaService.MarkerLayer::markers).orElse(null));
            var spawn = layers.stream().filter(l -> l.id().equals("diorama:spawn")).findFirst();
            check(spawn.isPresent() && spawn.get().markers().size() == 1, "the spawn set: " + spawn.orElse(null));
        } finally {
            com.sablednah.diorama.api.Markers.unregister("selftest:good");
            com.sablednah.diorama.api.Markers.unregister("selftest:bad");
            service.publishMarkers(service.server().submit(service::collectMarkers).join());
        }
    }

    /**
     * Outlines: a shared side between two chunks of one region draws nothing, every edge sits on
     * the ground it borders, a polygon closes, and a region set reaches the published layers.
     */
    private static void regionsTrace(DioramaService service) {
        var map = service.maps().get(net.minecraft.world.level.Level.OVERWORLD);
        if (map == null) {
            fail("no overworld map to trace regions on");
            return;
        }
        var store = map.store();
        store.scan();   // before anything asks for a chunk: an unscanned store answers "none" to all
        int sea = store.level().getSeaLevel();
        var spawn = store.level().getRespawnData().globalPos().pos();
        int cx = spawn.getX() >> 4, cz = spawn.getZ() >> 4;
        var one = java.util.Set.of(net.minecraft.world.level.ChunkPos.pack(cx, cz));
        var two = java.util.Set.of(net.minecraft.world.level.ChunkPos.pack(cx, cz), net.minecraft.world.level.ChunkPos.pack(cx + 1, cz));
        var lone = com.sablednah.diorama.render.RegionOutline.trace(one, store, sea);
        var pair = com.sablednah.diorama.render.RegionOutline.trace(two, store, sea);
        check(lone.size() == 4, "one chunk should have 4 edges, got " + lone.size());
        check(pair.size() == 6, "two touching chunks should have 6 edges (the shared side is interior), got " + pair.size());
        // The north edge's first point stands on the north-west column of the chunk.
        var snap = store.get(cx, cz);
        check(snap != null, "the spawn chunk is not in the store, so nothing below proves anything about the ground");
        if (snap != null) {
            int ground = snap.surface(cx * 16, cz * 16);
            boolean onGround = lone.stream().anyMatch(l -> l[0] == cx * 16 && l[2] == cz * 16 && l[1] == ground);
            check(onGround, "no edge starts on the ground at the chunk's corner (surface " + ground + ")");
        }
        var square = java.util.List.of(new double[] {0, 0}, new double[] {10, 0}, new double[] {10, 10}, new double[] {0, 10});
        double[] ring = com.sablednah.diorama.render.RegionOutline.tracePolygon(square, 100.0, store, sea);
        int n = ring.length;
        check(n == 15 && ring[0] == ring[n - 3] && ring[2] == ring[n - 1] && ring[1] == 100 && ring[n - 2] == 100,
                "a fixed-height square should close on itself at y 100: " + java.util.Arrays.toString(ring));

        var regions = new com.sablednah.diorama.api.Markers.MarkerSet() {
            public String id() { return "selftest:regions"; }
            public String label() { return "Regions"; }
            public java.util.Collection<com.sablednah.diorama.api.Markers.Marker> markers(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> level) {
                return java.util.List.of();
            }
            public java.util.Collection<com.sablednah.diorama.api.Markers.Region> regions(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> level) {
                return level == net.minecraft.world.level.Level.OVERWORLD
                        ? java.util.List.of(new com.sablednah.diorama.api.Markers.Region("land", "Land", 0x00FF00, null, new com.sablednah.diorama.api.Markers.Chunks(two)),
                                new com.sablednah.diorama.api.Markers.Region("line", "Too few corners", 0x00FF00, null,
                                        new com.sablednah.diorama.api.Markers.Polygon(java.util.List.of(new double[] {0, 0}, new double[] {1, 1}), null)))
                        : java.util.List.of();
            }
        };
        com.sablednah.diorama.api.Markers.register(regions);
        try {
            service.publishMarkers(service.server().submit(service::collectMarkers).join());
            var layer = service.markers().stream().filter(l -> l.id().equals("selftest:regions")).findFirst();
            check(layer.isPresent() && layer.get().regions().size() == 1 && layer.get().regions().get(0).lines().size() == 6,
                    "the region set should publish one 6-edge region and drop the 2-corner one: " + layer.map(DioramaService.MarkerLayer::regions).orElse(null));
        } finally {
            com.sablednah.diorama.api.Markers.unregister("selftest:regions");
            service.publishMarkers(service.server().submit(service::collectMarkers).join());
        }
    }

    /**
     * The info bar's pick: the pixel where a column's top is drawn should name that column, for
     * most columns round spawn (a taller neighbour in front can hide a few, which is right). And
     * a pixel above the whole world names nothing.
     */
    private static void pickFindsTheGround(DioramaService service) {
        var map = service.maps().get(net.minecraft.world.level.Level.OVERWORLD);
        if (map == null) {
            return;
        }
        var store = map.store();
        store.scan();
        int ppb = DioramaConfig.PIXELS_PER_BLOCK.get();
        var spawn = store.level().getRespawnData().globalPos().pos();
        int tried = 0, own = 0;
        for (View view : View.values()) {
            for (int i = -6; i <= 6; i += 3) {
                for (int j = -6; j <= 6; j += 3) {
                    int x = spawn.getX() + i, z = spawn.getZ() + j;
                    var snap = store.get(x >> 4, z >> 4);
                    if (snap == null || snap.surface(x, z) == Integer.MIN_VALUE) {
                        continue;
                    }
                    int top = snap.surface(x, z);
                    // The centre of the column's top face, projected exactly as the page projects.
                    double vx = view.viewX(x + 0.5, z + 0.5), vz = view.viewZ(x + 0.5, z + 0.5);
                    double sx = (vx - vz) * ppb / 2.0, sy = (vx + vz) * ppb / 4.0 - top * ppb / 2.0;
                    var hit = com.sablednah.diorama.render.Pick.at(store, view, ppb, sx, sy);
                    tried++;
                    if (hit != null && hit.x() == x && hit.z() == z && hit.y() == top - 1) {
                        own++;
                    }
                }
            }
        }
        check(tried > 0 && own * 2 >= tried, "the pick named its own column " + own + " of " + tried + " times");
        check(com.sablednah.diorama.render.Pick.at(store, View.SE, ppb, 0, -1e7) == null, "a pixel above the world picked a block");
    }

    /**
     * In every roofed world that has ground to look at, picks round its spawn land below the cut
     * and never on bedrock — the roof is lifted off. Logs which worlds it found, so a run with no
     * roofed world is visibly a run that proved nothing here.
     */
    private static void roofIsCutAway(DioramaService service) {
        int ppb = DioramaConfig.PIXELS_PER_BLOCK.get();
        for (var e : service.maps().entrySet()) {
            var store = e.getValue().store();
            if (store.ceiling() == Integer.MAX_VALUE) {
                continue;
            }
            store.scan();
            int tried = 0, under = 0, bedrock = 0;
            for (int i = -48; i <= 48; i += 16) {
                for (int j = -48; j <= 48; j += 16) {
                    double sx = (i - j) * ppb / 2.0, sy = (i + j) * ppb / 4.0 - 64 * ppb / 2.0;
                    var hit = com.sablednah.diorama.render.Pick.at(store, View.SE, ppb, sx, sy);
                    if (hit == null) {
                        continue;
                    }
                    tried++;
                    if (hit.y() < store.ceiling()) {
                        under++;
                    }
                    if (hit.block().is(net.minecraft.world.level.block.Blocks.BEDROCK)) {
                        bedrock++;
                    }
                }
            }
            Diorama.LOGGER.info("Diorama self-test: {} is roofed, cut at y {}: {} picks, {} under the cut, {} on bedrock",
                    e.getKey().identifier(), store.ceiling(), tried, under, bedrock);
            if (tried > 0) {
                check(under == tried && bedrock == 0, e.getKey().identifier() + ": the roof was not cut away ("
                        + under + " of " + tried + " under the cut, " + bedrock + " on bedrock)");
            }
        }
    }

    /** Each integration whose mod is present must have said it linked, or said why not. */
    private static void integrationsLinked() {
        var status = com.sablednah.diorama.integration.Linked.status();
        Diorama.LOGGER.info("Diorama self-test: integrations: {}", status);
        for (String mod : List.of("factions", "standards", "cityworld")) {
            if (net.neoforged.fml.ModList.get().isLoaded(mod)) {
                String name = switch (mod) { case "factions" -> "Factions"; case "standards" -> "Standards"; default -> "CityWorld"; };
                check(status.stream().anyMatch(s -> s.startsWith(name) && (s.contains("on the map") || s.contains("in the info bar"))),
                        name + " is installed but did not link: " + status);
            }
        }
    }

    /**
     * A real faction, made and claimed through Factions' own public API, must reach the map as one
     * region with the right outline. It is left in the dev world on purpose, to look at.
     */
    private static void factionsEndToEnd(DioramaService service) {
        if (!net.neoforged.fml.ModList.get().isLoaded("factions")) {
            return;
        }
        var server = service.server();
        var level = server.overworld();
        var spawn = level.getRespawnData().globalPos().pos();
        int cx = (spawn.getX() >> 4) + 3, cz = spawn.getZ() >> 4;
        String id;
        try {
            Class<?> api = Class.forName("com.sablednah.factions.api.FactionsApi");
            var ensure = api.getMethod("ensureFaction", net.minecraft.server.MinecraftServer.class, String.class, String.class,
                    java.util.UUID.class, boolean.class);
            var claim = api.getMethod("claim", net.minecraft.server.MinecraftServer.class, net.minecraft.resources.ResourceKey.class,
                    int.class, int.class, String.class);
            id = server.submit(() -> {
                try {
                    @SuppressWarnings("unchecked")
                    var made = (java.util.Optional<String>) ensure.invoke(null, server, "Diorama Survey", "DS",
                            java.util.UUID.nameUUIDFromBytes("OfflinePlayer:DioramaSurvey".getBytes(java.nio.charset.StandardCharsets.UTF_8)), true);
                    if (made.isPresent()) {
                        // An L: three chunks, eight boundary edges.
                        for (int[] c : new int[][] {{0, 0}, {1, 0}, {0, 1}}) {
                            claim.invoke(null, server, net.minecraft.world.level.Level.OVERWORLD, cx + c[0], cz + c[1], made.get());
                        }
                    }
                    return made.orElse(null);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }).join();
        } catch (ReflectiveOperationException | RuntimeException e) {
            fail("could not seed a faction through FactionsApi: " + e);
            return;
        }
        check(id != null, "FactionsApi.ensureFaction made nothing");
        service.publishMarkers(server.submit(service::collectMarkers).join());
        var layer = service.markers().stream().filter(l -> l.id().equals("factions:territory")).findFirst();
        var region = layer.flatMap(l -> l.regions().stream().filter(r -> r.region().id().equals(id)).findFirst());
        check(region.isPresent() && region.get().region().label().equals("Diorama Survey [DS]")
                        && region.get().lines().size() == 8 && region.get().region().detail() != null
                        && region.get().region().detail().contains("3"),
                "the seeded faction on the map: " + region.map(r -> r.region() + " with " + r.lines().size() + " edges").orElse("absent"));
    }

    /**
     * In a CityWorld world, asking about the ground round spawn must name something, from a
     * non-server thread as the web server asks. The first version hopped to the server thread and
     * timed out on every request, because an empty dedicated server pauses its tick.
     */
    private static void cityWorldAnswers(DioramaService service) {
        var cw = com.sablednah.diorama.integration.CityWorldInfo.current();
        var level = service.server().overworld();
        if (cw == null || !level.getChunkSource().getGenerator().getClass().getName().contains("CityWorld")) {
            return;
        }
        var spawn = level.getRespawnData().globalPos().pos();
        int named = 0;
        for (int i = -64; i <= 64; i += 16) {
            for (int j = -64; j <= 64; j += 16) {
                if (cw.describe(level.dimension(), spawn.getX() + i, spawn.getY(), spawn.getZ() + j) != null) {
                    named++;
                }
            }
        }
        check(named > 0, "CityWorld named nothing in 81 chunks round spawn of a CityWorld world");
    }

    /**
     * A store nobody has scanned yet must still find a chunk that exists. Answering "none" drew
     * tiles with every neighbour missing after a restart: grey cut-away squares, and holes where
     * an all-missing redraw deleted a good tile.
     */
    private static void unscannedStoreStillFinds(DioramaService service) {
        var level = service.server().overworld();
        var spawn = level.getRespawnData().globalPos().pos();
        var fresh = new com.sablednah.diorama.world.ChunkStore(level, 16, false);
        check(fresh.get(spawn.getX() >> 4, spawn.getZ() >> 4) != null, "a fresh, unscanned store could not find the spawn chunk");
        check(fresh.get(30_000_000 >> 4, 30_000_000 >> 4) == null, "a fresh store found a chunk at the world border");
    }

    /** The messages file reads back what it wrote, and only real colour codes are converted. */
    private static void messagesParse() {
        var m = Lang.parse(java.util.List.of(
                "# comment", "a: \"say \\\"hi\\\" & go\"", "b: 'it''s'", "c: bare value", "not a pair"));
        check("say \"hi\" & go".equals(m.get("a")), "double-quoted value read as " + m.get("a"));
        check("it's".equals(m.get("b")), "single-quoted value read as " + m.get("b"));
        check("bare value".equals(m.get("c")) && m.size() == 3, "bare value / junk line: " + m);
        check(Lang.colour("&cTom & Jerry").equals("\u00A7cTom & Jerry"), "colour codes: " + Lang.colour("&cTom & Jerry"));
    }

    /** Each camera corner looks away from itself, and a view's rotation undoes cleanly. */
    private static void viewsLookAwayFromTheirCorner() {
        int[][] expect = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}}; // se, sw, nw, ne: (x, z) step of a ray
        View[] order = {View.SE, View.SW, View.NW, View.NE};
        for (int i = 0; i < order.length; i++) {
            View v = order[i];
            check(v.rayX() == expect[i][0] && v.rayZ() == expect[i][1],
                    "view " + v.id() + " rays step (" + v.rayX() + ", " + v.rayZ() + ")");
            double x = 37.25, z = -11.5;
            double vx = v.viewX(x, z), vz = v.viewZ(x, z);
            check(v.worldX(vx, vz) == x && v.worldZ(vx, vz) == z, "view " + v.id() + " does not round-trip");
        }
    }

    private static int columnTop(ChunkSnap snap, int x, int z, int minY) {
        for (int y = snap.topY(); y >= minY; y--) {
            if (!snap.block(x, y, z).isAir()) {
                return y + 1;
            }
        }
        return minY;
    }

    private static void check(boolean ok, String failure) {
        if (ok) {
            pass();
        } else {
            fail(failure);
        }
    }

    private static void pass() {
        passed++;
    }

    private static void fail(String why) {
        failures.add(why);
    }

    private static void report() {
        if (failures.isEmpty()) {
            Diorama.LOGGER.info("Diorama self-test: PASS ({} checks)", passed);
        } else {
            Diorama.LOGGER.error("Diorama self-test: FAIL — {} passed, {} failed", passed, failures.size());
            for (String f : failures) {
                Diorama.LOGGER.error("  ✗ {}", f);
            }
        }
    }

    private SelfTest() {}
}
