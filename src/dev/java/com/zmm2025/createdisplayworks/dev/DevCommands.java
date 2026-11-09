package com.zmm2025.createdisplayworks.dev;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * The root of the dev-only commands, {@code /displayworks_dev <feature> ...}. Each feature registers its own
 * subcommands from its own class, so adding one edits nothing here:
 *
 * <pre>{@code
 * @SubscribeEvent
 * public static void commands(RegisterCommandsEvent event) {
 *     event.getDispatcher().register(DevCommands.root().then(Commands.literal("gauge").then(...)));
 * }
 * }</pre>
 *
 * Brigadier merges every registration of the same root. Commands the client runs, such as {@code media packet}, use
 * {@link #clientRoot()} in a {@code RegisterClientCommandsEvent} handler instead; the client hands any subcommand it
 * doesn't know on to the server.
 */
public final class DevCommands {
    public static final String ROOT = "displayworks_dev";

    private DevCommands() {}

    /** The root for server commands, which need operator permissions. */
    public static LiteralArgumentBuilder<CommandSourceStack> root() {
        return Commands.literal(ROOT).requires(source -> source.hasPermission(2));
    }

    /** The root for client commands, which act as the player's own client would, so they need no permissions. */
    public static LiteralArgumentBuilder<CommandSourceStack> clientRoot() {
        return Commands.literal(ROOT);
    }
}
