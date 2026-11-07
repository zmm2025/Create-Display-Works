package com.zmm2025.createdisplayworks.dev.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import com.zmm2025.createdisplayworks.CreateDisplayWorks;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The console of the test dedicated server {@code devServerStart} runs: a localhost-only HTTP API for commands, with full
 * permissions, and extra players. Inert unless that run sets {@code createdisplayworks.dev.serverControl}. Quits when
 * idle or when its worktree is removed, so a forgotten server can't keep a worktree from being cleaned up.
 */
@EventBusSubscriber(modid = CreateDisplayWorks.MOD_ID, value = Dist.DEDICATED_SERVER)
public final class DevServerControl {
    private static final Logger LOGGER = LoggerFactory.getLogger("Display Works Dev Tools");
    private static final long IDLE_LIMIT_MS = TimeUnit.MINUTES.toMillis(30);
    private static volatile long lastRequest = System.currentTimeMillis();
    private static volatile int controlPort;
    private static HttpServer http;

    private DevServerControl() {}

    @SubscribeEvent
    public static void started(ServerStartedEvent event) throws IOException {
        if (!Boolean.getBoolean("createdisplayworks.dev.serverControl")) return;
        MinecraftServer server = event.getServer();
        http = DevApi.serve(0, "Display Works Dev Server Control", (path, query, body) -> {
            lastRequest = System.currentTimeMillis();
            return run(server, path, query, body);
        });
        controlPort = http.getAddress().getPort();
        String worktree = System.getProperty("createdisplayworks.dev.worktree", "");
        JsonObject info = new JsonObject();
        info.addProperty("port", controlPort);
        info.addProperty("gamePort", server.getPort());
        info.addProperty("pid", ProcessHandle.current().pid());
        Path file = Path.of(worktree, "build", "dev-server.json");
        Files.writeString(file, info.toString(), StandardCharsets.UTF_8);
        LOGGER.info("Dev server console API listening on http://127.0.0.1:{}", controlPort);
        var watcher = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "Display Works Dev Server Watcher");
            thread.setDaemon(true);
            return thread;
        });
        watcher.scheduleWithFixedDelay(
                () -> {
                    boolean idle =
                            System.currentTimeMillis() - lastRequest > IDLE_LIMIT_MS && server.getPlayerCount() == 0;
                    if (idle || !Files.exists(Path.of(worktree, ".git"))) server.halt(false);
                },
                2,
                2,
                TimeUnit.SECONDS);
    }

    /** The HTTP server's dispatcher thread would otherwise keep the process alive after the game has stopped. */
    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        if (http != null) http.stop(0);
    }

    private static JsonObject run(MinecraftServer server, String path, Map<String, String> query, String body)
            throws Exception {
        return switch (path) {
            case "/status" -> status(server);
            case "/command" -> {
                String command = body.isBlank() ? query.getOrDefault("command", "") : body;
                yield server.submit(() -> DevPlayers.run(server, server.createCommandSourceStack(), command))
                        .get(1, TimeUnit.MINUTES);
            }
            case "/player" -> DevPlayers.player(server, query);
            case "/quit" -> {
                server.halt(false);
                yield DevApi.ok();
            }
            default -> null;
        };
    }

    private static JsonObject status(MinecraftServer server) {
        JsonObject status = DevApi.ok();
        status.addProperty("gamePort", server.getPort());
        status.addProperty("port", controlPort);
        JsonArray players = new JsonArray();
        server.getPlayerList()
                .getPlayers()
                .forEach(player -> players.add(player.getGameProfile().getName()));
        status.add("players", players);
        return status;
    }
}
