package com.zmm2025.createdisplayworks.dev.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Reads world state for agents: finds blocks near a point, and dumps a block entity's fields on the client and server
 * by reflection, so any block's state can be checked without adding debug logging. A client dump can also be sampled
 * on every rendered frame to follow animations.
 */
final class DevInspect {
    private DevInspect() {}

    private static final int MAX_ITEMS = 256, MAX_TEXT = 300;
    private static volatile PendingSample sample;

    // ---- /find ----

    /** Lists blocks matching an id, {@code #tag} or namespace within a cube around a point, nearest first. */
    static JsonObject find(Map<String, String> query) throws Exception {
        String block = query.get("block"), namespace = query.get("namespace");
        if((block == null) == (namespace == null)) return DevTools.failure("Pass block=<id or #tag> or namespace=<mod id>.");
        int radius = Integer.parseInt(query.getOrDefault("radius", "16")), limit = Integer.parseInt(query.getOrDefault("limit", "100"));
        if(radius < 0 || radius > 64) return DevTools.failure("Use a radius of 0-64.");
        Predicate<BlockState> test;
        if(namespace != null) test = state -> BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals(namespace);
        else if(block.startsWith("#")) {
            TagKey<Block> tag = TagKey.create(Registries.BLOCK, ResourceLocation.parse(block.substring(1)));
            test = state -> state.is(tag);
        } else {
            ResourceLocation id = ResourceLocation.parse(block);
            if(!BuiltInRegistries.BLOCK.containsKey(id)) return DevTools.failure("Unknown block " + block + ".");
            Block wanted = BuiltInRegistries.BLOCK.get(id);
            test = state -> state.is(wanted);
        }
        return onServer((server, level, player) -> {
            BlockPos center = query.containsKey("x") ? position(query) : player;
            List<BlockPos> found = new ArrayList<>();
            for(BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius)))
                if(level.isLoaded(pos) && test.test(level.getBlockState(pos))) found.add(pos.immutable());
            found.sort(Comparator.comparingDouble(pos -> pos.distSqr(center)));
            JsonArray blocks = new JsonArray();
            for(BlockPos pos : found.subList(0, Math.min(limit, found.size()))) {
                BlockState state = level.getBlockState(pos);
                JsonObject json = new JsonObject();
                json.addProperty("pos", text(pos));
                json.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                if(!state.getValues().isEmpty()) json.addProperty("state", state.toString().replaceFirst("^[^\\[]*", ""));
                json.addProperty("distance", DevTools.round(Math.sqrt(pos.distSqr(center))));
                blocks.add(json);
            }
            JsonObject response = DevTools.ok();
            response.addProperty("center", text(center));
            response.addProperty("count", found.size());
            response.add("blocks", blocks);
            return response;
        });
    }

    // ---- /blockentity ----

    /**
     * Dumps the block entity at {@code x y z}. {@code side} is client, server or both; {@code fields} picks fields by
     * name or dotted path; without them only this mod's own fields are listed, unless {@code all=true} adds the ones
     * inherited from Create and vanilla. {@code depth} limits how far this mod's own objects are expanded. With {@code frames > 1}
     * the client side is sampled on rendered frames {@code intervalMs} apart, with the game time each frame drew.
     */
    static JsonObject blockEntity(Map<String, String> query) throws Exception {
        BlockPos pos = position(query);
        String side = query.getOrDefault("side", "both");
        List<String> fields = query.containsKey("fields") ? Arrays.stream(query.get("fields").split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList() : List.of();
        int depth = Integer.parseInt(query.getOrDefault("depth", "2"));
        boolean all = Boolean.parseBoolean(query.get("all"));
        int frames = Integer.parseInt(query.getOrDefault("frames", "1")), intervalMs = Integer.parseInt(query.getOrDefault("intervalMs", "0"));
        if(frames < 1 || frames > 600 || intervalMs < 0 || intervalMs > 10_000) return DevTools.failure("Use 1-600 frames and an interval of 0-10000 ms.");
        Minecraft minecraft = Minecraft.getInstance();
        JsonObject response = DevTools.ok();
        response.addProperty("pos", text(pos));

        if(!side.equals("client")) {
            JsonObject server = onServer((s, level, player) -> {
                BlockEntity be = level.getBlockEntity(pos);
                JsonObject json = new JsonObject();
                json.addProperty("gameTime", level.getGameTime());
                if(be == null) json.addProperty("blockEntity", (String)null);
                else {
                    json.addProperty("type", be.getClass().getName());
                    json.add("fields", dump(be, fields, depth, all));
                }
                return json;
            });
            response.add("server", server);
        }
        if(side.equals("server")) return response;

        if(frames == 1) {
            response.add("client", minecraft.submit(() -> clientSample(pos, fields, depth, all, null)).get(1, TimeUnit.MINUTES));
            return response;
        }
        PendingSample pending = new PendingSample(pos, fields, depth, all, frames, intervalMs * 1_000_000L);
        sample = pending;
        JsonArray samples = pending.result.get(30 + (long)frames * intervalMs / 1000, TimeUnit.SECONDS);
        response.add("client", samples);
        return response;
    }

    /** Takes due samples; called after every rendered frame. */
    static void frameRendered() {
        PendingSample pending = sample;
        if(pending == null) return;
        long now = System.nanoTime();
        if(pending.samples.isEmpty()) pending.start = now;
        else if(now < pending.start + pending.samples.size() * pending.intervalNanos) return;
        try {
            pending.samples.add(clientSample(pending.pos, pending.fields, pending.depth, pending.all, (now - pending.start) / 1_000_000.0));
        } catch(RuntimeException e) {
            sample = null;
            pending.result.completeExceptionally(e);
            return;
        }
        if(pending.samples.size() < pending.frames) return;
        sample = null;
        pending.result.complete(pending.samples);
    }

    /** {@code timeMs} is set for a sample in a sequence, which leaves out the class name each frame would repeat. */
    private static JsonObject clientSample(BlockPos pos, List<String> fields, int depth, boolean all, Double timeMs) {
        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if(level == null) throw new IllegalStateException("Not in a world.");
        JsonObject json = new JsonObject();
        if(timeMs != null) json.addProperty("tMs", Math.round(timeMs * 10) / 10.0);
        float partial = minecraft.getTimer().getGameTimeDeltaPartialTick(false);
        json.addProperty("gameTime", level.getGameTime());
        json.addProperty("partialTick", Math.round(partial * 1000) / 1000.0);
        BlockEntity be = level.getBlockEntity(pos);
        if(be == null) json.addProperty("blockEntity", (String)null);
        else {
            if(timeMs == null) json.addProperty("type", be.getClass().getName());
            json.add("fields", dump(be, fields, depth, all));
        }
        return json;
    }

    // ---- Reflection dump ----

    private static JsonObject dump(Object be, List<String> paths, int depth, boolean all) {
        JsonObject json = new JsonObject();
        if(paths.isEmpty()) {
            for(Field field : fields(be.getClass(), BlockEntity.class))
                if(all || ownClass(field.getDeclaringClass())) json.add(field.getName(), read(field, be, depth));
            return json;
        }
        for(String path : paths) {
            Object value = be;
            JsonElement result = null;
            for(String name : path.split("\\.")) {
                Field field = value == null ? null : find(value.getClass(), name);
                if(field == null) { result = new JsonPrimitive("<no field " + name + ">"); break; }
                try { value = field.get(value); } catch(ReflectiveOperationException | RuntimeException e) { result = new JsonPrimitive("<inaccessible>"); break; }
            }
            json.add(path, result != null ? result : value(value, depth));
        }
        return json;
    }

    private static JsonElement read(Field field, Object owner, int depth) {
        try { return value(field.get(owner), depth); } catch(ReflectiveOperationException | RuntimeException e) { return new JsonPrimitive("<inaccessible>"); }
    }

    private static JsonElement value(Object value, int depth) {
        if(value == null) return JsonNull.INSTANCE;
        if(value instanceof Boolean b) return new JsonPrimitive(b);
        if(value instanceof Number n) return new JsonPrimitive(n);
        if(value instanceof Character || value instanceof Enum<?> || value instanceof ResourceLocation) return new JsonPrimitive(value.toString());
        if(value instanceof String s) return new JsonPrimitive(s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) + "..." : s);
        if(value instanceof Vec3i pos) return new JsonPrimitive(text(pos));
        if(value instanceof BlockState state) return new JsonPrimitive(state.toString());
        if(value.getClass().isArray()) {
            JsonArray array = new JsonArray();
            int length = Array.getLength(value);
            for(int i = 0; i < Math.min(length, MAX_ITEMS); i++) array.add(value(Array.get(value, i), depth - 1));
            if(length > MAX_ITEMS) array.add("... " + (length - MAX_ITEMS) + " more");
            return array;
        }
        if(value instanceof Collection<?> collection) {
            JsonArray array = new JsonArray();
            if(depth <= 0) return new JsonPrimitive(collection.getClass().getSimpleName() + " of " + collection.size());
            collection.stream().limit(MAX_ITEMS).forEach(item -> array.add(value(item, depth - 1)));
            if(collection.size() > MAX_ITEMS) array.add("... " + (collection.size() - MAX_ITEMS) + " more");
            return array;
        }
        if(value instanceof Map<?, ?> map) {
            if(depth <= 0) return new JsonPrimitive(map.getClass().getSimpleName() + " of " + map.size());
            JsonObject json = new JsonObject();
            map.entrySet().stream().limit(MAX_ITEMS).forEach(entry -> json.add(String.valueOf(entry.getKey()), value(entry.getValue(), depth - 1)));
            return json;
        }
        if(depth > 0 && (ownClass(value.getClass()) || value.getClass().isRecord())) {
            JsonObject json = new JsonObject();
            for(Field field : fields(value.getClass(), Object.class)) json.add(field.getName(), read(field, value, depth - 1));
            return json;
        }
        String text = value.toString();
        return new JsonPrimitive(text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "..." : text);
    }

    /** Instance fields from {@code type} up to, not including, {@code stop}, superclass fields first. */
    private static List<Field> fields(Class<?> type, Class<?> stop) {
        Deque<Class<?>> chain = new ArrayDeque<>();
        for(Class<?> c = type; c != null && c != stop && c != Object.class; c = c.getSuperclass()) chain.push(c);
        List<Field> fields = new ArrayList<>();
        for(Class<?> c : chain) for(Field field : c.getDeclaredFields()) {
            if(Modifier.isStatic(field.getModifiers()) || field.isSynthetic() || Level.class.isAssignableFrom(field.getType())) continue;
            try { field.setAccessible(true); } catch(RuntimeException e) { continue; }
            fields.add(field);
        }
        return fields;
    }

    private static boolean ownClass(Class<?> type) {
        return type.getName().startsWith("com.zmm2025.");
    }

    private static Field find(Class<?> type, String name) {
        for(Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch(NoSuchFieldException | RuntimeException ignored) {}
        }
        return null;
    }

    // ---- Helpers ----

    private interface ServerAction { JsonObject run(MinecraftServer server, ServerLevel level, BlockPos player); }

    /** Runs on the server thread in the player's dimension. */
    private static JsonObject onServer(ServerAction action) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer server = minecraft.getSingleplayerServer();
        if(server == null || minecraft.player == null) return DevTools.failure("Not in a single-player world.");
        var dimension = minecraft.player.level().dimension();
        BlockPos player = minecraft.player.blockPosition();
        return server.submit(() -> action.run(server, server.getLevel(dimension), player)).get(1, TimeUnit.MINUTES);
    }

    private static BlockPos position(Map<String, String> query) {
        if(!query.containsKey("x") || !query.containsKey("y") || !query.containsKey("z")) throw new IllegalArgumentException("Pass x, y and z.");
        return new BlockPos(Integer.parseInt(query.get("x")), Integer.parseInt(query.get("y")), Integer.parseInt(query.get("z")));
    }

    private static String text(Vec3i pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static final class PendingSample {
        final BlockPos pos;
        final List<String> fields;
        final int depth, frames;
        final boolean all;
        final long intervalNanos;
        final JsonArray samples = new JsonArray();
        final CompletableFuture<JsonArray> result = new CompletableFuture<>();
        long start;
        PendingSample(BlockPos pos, List<String> fields, int depth, boolean all, int frames, long intervalNanos) {
            this.pos = pos; this.fields = fields; this.depth = depth; this.all = all; this.frames = frames; this.intervalNanos = intervalNanos;
        }
    }
}
