package com.sablednah.diorama.neoforge;

import java.net.URI;
import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sablednah.diorama.render.MapRenderer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;

/**
 * {@code /diorama} — the link, for anyone; {@code status} and {@code render} for operators.
 */
public final class DioramaCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("diorama")
                .executes(ctx -> {
                    DioramaService s = DioramaService.current();
                    if (s == null || !s.webRunning()) {
                        ctx.getSource().sendFailure(Lang.get("link.off"));
                        return 0;
                    }
                    ctx.getSource().sendSuccess(() -> link(s), false);
                    return 1;
                })
                .then(Commands.literal("status")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(DioramaCommands::status))
                .then(regionCommands())
                .then(Commands.literal("render")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> render(ctx, ctx.getSource().getLevel()))
                        .then(Commands.argument("dimension", DimensionArgument.dimension())
                                .executes(ctx -> render(ctx, DimensionArgument.getDimension(ctx, "dimension"))))));
    }

    /** The clickable line. A dedicated server with no public URL configured cannot know its own address. */
    static MutableComponent link(DioramaService s) {
        String url = s.url();
        if (url.contains("<")) {
            return Lang.get("link.port", "port", com.sablednah.diorama.DioramaConfig.WEB_PORT.get());
        }
        return Lang.get("link.url", "url", url).withStyle(style -> style.withClickEvent(new ClickEvent.OpenUrl(URI.create(url))));
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        DioramaService s = DioramaService.current();
        CommandSourceStack src = ctx.getSource();
        if (s == null) {
            src.sendFailure(Lang.get("status.not_running"));
            return 0;
        }
        src.sendSuccess(() -> Lang.get("status.state", "state", s.state(), "detail", s.stateDetail().isEmpty() ? "" : " — " + s.stateDetail()), false);
        String progress = s.fullRenderProgress();
        if (progress != null) {
            src.sendSuccess(() -> Lang.get("status.full_render", "progress", progress), false);
        }
        var models = s.models();
        if (models != null) {
            src.sendSuccess(() -> Lang.get("status.fallbacks", "count", models.fallbackCount()), false);
        }
        for (var e : s.maps().entrySet()) {
            MapRenderer m = e.getValue();
            src.sendSuccess(() -> Lang.get("status.world", "world", e.getKey().identifier(),
                    "drawn", m.renderedTiles(), "waiting", m.pendingTiles()), false);
        }
        for (String line : com.sablednah.diorama.integration.Linked.status()) {
            src.sendSuccess(() -> Lang.get("status.linked", "line", line), false);
        }
        src.sendSuccess(() -> Lang.get("status.web", "url", s.webRunning() ? s.url() : Lang.text("status.web_off")), false);
        return 1;
    }

    private static int render(CommandContext<CommandSourceStack> ctx, ServerLevel level) throws CommandSyntaxException {
        DioramaService s = DioramaService.current();
        if (s == null || s.state() != DioramaService.State.READY) {
            ctx.getSource().sendFailure(Lang.get("render.not_ready", "detail", s == null ? "." : ": " + s.stateDetail()));
            return 0;
        }
        s.requestFullRender(level.dimension());
        ctx.getSource().sendSuccess(() -> Lang.get("render.started", "world", level.dimension().identifier()), true);
        return 1;
    }

    // ---------------------------------------------------------------- /diorama region

    /**
     * Mark out a region the way Dynmap's area markers did: stand at each corner in turn. Ids are
     * {@code word()}, which is safe only because an id is limited to letters, digits and
     * {@code _-} anyway; the colour and label are greedy strings, because {@code word()} refuses
     * {@code #} and spaces and its error ("Expected whitespace") names nothing.
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> regionCommands() {
        var ids = (com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack>) (c, b) ->
                net.minecraft.commands.SharedSuggestionProvider.suggest(OwnerRegions.INSTANCE.ids(), b);
        var id = com.mojang.brigadier.arguments.StringArgumentType.word();
        var text = com.mojang.brigadier.arguments.StringArgumentType.greedyString();
        return Commands.literal("region")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("list").executes(DioramaCommands::regionList))
                .then(Commands.literal("create").then(Commands.argument("id", id).executes(c -> regionCreate(c, null))
                        .then(Commands.argument("label", text).executes(c -> regionCreate(c, string(c, "label"))))))
                .then(Commands.literal("corner").then(Commands.argument("id", id).suggests(ids).executes(DioramaCommands::regionCorner)))
                .then(Commands.literal("undo").then(Commands.argument("id", id).suggests(ids).executes(DioramaCommands::regionUndo)))
                .then(Commands.literal("label").then(Commands.argument("id", id).suggests(ids)
                        .then(Commands.argument("label", text).executes(c -> regionEdit(c, r -> r.label = string(c, "label"))))))
                .then(Commands.literal("detail").then(Commands.argument("id", id).suggests(ids)
                        .executes(c -> regionEdit(c, r -> r.detail = null))
                        .then(Commands.argument("detail", text).executes(c -> regionEdit(c, r -> r.detail = string(c, "detail"))))))
                .then(Commands.literal("colour").then(Commands.argument("id", id).suggests(ids)
                        .then(Commands.argument("colour", text).executes(DioramaCommands::regionColour))))
                .then(Commands.literal("height").then(Commands.argument("id", id).suggests(ids)
                        .then(Commands.literal("ground").executes(c -> regionEdit(c, r -> r.y = null)))
                        .then(Commands.argument("y", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .executes(c -> regionEdit(c, r -> r.y = (double) com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "y"))))))
                .then(Commands.literal("delete").then(Commands.argument("id", id).suggests(ids).executes(DioramaCommands::regionDelete)));
    }

    private static String string(CommandContext<CommandSourceStack> c, String name) {
        return com.mojang.brigadier.arguments.StringArgumentType.getString(c, name);
    }

    private static int regionList(CommandContext<CommandSourceStack> c) {
        var src = c.getSource();
        List<String> ids = OwnerRegions.INSTANCE.ids();
        var packs = OwnerRegions.INSTANCE.datapackRegions();
        if (ids.isEmpty() && packs.isEmpty()) {
            src.sendSuccess(() -> Lang.get("region.none"), false);
            return 0;
        }
        for (String id : ids) {
            var r = OwnerRegions.INSTANCE.get(id);
            if (r != null) {
                src.sendSuccess(() -> Lang.get("region.list_line", "id", id, "label", r.label, "corners", r.corners.size(),
                        "world", r.dimension.identifier()), false);
            }
        }
        packs.forEach((id, r) -> src.sendSuccess(() -> Lang.get("region.list_pack", "id", id, "label", r.label,
                "world", r.dimension.identifier()), false));
        return ids.size() + packs.size();
    }

    private static int regionCreate(CommandContext<CommandSourceStack> c, String label) {
        String id = string(c, "id");
        if (!id.matches("[a-z0-9_\\-]+")) {
            c.getSource().sendFailure(Lang.get("region.bad_id", "id", id));
            return 0;
        }
        if (OwnerRegions.INSTANCE.exists(id)) {
            c.getSource().sendFailure(Lang.get("region.exists", "id", id));
            return 0;
        }
        OwnerRegions.INSTANCE.put(id, new OwnerRegions.Owned(label == null ? id : label, c.getSource().getLevel().dimension()));
        c.getSource().sendSuccess(() -> Lang.get("region.created", "id", id), true);
        return 1;
    }

    private static int regionCorner(CommandContext<CommandSourceStack> c) {
        var r = owned(c);
        if (r == null) {
            return 0;
        }
        var src = c.getSource();
        if (src.getLevel().dimension() != r.dimension) {
            src.sendFailure(Lang.get("region.wrong_world", "world", r.dimension.identifier()));
            return 0;
        }
        var pos = net.minecraft.core.BlockPos.containing(src.getPosition());
        r.corners.add(new double[] {pos.getX(), pos.getZ()});
        OwnerRegions.INSTANCE.save();
        int n = r.corners.size();
        src.sendSuccess(() -> Lang.get(n < 3 ? "region.corner_more" : "region.corner", "n", n, "x", pos.getX(), "z", pos.getZ(),
                "left", 3 - n), false);
        return n;
    }

    private static int regionUndo(CommandContext<CommandSourceStack> c) {
        var r = owned(c);
        if (r == null) {
            return 0;
        }
        if (r.corners.isEmpty()) {
            c.getSource().sendFailure(Lang.get("region.no_corners"));
            return 0;
        }
        r.corners.remove(r.corners.size() - 1);
        OwnerRegions.INSTANCE.save();
        c.getSource().sendSuccess(() -> Lang.get("region.undone", "n", r.corners.size()), false);
        return 1;
    }

    private static int regionColour(CommandContext<CommandSourceStack> c) {
        Integer colour = OwnerRegions.colour(string(c, "colour"));
        if (colour == null) {
            c.getSource().sendFailure(Lang.get("region.bad_colour", "colour", string(c, "colour")));
            return 0;
        }
        return regionEdit(c, r -> r.colour = colour);
    }

    private static int regionEdit(CommandContext<CommandSourceStack> c, java.util.function.Consumer<OwnerRegions.Owned> edit) {
        var r = owned(c);
        if (r == null) {
            return 0;
        }
        edit.accept(r);
        OwnerRegions.INSTANCE.save();
        c.getSource().sendSuccess(() -> Lang.get("region.saved", "id", string(c, "id")), false);
        return 1;
    }

    private static int regionDelete(CommandContext<CommandSourceStack> c) {
        String id = string(c, "id");
        if (!OwnerRegions.INSTANCE.remove(id)) {
            c.getSource().sendFailure(Lang.get("region.unknown", "id", id));
            return 0;
        }
        c.getSource().sendSuccess(() -> Lang.get("region.deleted", "id", id), true);
        return 1;
    }

    private static OwnerRegions.@org.jspecify.annotations.Nullable Owned owned(CommandContext<CommandSourceStack> c) {
        String id = string(c, "id");
        var r = OwnerRegions.INSTANCE.get(id);
        if (r == null) {
            c.getSource().sendFailure(Lang.get(OwnerRegions.INSTANCE.datapackRegions().containsKey(id) ? "region.from_pack" : "region.unknown", "id", id));
        }
        return r;
    }

    private DioramaCommands() {}
}
