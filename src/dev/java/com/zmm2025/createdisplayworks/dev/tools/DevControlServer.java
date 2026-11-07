package com.zmm2025.createdisplayworks.dev.tools;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Map;
import net.minecraft.client.Minecraft;

/** Localhost-only HTTP API for driving the dev client. Requests run one at a time. Endpoints are listed in DEVELOPMENT.md. */
final class DevControlServer {
    private DevControlServer() {}

    /** Starts the API on {@code port}, or on a free port for 0, and returns the port it listens on. */
    static int start(int port) throws IOException {
        return DevApi.serve(port, "Display Works Dev Control", (path, query, body) -> {
                    DevInstance.touch();
                    return run(path, query, body);
                })
                .getAddress()
                .getPort();
    }

    /** Runs an endpoint from a batch step, given its path and query the way a request gives them. */
    static JsonObject call(String target, String body) throws Exception {
        int split = target.indexOf('?');
        JsonObject response = run(
                split < 0 ? target : target.substring(0, split),
                DevApi.query(split < 0 ? null : target.substring(split + 1)),
                body);
        return response == null ? DevApi.failure(DevApi.UNKNOWN) : response;
    }

    private static JsonObject run(String path, Map<String, String> query, String body) throws Exception {
        return switch (path) {
            case "/status" -> DevTools.status();
            case "/window" -> DevInstance.window(query);
            case "/quit" -> DevInstance.quit();
            case "/reload" -> DevTools.reload(query.get("worktree"), Boolean.parseBoolean(query.get("full")));
            case "/command" -> DevTools.command(body.isBlank() ? query.getOrDefault("command", "") : body);
            case "/screenshot" ->
                DevTools.screenshot(
                        !"false".equals(query.get("hud")),
                        Integer.parseInt(query.getOrDefault("frames", "1")),
                        Integer.parseInt(query.getOrDefault("intervalMs", "100")),
                        query.get("video"),
                        query.get("keep"),
                        Boolean.parseBoolean(query.get("audio")));
            case "/audio" -> DevAudio.audio(query);
            case "/sounds" ->
                DevSounds.sounds(Long.parseLong(query.getOrDefault("sinceMs", "3000")), query.get("filter"));
            case "/find" -> DevInspect.find(query);
            case "/camera" -> DevCamera.camera(query);
            case "/look" -> DevInput.look(query);
            case "/perf" ->
                DevPerf.perf(
                        Double.parseDouble(query.getOrDefault("seconds", "5")),
                        query.get("namespace"),
                        query.get("fps"));
            case "/blockentity" -> DevInspect.blockEntity(query);
            case "/entities" -> DevScreens.entities(query);
            case "/screen" -> DevScreens.screen();
            case "/close" -> DevScreens.close();
            case "/chat" -> DevScreens.chat(Integer.parseInt(query.getOrDefault("lines", "20")));
            case "/options" -> DevOptions.options(query);
            case "/use" -> DevTools.interact(true, query);
            case "/attack" -> DevTools.interact(false, query);
            case "/key" -> DevInput.key(query, body);
            case "/type" -> DevInput.type(body.isEmpty() ? query.getOrDefault("text", "") : body);
            case "/click" -> DevInput.click(query);
            case "/mouse" -> DevInput.mouse(query);
            case "/scroll" -> DevInput.scroll(query);
            case "/release" -> DevInput.release();
            case "/drop" -> DevInput.drop(body.isBlank() ? query.getOrDefault("path", "") : body);
            case "/fixture" -> DevFixtures.fixture(query);
            case "/player" -> {
                var server = Minecraft.getInstance().getSingleplayerServer();
                yield server == null
                        ? DevApi.failure(
                                "Not in a single-player world; a dedicated server's console API has /player too.")
                        : DevPlayers.player(server, query);
            }
            case "/save" -> DevWorld.save();
            case "/connect" -> DevWorld.connect(query);
            case "/disconnect" -> DevWorld.disconnect();
            default -> null;
        };
    }
}
