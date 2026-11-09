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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;

/**
 * What the dev client's control API and the test dedicated server's console API share: a localhost-only JSON server,
 * and helpers for reading requests and writing answers. The dedicated server loads this class, and refuses to load
 * client classes, so it must stay clear of them.
 */
final class DevApi {
    static final String UNKNOWN = "Unknown endpoint; see DEVELOPMENT.md.";

    private DevApi() {}

    /** Answers a request with the JSON an endpoint returns, or null for an unknown path. */
    interface Endpoints {
        JsonObject run(String path, Map<String, String> query, String body) throws Exception;
    }

    /** Starts an API on {@code port}, or on a free port for 0, that answers one request at a time. */
    static HttpServer serve(int port, String threadName, Endpoints endpoints) throws IOException {
        HttpServer server =
                HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port), 0);
        server.setExecutor(Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, threadName);
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/", exchange -> handle(exchange, endpoints));
        server.start();
        return server;
    }

    /**
     * Whether a request comes from a local tool such as curl or Gradle, rather than from a web page. Browsers send an
     * {@code Origin} header with every POST, and a page that rebinds its own domain to 127.0.0.1 still sends its own
     * name as the {@code Host}. Without this check, any open web page could run commands in the game.
     */
    private static boolean fromLocalTool(HttpExchange exchange) {
        if (exchange.getRequestHeaders().containsKey("Origin")) return false;
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null) return true;
        String name = host.startsWith("[") ? host.substring(0, host.indexOf(']') + 1) : host.split(":", 2)[0];
        return name.equals("127.0.0.1") || name.equalsIgnoreCase("localhost") || name.equals("[::1]");
    }

    private static void handle(HttpExchange exchange, Endpoints endpoints) throws IOException {
        if (!fromLocalTool(exchange)) {
            send(exchange, 403, failure("Only local tools may use this API, not web pages."));
            return;
        }
        JsonObject response;
        int code = 200;
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            response = endpoints.run(
                    exchange.getRequestURI().getPath(),
                    query(exchange.getRequestURI().getRawQuery()),
                    body);
            if (response == null) {
                code = 404;
                response = failure(UNKNOWN);
            }
        } catch (Exception e) {
            code = 500;
            response = failure(e.toString());
        }
        send(exchange, code, response);
    }

    private static void send(HttpExchange exchange, int code, JsonObject response) throws IOException {
        byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    static Map<String, String> query(String raw) {
        Map<String, String> query = new LinkedHashMap<>();
        if (raw == null) return query;
        for (String pair : raw.split("&")) {
            int split = pair.indexOf('=');
            String key = split < 0 ? pair : pair.substring(0, split),
                    value = split < 0 ? "" : pair.substring(split + 1);
            query.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return query;
    }

    /** The block at {@code x}, {@code y} and {@code z}. */
    static BlockPos blockPos(Map<String, String> query) {
        return BlockPos.containing(point(query));
    }

    /** The point at {@code x}, {@code y} and {@code z}: a whole number is the middle of a block, a decimal exact. */
    static Vec3 point(Map<String, String> query) {
        double[] point = new double[3];
        for (int i = 0; i < 3; i++) {
            String text = query.get("xyz".substring(i, i + 1));
            if (text == null) throw new IllegalArgumentException("Pass x, y and z.");
            point[i] = text.contains(".") ? Double.parseDouble(text) : Integer.parseInt(text) + 0.5;
        }
        return new Vec3(point[0], point[1], point[2]);
    }

    static JsonObject ok() {
        JsonObject json = new JsonObject();
        json.addProperty("ok", true);
        return json;
    }

    static JsonObject failure(String message) {
        JsonObject json = new JsonObject();
        json.addProperty("ok", false);
        json.addProperty("status", "error");
        json.addProperty("message", message);
        return json;
    }

    static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    /** A position as {@code "x y z"}, rounded to hundredths. */
    static String position(Position pos) {
        return round(pos.x()) + " " + round(pos.y()) + " " + round(pos.z());
    }

    static String position(Vec3i pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
