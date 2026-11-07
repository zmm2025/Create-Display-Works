package com.zmm2025.createdisplayworks.dev.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a test setup from a recipe in {@code src/dev/resources/dev-fixtures}: a list of {@code /command} batch steps
 * with coordinates relative to an origin, written as {@code {x+3}}. Each fixture gets its own spot, far from the world's
 * builds, unless the request names one. The recipe's {@code parts} and {@code clear} come back with real coordinates.
 */
final class DevFixtures {
    private static final List<String> NAMES = List.of("board", "contraption", "train");
    /** Where fixtures start, and how far apart they are along X. */
    private static final int FIRST = 4096, SPACING = 128;
    private static final Pattern COORDINATE = Pattern.compile("\\{([xyz])(?:([+-])(\\d+(?:\\.\\d+)?))?}");

    private DevFixtures() {}

    static JsonObject fixture(Map<String, String> query) throws Exception {
        String name = query.get("name");
        if(!NAMES.contains(name)) return DevTools.failure("Use name=" + String.join(", ", NAMES) + ".");
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer server = minecraft.getSingleplayerServer();
        if(server == null || minecraft.player == null) return DevTools.failure("Fixtures need a single-player world.");
        JsonObject recipe = recipe(name);
        boolean placed = query.containsKey("x");
        int x = placed ? Integer.parseInt(query.get("x")) : nextSpot(server);
        int z = placed ? Integer.parseInt(query.get("z")) : FIRST;
        // The ground's height is only known once the chunk has loaded.
        JsonObject arrived = DevTools.command("[\"tp @s " + x + " 80 " + z + "\",{\"waitUntil\":\"execute if loaded " + x + " 0 " + z + "\",\"timeoutTicks\":400}]");
        if(!arrived.get("ok").getAsBoolean()) return arrived;
        var dimension = minecraft.player.level().dimension();
        int y = query.containsKey("y") ? Integer.parseInt(query.get("y"))
            : server.submit(() -> server.getLevel(dimension).getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)).get(1, TimeUnit.MINUTES);

        JsonObject batch = DevTools.command(place(recipe.get("steps"), x, y, z).toString());
        JsonObject response = new JsonObject();
        response.addProperty("ok", batch.get("ok").getAsBoolean());
        response.addProperty("name", name);
        response.addProperty("origin", x + " " + y + " " + z);
        response.add("parts", place(recipe.get("parts"), x, y, z));
        response.add("clear", place(recipe.get("clear"), x, y, z));
        if(Boolean.parseBoolean(query.get("verbose"))) response.add("results", batch.get("results"));
        else response.add("failed", failures(batch));
        return response;
    }

    private static JsonObject recipe(String name) throws IOException {
        try(InputStream in = DevFixtures.class.getResourceAsStream("/dev-fixtures/" + name + ".json")) {
            if(in == null) throw new IOException("No recipe for " + name);
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    /** Keeps this world's fixtures apart. The count lives in the world's folder, so a fresh world starts over. */
    private static int nextSpot(MinecraftServer server) throws IOException {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("dev-fixtures.txt");
        int count = Files.exists(file) ? Integer.parseInt(Files.readString(file).strip()) : 0;
        Files.writeString(file, Integer.toString(count + 1));
        return FIRST + count * SPACING;
    }

    /** Replaces coordinates in every string of a recipe's JSON. */
    private static JsonElement place(JsonElement element, int x, int y, int z) {
        if(element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) return new JsonPrimitive(place(element.getAsString(), x, y, z));
        if(element.isJsonArray()) {
            JsonArray array = new JsonArray();
            element.getAsJsonArray().forEach(item -> array.add(place(item, x, y, z)));
            return array;
        }
        if(element.isJsonObject()) {
            JsonObject object = new JsonObject();
            element.getAsJsonObject().entrySet().forEach(entry -> object.add(entry.getKey(), place(entry.getValue(), x, y, z)));
            return object;
        }
        return element;
    }

    private static String place(String text, int x, int y, int z) {
        Matcher matcher = COORDINATE.matcher(text);
        StringBuilder placed = new StringBuilder();
        while(matcher.find()) {
            int base = switch(matcher.group(1)) { case "x" -> x; case "y" -> y; default -> z; };
            String offset = matcher.group(3);
            String value;
            if(offset == null) value = Integer.toString(base);
            else {
                double sum = base + (matcher.group(2).equals("-") ? -1 : 1) * Double.parseDouble(offset);
                value = offset.contains(".") ? Double.toString(sum) : Integer.toString((int)sum);
            }
            matcher.appendReplacement(placed, value);
        }
        return matcher.appendTail(placed).toString();
    }

    private static JsonArray failures(JsonObject batch) {
        JsonArray failed = new JsonArray();
        batch.getAsJsonArray("results").forEach(step -> { if(!step.getAsJsonObject().get("ok").getAsBoolean()) failed.add(step); });
        return failed;
    }
}
