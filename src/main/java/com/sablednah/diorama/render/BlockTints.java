package com.sablednah.diorama.render;

import java.util.IdentityHashMap;
import java.util.Map;

import com.sablednah.diorama.asset.Assets;
import net.minecraft.core.Holder;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.DryFoliageColor;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The colour a {@code tintindex} face is multiplied by — grass, leaves, water, redstone.
 *
 * <p>The client keeps this table in {@code BlockColors.createDefault}, which a dedicated server
 * does not have. This is that table, transcribed from 26.2 and identical on 1.21.11, with the world-side colour of each
 * entry (the lily pad's in-hand and in-world greens differ, and a map is the world).</p>
 *
 * <p>The biome half needs the colour maps. {@code GrassColor} and friends are common code whose
 * pixel tables only the client fills in; {@link #initColorMaps} fills them from the same PNGs on a
 * dedicated server, after which {@code Biome.getGrassColor} — swamp noise and all — answers
 * correctly there too.</p>
 */
public final class BlockTints {

    private enum Kind { NONE, GRASS, FOLIAGE, DRY_FOLIAGE, WATER, REDSTONE, STEM, CONSTANT }

    private record Source(Kind kind, int constant) {
        static final Source NONE = new Source(Kind.NONE, -1);
        static Source of(Kind kind) {
            return new Source(kind, -1);
        }
    }

    private static final Map<Block, Source[]> TABLE = new IdentityHashMap<>();

    private static void register(Source[] layers, Block... blocks) {
        for (Block b : blocks) {
            TABLE.put(b, layers);
        }
    }

    static {
        Source grass = Source.of(Kind.GRASS);
        register(new Source[] {grass}, Blocks.LARGE_FERN, Blocks.TALL_GRASS, Blocks.FERN, Blocks.SHORT_GRASS,
                Blocks.POTTED_FERN, Blocks.BUSH, Blocks.GRASS_BLOCK, Blocks.SUGAR_CANE);
        register(new Source[] {Source.NONE, grass}, Blocks.PINK_PETALS, Blocks.WILDFLOWERS);
        register(new Source[] {new Source(Kind.CONSTANT, -10380959)}, Blocks.SPRUCE_LEAVES);
        register(new Source[] {new Source(Kind.CONSTANT, -8345771)}, Blocks.BIRCH_LEAVES);
        register(new Source[] {Source.of(Kind.FOLIAGE)}, Blocks.OAK_LEAVES, Blocks.JUNGLE_LEAVES, Blocks.ACACIA_LEAVES,
                Blocks.DARK_OAK_LEAVES, Blocks.VINE, Blocks.MANGROVE_LEAVES);
        register(new Source[] {Source.of(Kind.DRY_FOLIAGE)}, Blocks.LEAF_LITTER);
        register(new Source[] {Source.of(Kind.WATER)}, Blocks.WATER_CAULDRON, Blocks.WATER, Blocks.BUBBLE_COLUMN);
        register(new Source[] {Source.of(Kind.REDSTONE)}, Blocks.REDSTONE_WIRE);
        register(new Source[] {new Source(Kind.CONSTANT, -2046180)}, Blocks.ATTACHED_MELON_STEM, Blocks.ATTACHED_PUMPKIN_STEM);
        register(new Source[] {Source.of(Kind.STEM)}, Blocks.MELON_STEM, Blocks.PUMPKIN_STEM);
        register(new Source[] {new Source(Kind.CONSTANT, -14647248)}, Blocks.LILY_PAD);
    }

    /**
     * Fill the vanilla colour maps where nobody has: a dedicated server. Harmless in single
     * player, where the client loaded the same images already.
     */
    public static void initColorMaps(Assets assets) {
        Assets.Texture grass = assets.texture("minecraft:colormap/grass");
        Assets.Texture foliage = assets.texture("minecraft:colormap/foliage");
        Assets.Texture dry = assets.texture("minecraft:colormap/dry_foliage");
        if (grass.width() == 256 && grass.height() == 256) {
            GrassColor.init(grass.argb());
        }
        if (foliage.width() == 256 && foliage.height() == 256) {
            FoliageColor.init(foliage.argb());
        }
        if (dry.width() == 256 && dry.height() == 256) {
            DryFoliageColor.init(dry.argb());
        }
    }

    /** The RGB to multiply by, or -1 (white) for no tint. */
    public static int tint(BlockState state, int layer, @Nullable Holder<Biome> biome, int x, int z) {
        if (layer < 0) {
            return -1;
        }
        Source[] layers = TABLE.get(state.getBlock());
        Source s;
        if (layers == null) {
            // A modded block registered its own colour handler, which lives on the client only.
            // Leaves are leaves and anything else tinted is almost always grass-coloured.
            s = Source.of(state.getBlock() instanceof LeavesBlock ? Kind.FOLIAGE : Kind.GRASS);
        } else if (layer >= layers.length) {
            return -1;
        } else {
            s = layers[layer];
        }
        return switch (s.kind) {
            case NONE -> -1;
            case CONSTANT -> s.constant;
            case GRASS -> biome == null ? GrassColor.getDefaultColor() : biome.value().getGrassColor(x, z);
            case FOLIAGE -> biome == null ? -12012264 : biome.value().getFoliageColor();
            case DRY_FOLIAGE -> biome == null ? -10732494 : biome.value().getDryFoliageColor();
            case WATER -> biome == null ? 0xFF3F76E4 : biome.value().getWaterColor();
            case REDSTONE -> RedStoneWireBlock.getColorForPower(state.getValue(RedStoneWireBlock.POWER));
            case STEM -> {
                int age = state.getValue(StemBlock.AGE);
                yield ARGB.color(age * 32, 255 - age * 8, age * 4);
            }
        };
    }

    private BlockTints() {}
}
