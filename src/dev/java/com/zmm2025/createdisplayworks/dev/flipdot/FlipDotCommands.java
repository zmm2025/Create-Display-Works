package com.zmm2025.createdisplayworks.dev.flipdot;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.zmm2025.createdisplayworks.CreateDisplayWorks;
import com.zmm2025.createdisplayworks.dev.DevCommands;
import com.zmm2025.createdisplayworks.flipdot.FlipDotBlockEntity;
import com.zmm2025.createdisplayworks.media.MediaClip;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** {@code /displayworks_dev flipdot ...}: dev-only commands for the Flip-Dot Display. */
@EventBusSubscriber(modid = CreateDisplayWorks.MOD_ID)
public final class FlipDotCommands {
    private static final String[] PATTERNS = {"stripes", "invert", "checker"};

    private FlipDotCommands() {}

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        var animate = Commands.literal("animate")
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .then(Commands.argument("pattern", StringArgumentType.word())
                                .suggests((c, builder) -> SharedSuggestionProvider.suggest(PATTERNS, builder))
                                .executes(c -> animate(
                                        c.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(c, "pos"),
                                        StringArgumentType.getString(c, "pattern")))));
        event.getDispatcher()
                .register(DevCommands.root().then(Commands.literal("flipdot").then(animate)));
    }

    /**
     * Plays a generated clip on the board at {@code pos}, for render and performance testing: {@code stripes} scrolls
     * diagonal stripes so some discs flip each frame, {@code invert} flips every disc each frame, and {@code checker}
     * shows a still checkerboard.
     */
    private static int animate(CommandSourceStack source, BlockPos pos, String pattern) {
        if (!(source.getLevel().getBlockEntity(pos) instanceof FlipDotBlockEntity board) || board.assembly() == null) {
            source.sendFailure(Component.literal("No flip-dot board there"));
            return 0;
        }
        var group = board.assembly();
        boolean checker = pattern.equals("invert") || pattern.equals("checker");
        int columns = group.width(),
                rows = group.height(),
                count = pattern.equals("invert") ? 2 : pattern.equals("checker") ? 1 : 16;
        long[] frames = new long[columns * rows * count];
        for (int f = 0; f < count; f++)
            for (int tile = 0; tile < columns * rows; tile++)
                for (int bit = 0; bit < 64; bit++) {
                    int x = tile % columns * 8 + bit % 8, y = tile / columns * 8 + bit / 8;
                    boolean on = checker ? (x + y + f) % 2 == 0 : Math.floorMod(x + y - f, 16) < 8;
                    if (on) frames[f * columns * rows + tile] |= 1L << bit;
                }
        group.controller().installMedia(new MediaClip(columns, rows, frames));
        source.sendSuccess(
                () -> Component.literal("Playing " + pattern + " on " + columns + "x" + rows + " boards"), false);
        return 1;
    }
}
