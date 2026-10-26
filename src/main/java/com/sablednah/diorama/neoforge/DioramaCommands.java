package com.sablednah.diorama.neoforge;

import java.net.URI;

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

    private DioramaCommands() {}
}
