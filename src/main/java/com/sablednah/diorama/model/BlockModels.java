package com.sablednah.diorama.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.math.Quadrant;
import com.mojang.math.Transformation;
import com.sablednah.diorama.Diorama;
import com.sablednah.diorama.asset.Assets;
import com.sablednah.diorama.asset.Assets.Texture;
import net.minecraft.core.BlockMath;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Turns a {@link BlockState} into faces, from the same JSON the client renders it from.
 *
 * <p>This is a port of the client's model pipeline, not an approximation of it, and the
 * difference is the whole reason Diorama exists: Schemy drew stairs from hand-written geometry and
 * was never quite right. Here the blockstate file picks the variant, the model chain supplies the
 * elements, and {@link #bakeFace} follows {@code FaceBakery.bakeQuad} step for step — the same
 * vertex order, the same default UVs, the same element rotation and rescale, the same variant
 * rotation about the block centre and the same uvlock maths. The rotation helpers it leans on
 * ({@link BlockMath}, {@link Quadrant}, {@link Transformation}) are common code on 26.x, so the
 * server reaches the very methods the client calls.</p>
 *
 * <p>Results are cached per state. A state that resolves to nothing becomes {@link Baked#EMPTY},
 * and the count of those is what the self-test reports.</p>
 */
public final class BlockModels {

    private static final float[] SHADE_BY_DIRECTION = new float[6];

    static {
        // The game's own directional light: tops full, the two side pairs dimmed by different
        // amounts, undersides darkest. It is what makes a cube read as a cube.
        SHADE_BY_DIRECTION[Direction.DOWN.ordinal()] = 0.5f;
        SHADE_BY_DIRECTION[Direction.UP.ordinal()] = 1.0f;
        SHADE_BY_DIRECTION[Direction.NORTH.ordinal()] = 0.8f;
        SHADE_BY_DIRECTION[Direction.SOUTH.ordinal()] = 0.8f;
        SHADE_BY_DIRECTION[Direction.WEST.ordinal()] = 0.6f;
        SHADE_BY_DIRECTION[Direction.EAST.ordinal()] = 0.6f;
    }

    /**
     * What a state draws as. {@code alternatives} are weighted random variants (grass and stone
     * rotate per block in game); the renderer picks one from the block position so the choice is
     * stable between renders.
     */
    public record Baked(Quad[][] alternatives, int[] cumulativeWeights, boolean fallback) {
        public static final Baked EMPTY = new Baked(new Quad[][] {new Quad[0]}, new int[] {1}, false);

        public Quad[] pick(int x, int y, int z) {
            if (alternatives.length == 1) {
                return alternatives[0];
            }
            long seed = (x * 3129871L) ^ (z * 116129781L) ^ y;
            seed = seed * seed * 42317861L + seed * 11L;
            int total = cumulativeWeights[cumulativeWeights.length - 1];
            int roll = (int) Math.floorMod(seed >> 16, (long) total);
            for (int i = 0; i < cumulativeWeights.length; i++) {
                if (roll < cumulativeWeights[i]) {
                    return alternatives[i];
                }
            }
            return alternatives[0];
        }

        public boolean isEmpty() {
            for (Quad[] q : alternatives) {
                if (q.length > 0) {
                    return false;
                }
            }
            return true;
        }
    }

    private final Assets assets;
    private final Map<BlockState, Baked> baked = new ConcurrentHashMap<>();
    private final Map<String, JsonObject> blockstateFiles = new ConcurrentHashMap<>();
    private final Map<String, ResolvedModel> models = new ConcurrentHashMap<>();
    private final AtomicInteger fallbacks = new AtomicInteger();
    private final Set<Identifier> brokenBlocks = ConcurrentHashMap.newKeySet();

    public BlockModels(Assets assets) {
        this.assets = assets;
    }

    public Assets assets() {
        return assets;
    }

    /** How many states had no model and were drawn from their shape instead. */
    public int fallbackCount() {
        return fallbacks.get();
    }

    public Baked get(BlockState state) {
        Baked b = baked.get(state);
        if (b == null) {
            b = bake(state);
            baked.put(state, b);
        }
        return b;
    }

    private Baked bake(BlockState state) {
        // Air, and everything the game itself never draws: barriers, light blocks, structure
        // voids. Fluids are INVISIBLE too — the renderer draws them from the fluid state.
        if (state.isAir() || state.getRenderShape() == net.minecraft.world.level.block.RenderShape.INVISIBLE) {
            return Baked.EMPTY;
        }
        try {
            Baked b = bakeFromJson(state);
            if (b != null && !b.isEmpty()) {
                return b;
            }
        } catch (RuntimeException e) {
            Diorama.LOGGER.debug("Diorama: model for {} failed: {}", state, e.toString());
        }
        // The fallback must not throw either: an exception here escapes get() uncached, so every
        // tile holding the block fails, and is retried, every pass (ZARP: 463 warnings a session
        // from AE2, Lootr and Sophisticated Storage). Draw nothing, once, and say which block.
        try {
            return bakeFromShape(state);
        } catch (RuntimeException e) {
            Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            if (brokenBlocks.add(id)) {
                Diorama.LOGGER.warn("Diorama: cannot draw {}, leaving it out of the map: {}", id, e.toString());
            }
            return Baked.EMPTY;
        }
    }

    // ---------------------------------------------------------------- blockstate dispatch

    private @Nullable Baked bakeFromJson(BlockState state) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        JsonObject file = blockstateFiles.computeIfAbsent(id.toString(),
                k -> {
                    JsonObject j = assets.json("blockstates", id.getNamespace(), id.getPath());
                    return j == null ? new JsonObject() : j;
                });

        if (file.has("variants")) {
            for (Map.Entry<String, JsonElement> entry : file.getAsJsonObject("variants").entrySet()) {
                if (matchesVariantKey(state, entry.getKey())) {
                    return bakeWeighted(entry.getValue());
                }
            }
            return null;
        }
        if (file.has("multipart")) {
            // Every matching part applies at once (fence posts plus arms, wall sides). Each part
            // takes its first variant: a multipart block with weighted parts is vanishingly rare.
            List<Quad> all = new ArrayList<>();
            for (JsonElement partEl : file.getAsJsonArray("multipart")) {
                JsonObject part = partEl.getAsJsonObject();
                if (part.has("when") && !matchesCondition(state, part.getAsJsonObject("when"))) {
                    continue;
                }
                JsonElement apply = part.get("apply");
                JsonObject variant = apply.isJsonArray() ? apply.getAsJsonArray().get(0).getAsJsonObject() : apply.getAsJsonObject();
                all.addAll(bakeVariant(variant));
            }
            return new Baked(new Quad[][] {all.toArray(Quad[]::new)}, new int[] {1}, false);
        }
        return null;
    }

    private Baked bakeWeighted(JsonElement variants) {
        if (!variants.isJsonArray()) {
            return new Baked(new Quad[][] {bakeVariant(variants.getAsJsonObject()).toArray(Quad[]::new)}, new int[] {1}, false);
        }
        JsonArray array = variants.getAsJsonArray();
        Quad[][] alternatives = new Quad[array.size()][];
        int[] cumulative = new int[array.size()];
        int total = 0;
        for (int i = 0; i < array.size(); i++) {
            JsonObject v = array.get(i).getAsJsonObject();
            alternatives[i] = bakeVariant(v).toArray(Quad[]::new);
            total += v.has("weight") ? Math.max(1, v.get("weight").getAsInt()) : 1;
            cumulative[i] = total;
        }
        return new Baked(alternatives, cumulative, false);
    }

    /** {@code ""}, or {@code facing=north,half=top}: every listed property must match. */
    static boolean matchesVariantKey(BlockState state, String key) {
        if (key.isEmpty()) {
            return true;
        }
        for (String pair : key.split(",")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                return false;
            }
            String value = valueOf(state, pair.substring(0, eq));
            if (value == null || !value.equals(pair.substring(eq + 1))) {
                return false;
            }
        }
        return true;
    }

    /**
     * A multipart {@code when}: {@code OR} / {@code AND} lists, or property tests where a value
     * may be {@code a|b} and a term may be negated with {@code !} — the 26.2 grammar, from
     * {@code KeyValueCondition}.
     */
    static boolean matchesCondition(BlockState state, JsonObject when) {
        if (when.has("OR")) {
            for (JsonElement e : when.getAsJsonArray("OR")) {
                if (matchesCondition(state, e.getAsJsonObject())) {
                    return true;
                }
            }
            return false;
        }
        if (when.has("AND")) {
            for (JsonElement e : when.getAsJsonArray("AND")) {
                if (!matchesCondition(state, e.getAsJsonObject())) {
                    return false;
                }
            }
            return true;
        }
        for (Map.Entry<String, JsonElement> test : when.entrySet()) {
            String actual = valueOf(state, test.getKey());
            if (actual == null) {
                return false;
            }
            boolean any = false;
            for (String term : test.getValue().getAsString().split("\\|")) {
                boolean negated = term.startsWith("!");
                String value = negated ? term.substring(1) : term;
                if (actual.equals(value) != negated) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    private static @Nullable String valueOf(BlockState state, String propertyName) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty(propertyName);
        return property == null ? null : name(state, property);
    }

    private static <T extends Comparable<T>> String name(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    // ---------------------------------------------------------------- variants and models

    private List<Quad> bakeVariant(JsonObject variant) {
        String ref = modelRef(variant);
        ResolvedModel model = ref == null ? null : model(ref);
        if (model == null || model.elements.isEmpty()) {
            return List.of();
        }
        Quadrant x = quadrant(variant.has("x") ? variant.get("x").getAsInt() : 0);
        Quadrant y = quadrant(variant.has("y") ? variant.get("y").getAsInt() : 0);
        Quadrant z = quadrant(variant.has("z") ? variant.get("z").getAsInt() : 0);
        boolean uvlock = variant.has("uvlock") && variant.get("uvlock").getAsBoolean();

        var group = Quadrant.fromXYZAngles(x, y, z);
        Transformation rotation = group == com.mojang.math.OctahedralGroup.IDENTITY
                ? Transformation.IDENTITY
                : new Transformation(new Matrix4f(group.transformation()));

        List<Quad> out = new ArrayList<>();
        for (JsonObject element : model.elements) {
            bakeElement(element, model, rotation, uvlock, out);
        }
        return out;
    }

    /**
     * The model a variant names. Ordinarily {@code "model"}; but a variant with a {@code "type"}
     * belongs to a mod's own block-state model loader ({@code ae2:crafting_cube},
     * {@code lootr:custom}), which only that mod's client code can run. Its fields are the loader's
     * own, and often name ordinary models — Lootr's barrel carries {@code "vanilla":
     * "minecraft:block/barrel"} — so the first one that resolves to a model with geometry stands
     * in. None, and the block is drawn from its shape like any entity-rendered block.
     */
    public @Nullable String modelRef(JsonObject variant) {
        JsonElement m = variant.get("model");
        if (m != null && m.isJsonPrimitive()) {
            return m.getAsString();
        }
        if (!variant.has("type")) {
            return null;
        }
        for (Map.Entry<String, JsonElement> field : variant.entrySet()) {
            JsonElement v = field.getValue();
            if (field.getKey().equals("type") || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) {
                continue;
            }
            String candidate = v.getAsString();
            if (Identifier.tryParse(candidate) == null) {
                continue;
            }
            ResolvedModel r = model(candidate);
            if (r != null && !r.elements.isEmpty()) {
                return candidate;
            }
        }
        return null;
    }

    /** A model with its parent chain flattened: the nearest {@code elements}, every texture slot. */
    private record ResolvedModel(List<JsonObject> elements, Map<String, String> textures) {}

    private @Nullable ResolvedModel model(String id) {
        ResolvedModel cached = models.get(id);
        if (cached != null) {
            return cached;
        }
        List<JsonObject> elements = null;
        Map<String, String> textures = new HashMap<>();
        String next = id;
        for (int depth = 0; depth < 32 && next != null && !next.isEmpty(); depth++) {
            Identifier mid = Identifier.parse(next);
            JsonObject json = assets.json("models", mid.getNamespace(), mid.getPath());
            if (json == null) {
                break;
            }
            if (json.has("textures")) {
                for (Map.Entry<String, JsonElement> t : json.getAsJsonObject("textures").entrySet()) {
                    // Child first, so a slot already set by a child is never overwritten by its
                    // parent. A value may be a plain id or 26.2's {"sprite": ...} object.
                    JsonElement v = t.getValue();
                    String value = v.isJsonObject() ? v.getAsJsonObject().get("sprite").getAsString() : v.getAsString();
                    textures.putIfAbsent(t.getKey(), value);
                }
            }
            if (elements == null && json.has("elements")) {
                elements = new ArrayList<>();
                for (JsonElement e : json.getAsJsonArray("elements")) {
                    elements.add(e.getAsJsonObject());
                }
            }
            next = json.has("parent") ? json.get("parent").getAsString() : null;
        }
        ResolvedModel resolved = new ResolvedModel(elements == null ? List.of() : elements, textures);
        models.put(id, resolved);
        return resolved;
    }

    /**
     * A face's {@code texture} is always a slot name, with or without the {@code #}: heavy_core
     * writes {@code "all"} where every other model writes {@code "#all"}, and the game accepts
     * both. Slot values may in turn point at other slots.
     */
    private Texture texture(ResolvedModel model, String ref) {
        String value = ref.startsWith("#") ? ref : "#" + ref;
        for (int depth = 0; depth < 16 && value != null && value.startsWith("#"); depth++) {
            value = model.textures.get(value.substring(1));
        }
        if (value == null || value.startsWith("#")) {
            return Texture.MISSING;
        }
        return assets.texture(value);
    }

    // ---------------------------------------------------------------- baking, after FaceBakery

    private void bakeElement(JsonObject element, ResolvedModel model, Transformation rotation, boolean uvlock, List<Quad> out) {
        Vector3f from = vec(element.getAsJsonArray("from"));
        Vector3f to = vec(element.getAsJsonArray("to"));
        // 26.3 replaced the element's "shade": false with "shade_direction_override": <direction>, and
        // the client no longer reads "shade" at all: a face is lit as if it faced the override
        // (BlockModelLighter.getDirectionalBrightness), not by its own direction. The override is
        // used as written, never turned with the variant, exactly as FaceBakery passes it through.
        Direction shadeOverride = element.has("shade_direction_override")
                ? Direction.byName(element.get("shade_direction_override").getAsString()) : null;

        Vector3f rotOrigin = null;
        Matrix4f rotMatrix = null;
        if (element.has("rotation")) {
            JsonObject r = element.getAsJsonObject("rotation");
            rotOrigin = vec(r.getAsJsonArray("origin")).mul(1f / 16f);
            if (r.has("axis") || r.has("angle")) {
                Direction.Axis axis = Direction.Axis.byName(r.get("axis").getAsString());
                float angle = r.get("angle").getAsFloat();
                rotMatrix = new Matrix4f();
                if (angle != 0f && axis != null) {
                    rotMatrix.rotation((float) Math.toRadians(angle), axis.getPositive().getUnitVec3f());
                }
            } else {
                float rx = r.has("x") ? r.get("x").getAsFloat() : 0f;
                float ry = r.has("y") ? r.get("y").getAsFloat() : 0f;
                float rz = r.has("z") ? r.get("z").getAsFloat() : 0f;
                rotMatrix = new Matrix4f().rotationZYX((float) Math.toRadians(rz), (float) Math.toRadians(ry), (float) Math.toRadians(rx));
            }
            if (r.has("rescale") && r.get("rescale").getAsBoolean()) {
                rotMatrix.scale(rescale(rotMatrix, Direction.Axis.X), rescale(rotMatrix, Direction.Axis.Y), rescale(rotMatrix, Direction.Axis.Z));
            }
        }

        JsonObject faces = element.getAsJsonObject("faces");
        for (Map.Entry<String, JsonElement> f : faces.entrySet()) {
            Direction facing = Direction.byName(f.getKey());
            if (facing == null) {
                continue;
            }
            JsonObject face = f.getValue().getAsJsonObject();
            float[] uvs;
            if (face.has("uv")) {
                JsonArray a = face.getAsJsonArray("uv");
                uvs = new float[] {a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat(), a.get(3).getAsFloat()};
            } else {
                uvs = defaultFaceUV(from, to, facing);
            }
            int uvRotation = face.has("rotation") ? quadrant(face.get("rotation").getAsInt()).shift : 0;
            int tint = face.has("tintindex") ? face.get("tintindex").getAsInt() : -1;
            Texture tex = texture(model, face.has("texture") ? face.get("texture").getAsString() : "#missing");
            Quad q = bakeFace(from, to, uvs, uvRotation, facing, rotation, uvlock, rotOrigin, rotMatrix, tex, tint, shadeOverride);
            if (q != null) {
                out.add(q);
            }
        }
    }

    private static float rescale(Matrix4fc rotation, Direction.Axis axis) {
        Vector3f t = rotation.transformDirection(new Vector3f(axis.getPositive().getUnitVec3f()));
        return 1f / Math.max(Math.max(Math.abs(t.x), Math.abs(t.y)), Math.abs(t.z));
    }

    /** {@code FaceBakery.defaultFaceUV}, verbatim. */
    static float[] defaultFaceUV(Vector3f from, Vector3f to, Direction facing) {
        return switch (facing) {
            case DOWN -> new float[] {from.x(), 16f - to.z(), to.x(), 16f - from.z()};
            case UP -> new float[] {from.x(), from.z(), to.x(), to.z()};
            case NORTH -> new float[] {16f - to.x(), 16f - to.y(), 16f - from.x(), 16f - from.y()};
            case SOUTH -> new float[] {from.x(), 16f - to.y(), to.x(), 16f - from.y()};
            case WEST -> new float[] {from.z(), 16f - to.y(), to.z(), 16f - from.y()};
            case EAST -> new float[] {16f - to.z(), 16f - to.y(), 16f - from.z(), 16f - from.y()};
        };
    }

    /**
     * {@code FaceInfo}: which corner of the element each of a face's four vertices sits on.
     * Values index {@code [minX, minY, minZ, maxX, maxY, maxZ]}.
     */
    private static final int[][][] FACE_INFO = {
            /* DOWN  */ {{0, 1, 5}, {0, 1, 2}, {3, 1, 2}, {3, 1, 5}},
            /* UP    */ {{0, 4, 2}, {0, 4, 5}, {3, 4, 5}, {3, 4, 2}},
            /* NORTH */ {{3, 4, 2}, {3, 1, 2}, {0, 1, 2}, {0, 4, 2}},
            /* SOUTH */ {{0, 4, 5}, {0, 1, 5}, {3, 1, 5}, {3, 4, 5}},
            /* WEST  */ {{0, 4, 2}, {0, 1, 2}, {0, 1, 5}, {0, 4, 5}},
            /* EAST  */ {{3, 4, 5}, {3, 1, 5}, {3, 1, 2}, {3, 4, 2}},
    };

    private static final Vector3f BLOCK_MIDDLE = new Vector3f(0.5f, 0.5f, 0.5f);

    private static @Nullable Quad bakeFace(Vector3f from, Vector3f to, float[] uvs, int uvShift, Direction facing,
                                           Transformation rotation, boolean uvlock,
                                           @Nullable Vector3f rotOrigin, @Nullable Matrix4f rotMatrix,
                                           Texture tex, int tint, @Nullable Direction shadeOverride) {
        float[] ext = {from.x(), from.y(), from.z(), to.x(), to.y(), to.z()};
        int[][] info = FACE_INFO[facing.ordinal()];

        Matrix4fc uvTransform = null;
        if (uvlock && rotation != Transformation.IDENTITY) {
            uvTransform = BlockMath.getFaceTransformation(rotation, facing).getMatrix().invertAffine(new Matrix4f());
        }

        Vector3f[] pos = new Vector3f[4];
        float[] uv = new float[8];
        for (int i = 0; i < 4; i++) {
            Vector3f v = new Vector3f(ext[info[i][0]], ext[info[i][1]], ext[info[i][2]]).div(16f);
            if (rotMatrix != null) {
                v.sub(rotOrigin);
                rotMatrix.transformPosition(v);
                v.add(rotOrigin);
            }
            if (rotation != Transformation.IDENTITY) {
                v.sub(BLOCK_MIDDLE);
                rotation.getMatrix().transformPosition(v);
                v.add(BLOCK_MIDDLE);
            }
            pos[i] = v;

            // CuboidFace.getU/getV: the face's own rotation shifts which corner each vertex reads.
            int corner = (i + uvShift) % 4;
            float u = (corner == 0 || corner == 1 ? uvs[0] : uvs[2]) / 16f;
            float w = (corner == 0 || corner == 3 ? uvs[1] : uvs[3]) / 16f;
            if (uvTransform != null) {
                Vector3f t = uvTransform.transformPosition(new Vector3f(u - 0.5f, w - 0.5f, 0f));
                u = t.x + 0.5f;
                w = t.y + 0.5f;
            }
            uv[i * 2] = u;
            uv[i * 2 + 1] = w;
        }

        Vector3f e1 = new Vector3f(pos[1]).sub(pos[0]);
        Vector3f e3 = new Vector3f(pos[3]).sub(pos[0]);
        Vector3f n = new Vector3f(e1).cross(e3);
        if (n.lengthSquared() == 0f) {
            return null;
        }
        Direction lit = Direction.getApproximateNearest(n.x, n.y, n.z);
        return new Quad(
                new float[] {pos[0].x, pos[0].y, pos[0].z},
                new float[] {e1.x, e1.y, e1.z},
                new float[] {e3.x, e3.y, e3.z},
                new float[] {n.x, n.y, n.z},
                new float[] {uv[0], uv[1], uv[2], uv[3], uv[6], uv[7]},
                tex, tint, SHADE_BY_DIRECTION[(shadeOverride != null ? shadeOverride : lit).ordinal()]);
    }

    /** Degrees to a quarter turn. Anything but 0/90/180/270 is a broken model; treat it as 0. */
    private static Quadrant quadrant(int degrees) {
        return switch (Math.floorMod(degrees, 360)) {
            case 90 -> Quadrant.R90;
            case 180 -> Quadrant.R180;
            case 270 -> Quadrant.R270;
            default -> Quadrant.R0;
        };
    }

    private static Vector3f vec(JsonArray a) {
        return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
    }

    // ---------------------------------------------------------------- the fallback

    /**
     * Chests, signs, beds, banners, heads: the game draws these with entity renderers, so their
     * block model is a particle texture and nothing else. Rather than a hole, draw the block's own
     * outline shape in its particle texture — a chest comes out chest-sized and chest-coloured.
     */
    private Baked bakeFromShape(BlockState state) {
        if (!state.getFluidState().isEmpty() && state.getFluidState().createLegacyBlock() == state) {
            // A bare fluid block. The renderer draws fluids itself; it is not a missing model.
            return Baked.EMPTY;
        }
        List<AABB> boxes;
        try {
            boxes = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs();
        } catch (RuntimeException e) {
            return Baked.EMPTY;
        }
        if (boxes.isEmpty()) {
            return Baked.EMPTY;
        }
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        Texture tex = particleTexture(state, id);
        if (tex == Texture.MISSING) {
            // No texture and no map colour (Lootr's trophy): the checkerboard would mark a fault
            // where there is only a block the map has no way to draw.
            return Baked.EMPTY;
        }
        List<Quad> out = new ArrayList<>();
        for (AABB box : boxes) {
            Vector3f from = new Vector3f((float) box.minX * 16f, (float) box.minY * 16f, (float) box.minZ * 16f);
            Vector3f to = new Vector3f((float) box.maxX * 16f, (float) box.maxY * 16f, (float) box.maxZ * 16f);
            for (Direction d : Direction.values()) {
                Quad q = bakeFace(from, to, defaultFaceUV(from, to, d), 0, d, Transformation.IDENTITY, false, null, null, tex, -1, null);
                if (q != null) {
                    out.add(q);
                }
            }
        }
        fallbacks.incrementAndGet();
        return new Baked(new Quad[][] {out.toArray(Quad[]::new)}, new int[] {1}, true);
    }

    private Texture particleTexture(BlockState state, Identifier id) {
        JsonObject file = blockstateFiles.get(id.toString());
        if (file != null && file.has("variants")) {
            for (Map.Entry<String, JsonElement> entry : file.getAsJsonObject("variants").entrySet()) {
                JsonElement v = entry.getValue();
                JsonElement first = v.isJsonArray() ? v.getAsJsonArray().get(0) : v;
                String ref = first.isJsonObject() ? modelRef(first.getAsJsonObject()) : null;
                ResolvedModel m = ref == null ? null : model(ref);
                if (m != null && m.textures.containsKey("particle")) {
                    return texture(m, "#particle");
                }
            }
        }
        Texture own = assets.texture(id.getNamespace() + ":block/" + id.getPath());
        if (own != Texture.MISSING) {
            return own;
        }
        // Nothing to name a texture by — an AE2 cable's model is built in code. The block's own map
        // colour, flat, reads as "something is here" where the checkerboard reads as a fault.
        int col;
        try {
            col = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
        } catch (RuntimeException e) {
            col = 0;
        }
        return col == 0 ? Texture.MISSING : new Texture(1, 1, new int[] {0xFF000000 | col});
    }
}
