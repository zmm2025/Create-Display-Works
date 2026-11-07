package com.zmm2025.createdisplayworks.dev.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs server commands and adds extra players to a server, the way vanilla's game tests do: a server-side player with
 * no client, who shows up in the world and the player list. It has no operator permissions unless {@code op=true}, so
 * it can stand in for a stranger when testing permissions and land claims. Works on the integrated and dedicated
 * server, so this class must stay clear of client classes.
 */
final class DevPlayers {
    private DevPlayers() {}

    /** Runs a command as {@code source}, capturing what it prints. Runs on the server thread. */
    static JsonObject run(MinecraftServer server, CommandSourceStack source, String command) {
        String text = command.strip().startsWith("/") ? command.strip().substring(1) : command.strip();
        List<String> output = new ArrayList<>();
        boolean[] success = {false};
        int[] value = {0};
        CommandSource sink = new CommandSource() {
            @Override public void sendSystemMessage(Component message) { output.add(message.getString()); }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
        server.getCommands().performPrefixedCommand(source.withSource(sink).withCallback((ok, result) -> { success[0] |= ok; value[0] = result; }), text);
        JsonObject response = new JsonObject();
        response.addProperty("ok", success[0]);
        response.addProperty("result", value[0]);
        JsonArray lines = new JsonArray();
        output.forEach(lines::add);
        response.add("output", lines);
        return response;
    }

    /**
     * {@code name} joins if it isn't on the server yet, then, in this order: {@code op=true} or {@code op=false} sets its
     * operator status, {@code command} runs a command as it, and {@code x y z} with an optional {@code face} right-clicks
     * that block with its main hand. {@code leave=true} takes it off the server. Without {@code name}, lists the players.
     */
    static JsonObject player(MinecraftServer server, Map<String, String> query) throws Exception {
        return server.submit(() -> {
            JsonObject response = new JsonObject();
            response.addProperty("ok", true);
            String name = query.get("name");
            if(name == null) {
                JsonArray players = new JsonArray();
                server.getPlayerList().getPlayers().forEach(player -> players.add(player.getGameProfile().getName()));
                response.add("players", players);
                return response;
            }
            ServerPlayer player = server.getPlayerList().getPlayerByName(name);
            if(Boolean.parseBoolean(query.get("leave"))) {
                if(player != null) player.connection.disconnect(Component.literal("Left"));
                return response;
            }
            if(player == null) {
                player = join(server, name);
                response.addProperty("joined", true);
            }
            if(query.containsKey("op")) {
                if(Boolean.parseBoolean(query.get("op"))) server.getPlayerList().op(player.getGameProfile());
                else server.getPlayerList().deop(player.getGameProfile());
            }
            if(query.containsKey("command")) response.add("command", run(server, player.createCommandSourceStack(), query.get("command")));
            if(query.containsKey("x")) {
                BlockPos pos = new BlockPos(Integer.parseInt(query.get("x")), Integer.parseInt(query.get("y")), Integer.parseInt(query.get("z")));
                Direction face = Direction.byName(query.getOrDefault("face", "up"));
                var hit = new BlockHitResult(Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5)), face, pos, false);
                response.addProperty("use", player.gameMode.useItemOn(player, player.serverLevel(), player.getMainHandItem(), InteractionHand.MAIN_HAND, hit).toString());
            }
            response.addProperty("pos", DevPlayers.round(player.getX()) + " " + DevPlayers.round(player.getY()) + " " + DevPlayers.round(player.getZ()));
            return response;
        }).get(1, TimeUnit.MINUTES);
    }

    private static ServerPlayer join(MinecraftServer server, String name) {
        GameProfile profile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(name), name);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(server, server.overworld(), profile, cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        // Mods send payloads when a player joins, and NeoForge refuses channels nobody negotiated.
        NetworkRegistry.configureMockConnection(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
