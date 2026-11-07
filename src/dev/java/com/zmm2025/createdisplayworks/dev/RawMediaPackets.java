package com.zmm2025.createdisplayworks.dev;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import com.zmm2025.createdisplayworks.CreateDisplayWorks;
import com.zmm2025.createdisplayworks.media.*;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Development-only client command that sends the media screen's packets directly, without opening the screen, the
 * way a modified client could. Replies the server sends back are logged with a {@code RAW_MEDIA} prefix.
 */
@EventBusSubscriber(modid = CreateDisplayWorks.MOD_ID, value = Dist.CLIENT)
public final class RawMediaPackets {
    private static boolean listening;

    @SubscribeEvent
    public static void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher()
                .register(Commands.literal("flipdot_packet")
                        .then(Commands.literal("control")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .then(Commands.argument("action", StringArgumentType.word())
                                                .executes(c -> {
                                                    BlockPos pos = pos(c);
                                                    String action = StringArgumentType.getString(c, "action");
                                                    listen();
                                                    PacketDistributor.sendToServer(
                                                            new MediaNetwork.Control(pos, action));
                                                    report("sent control " + action + " to " + pos.toShortString());
                                                    return 1;
                                                }))))
                        .then(Commands.literal("upload")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .then(Commands.argument("columns", IntegerArgumentType.integer(1, 64))
                                                .then(Commands.argument("rows", IntegerArgumentType.integer(1, 64))
                                                        .executes(c -> {
                                                            BlockPos pos = pos(c);
                                                            int columns = IntegerArgumentType.getInteger(c, "columns"),
                                                                    rows = IntegerArgumentType.getInteger(c, "rows");
                                                            long[] frame = new long[columns * rows];
                                                            Arrays.fill(frame, 0xAA55AA55AA55AA55L);
                                                            listen();
                                                            MediaNetwork.upload(
                                                                    pos,
                                                                    new MediaClip(
                                                                            columns,
                                                                            rows,
                                                                            frame,
                                                                            "raw-packet-test.png"));
                                                            report("sent upload to " + pos.toShortString());
                                                            return 1;
                                                        })))))
                        // Leaves the current world and joins a server, to test as a non-op player on a dedicated
                        // server.
                        .then(Commands.literal("connect")
                                .then(Commands.argument("address", StringArgumentType.greedyString())
                                        .executes(c -> {
                                            String address = StringArgumentType.getString(c, "address");
                                            var minecraft = Minecraft.getInstance();
                                            minecraft.execute(() -> {
                                                if (minecraft.level != null) minecraft.level.disconnect();
                                                minecraft.disconnect();
                                                ConnectScreen.startConnecting(
                                                        new TitleScreen(),
                                                        minecraft,
                                                        ServerAddress.parseString(address),
                                                        new ServerData("Test server", address, ServerData.Type.OTHER),
                                                        false,
                                                        null);
                                            });
                                            return 1;
                                        }))));
    }

    private static BlockPos pos(CommandContext<CommandSourceStack> c) {
        return c.getArgument("pos", Coordinates.class).getBlockPos(c.getSource());
    }
    /** Logs what the server answers, then hands it on to the media screen as usual. */
    private static void listen() {
        if (listening) return;
        listening = true;
        var status = MediaNetwork.updateScreen;
        var editor = MediaNetwork.receiveEditor;
        MediaNetwork.updateScreen = s -> {
            report("status at " + s.pos().toShortString() + ": message='" + s.message() + "' frames=" + s.frames()
                    + " playing=" + s.playing());
            status.accept(s);
        };
        MediaNetwork.receiveEditor = p -> {
            report("editor part at " + p.pos().toShortString() + ": " + (p.offset() + p.data().length) + "/" + p.total()
                    + " bytes");
            editor.accept(p);
        };
    }

    private static void report(String text) {
        LogUtils.getLogger().info("RAW_MEDIA {}", text);
        var player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(Component.literal(text), false);
    }
}
