package com.zmm2025.createdisplayworks.dev.tools;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

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
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            response = switch(exchange.getRequestURI().getPath()) {
                case "/status" -> DevTools.status();
                case "/window" -> DevInstance.window(!"false".equals(query.get("show")));
                case "/reload" -> DevTools.reload(query.get("worktree"), Boolean.parseBoolean(query.get("full")));
                case "/command" -> DevTools.command(body.isBlank() ? query.getOrDefault("command", "") : body);
                case "/screenshot" -> DevTools.screenshot(!"false".equals(query.get("hud")),
                    Integer.parseInt(query.getOrDefault("frames", "1")), Integer.parseInt(query.getOrDefault("intervalMs", "100")), query.get("video"));
                case "/sounds" -> DevSounds.sounds(Long.parseLong(query.getOrDefault("sinceMs", "3000")), query.get("filter"));
                case "/find" -> DevInspect.find(query);
                case "/camera" -> DevCamera.camera(query);
                case "/perf" -> DevPerf.perf(Double.parseDouble(query.getOrDefault("seconds", "5")), query.get("namespace"));
                case "/blockentity" -> DevInspect.blockEntity(query);
                case "/use" -> DevTools.interact(true, query);
                case "/attack" -> DevTools.interact(false, query);
                case "/key" -> DevTools.key(query.getOrDefault("key", body.strip()));
                case "/type" -> DevTools.type(body.isEmpty() ? query.getOrDefault("text", "") : body);
                case "/click" -> DevTools.click(Double.parseDouble(query.get("x")), Double.parseDouble(query.get("y")), Integer.parseInt(query.getOrDefault("button", "0")));
                case "/mouse" -> DevTools.click(Double.parseDouble(query.get("x")), Double.parseDouble(query.get("y")), null);
                default -> { code = 404; yield DevTools.failure("Unknown endpoint; see DEVELOPMENT.md."); }
            };
        } catch(Exception e) {
            code = 500;
            response = DevTools.failure(e.toString());
        }
        byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try(OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
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
