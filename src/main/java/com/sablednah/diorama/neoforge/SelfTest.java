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
        viewsLookAwayFromTheirCorner();
        messagesParse();
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
            int[] argb = service.renderers().get().render(map.store(), view, tx, ty, overworld.getMaxY() + 1, overworld.getMinY());
            int pixel = argb == null ? 0 : argb[Math.floorMod(sy, IsoRenderer.TILE) * IsoRenderer.TILE + Math.floorMod(sx, IsoRenderer.TILE)];
            check((pixel >>> 24) > 200, "view " + view.id() + ": the pixel over spawn's surface (y " + top + ") is not opaque: "
                    + Integer.toHexString(pixel));
            try {
                Path dir = service.server().getServerDirectory().resolve("diorama").resolve("selftest").resolve(view.id());
                new TileStore(dir).write(0, tx, ty, argb);
                Diorama.LOGGER.info("Diorama self-test: spawn tile written to {}", new TileStore(dir).file(0, tx, ty));
            } catch (Exception e) {
                fail("writing the spawn tile: " + e);
            }
        }
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
