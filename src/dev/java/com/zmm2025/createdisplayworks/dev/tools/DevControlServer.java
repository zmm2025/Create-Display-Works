package com.zmm2025.createdisplayworks.dev.tools;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/** Localhost-only HTTP API for driving the dev client. Requests run one at a time. Endpoints are listed in DEVELOPMENT.md. */
final class DevControlServer {
    private static final String UNKNOWN = "Unknown endpoint; see DEVELOPMENT.md.";

    private DevControlServer() {}

    /** Starts the API on {@code port}, or on a free port for 0, and returns the port it listens on. */
    static int start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port), 0);
        server.setExecutor(Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "Display Works Dev Control");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/", DevControlServer::handle);
        server.start();
        return server.getAddress().getPort();
    }

    private static void handle(HttpExchange exchange) throws IOException {
        JsonObject response;
        int code = 200;
        DevInstance.touch();
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            response = dispatch(exchange.getRequestURI().getPath(), query(exchange.getRequestURI().getRawQuery()), body);
            if(response == null) {
                code = 404;
                response = DevTools.failure(UNKNOWN);
            }
        } catch(Exception e) {
            code = 500;
            response = DevTools.failure(e.toString());
        }
        byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try(OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }

    /** Runs an endpoint from a batch step, given its path and query the way a request gives them. */
    static JsonObject call(String target, String body) throws Exception {
        int split = target.indexOf('?');
        JsonObject response = dispatch(split < 0 ? target : target.substring(0, split), query(split < 0 ? null : target.substring(split + 1)), body);
        return response == null ? DevTools.failure(UNKNOWN) : response;
    }

    private static JsonObject dispatch(String path, Map<String, String> query, String body) throws Exception {
        return switch(path) {
            case "/status" -> DevTools.status();
            case "/window" -> DevInstance.window(query);
            case "/quit" -> DevInstance.quit();
            case "/reload" -> DevTools.reload(query.get("worktree"), Boolean.parseBoolean(query.get("full")));
            case "/command" -> DevTools.command(body.isBlank() ? query.getOrDefault("command", "") : body);
            case "/screenshot" -> DevTools.screenshot(!"false".equals(query.get("hud")), Integer.parseInt(query.getOrDefault("frames", "1")),
                Integer.parseInt(query.getOrDefault("intervalMs", "100")), query.get("video"), query.get("keep"));
            case "/sounds" -> DevSounds.sounds(Long.parseLong(query.getOrDefault("sinceMs", "3000")), query.get("filter"));
            case "/find" -> DevInspect.find(query);
            case "/camera" -> DevCamera.camera(query);
            case "/look" -> DevInput.look(query);
            case "/perf" -> DevPerf.perf(Double.parseDouble(query.getOrDefault("seconds", "5")), query.get("namespace"), query.get("fps"));
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
                yield server == null ? DevTools.failure("Not in a single-player world; a dedicated server's console API has /player too.") : DevPlayers.player(server, query);
            }
            case "/save" -> DevWorld.save();
            case "/connect" -> DevWorld.connect(query);
            case "/disconnect" -> DevWorld.disconnect();
            default -> null;
        };
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> query = new LinkedHashMap<>();
        if(raw == null) return query;
        for(String pair : raw.split("&")) {
            int split = pair.indexOf('=');
            String key = split < 0 ? pair : pair.substring(0, split), value = split < 0 ? "" : pair.substring(split + 1);
            query.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return query;
    }
}
