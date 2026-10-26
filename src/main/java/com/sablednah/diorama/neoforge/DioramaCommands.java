package com.sablednah.diorama.neoforge;

import java.net.URI;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sablednah.diorama.render.MapRenderer;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
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
                        ctx.getSource().sendFailure(Component.literal("The map is not being served. Ask whoever runs this place."));
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
        MutableComponent line = Component.literal("Diorama: ").withStyle(ChatFormatting.GRAY);
        if (url.contains("<")) {
            return line.append(Component.literal("the map is on port " + com.sablednah.diorama.DioramaConfig.WEB_PORT.get()
                    + " of this server. (Set web.publicUrl and this becomes a link.)"));
        }
        return line.append(Component.literal(url).withStyle(style -> style
                .withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))));
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        DioramaService s = DioramaService.current();
        CommandSourceStack src = ctx.getSource();
        if (s == null) {
            src.sendFailure(Component.literal("Diorama is not running."));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("Diorama: " + s.state() + (s.stateDetail().isEmpty() ? "" : " — " + s.stateDetail())), false);
        String progress = s.fullRenderProgress();
        if (progress != null) {
            src.sendSuccess(() -> Component.literal("  full render: " + progress), false);
        }
        var models = s.models();
        if (models != null) {
            src.sendSuccess(() -> Component.literal("  block states drawn from their shape (no model): " + models.fallbackCount()), false);
        }
        for (var e : s.maps().entrySet()) {
            MapRenderer m = e.getValue();
            src.sendSuccess(() -> Component.literal("  " + e.getKey().identifier() + ": " + m.renderedTiles()
                    + " tiles drawn this session, " + m.pendingTiles() + " waiting"), false);
        }
        src.sendSuccess(() -> Component.literal("  web: " + (s.webRunning() ? s.url() : "off")), false);
        return 1;
    }

    private static int render(CommandContext<CommandSourceStack> ctx, ServerLevel level) throws CommandSyntaxException {
        DioramaService s = DioramaService.current();
        if (s == null || s.state() != DioramaService.State.READY) {
            ctx.getSource().sendFailure(Component.literal("Diorama is not ready" + (s == null ? "." : ": " + s.stateDetail())));
            return 0;
        }
        s.requestFullRender(level.dimension());
        ctx.getSource().sendSuccess(() -> Component.literal("Diorama: redrawing " + level.dimension().identifier()
                + ". It runs in the background; /diorama status says how far it got."), true);
        return 1;
    }

    private DioramaCommands() {}
}
