package com.zmm2025.createdisplayworks.dev.tools;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.zmm2025.createdisplayworks.CreateDisplayWorks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Measures rendering cost over a few seconds: frame times, and the CPU time this mod's block entity renderers spend
 * per frame, by wrapping them for the duration of the measurement. Also reports server tick times.
 */
final class DevPerf {
    private DevPerf() {}

    private static volatile Session session;

    private static final class Session {
        final long end;
        final List<Long> frames = new ArrayList<>();
        final Map<String, Stats> types = new TreeMap<>();
        final CompletableFuture<JsonObject> result = new CompletableFuture<>();
        Map<BlockEntityType<?>, BlockEntityRenderer<?>> original, installed;
        long lastFrame;
        Session(long end) { this.end = end; }
    }

    private static final class Stats {
        long frameNanos;
        int frameCalls;
        final List<Long> nanosPerFrame = new ArrayList<>();
        long calls;
    }

    /** Wraps a renderer to time its {@code render} calls; everything else is passed through. */
    private record Timed<T extends BlockEntity>(BlockEntityRenderer<T> inner, Stats stats) implements BlockEntityRenderer<T> {
        @Override public void render(T be, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
            long start = System.nanoTime();
            try {
                inner.render(be, partialTick, pose, buffers, light, overlay);
            } finally {
                stats.frameNanos += System.nanoTime() - start;
                stats.frameCalls++;
            }
        }
        @Override public boolean shouldRenderOffScreen(T be) { return inner.shouldRenderOffScreen(be); }
        @Override public int getViewDistance() { return inner.getViewDistance(); }
        @Override public boolean shouldRender(T be, Vec3 camera) { return inner.shouldRender(be, camera); }
        @Override public AABB getRenderBoundingBox(T be) { return inner.getRenderBoundingBox(be); }
    }

    /** Measures for {@code seconds}; {@code namespace} picks whose block entity renderers to time (default: this mod). */
    static JsonObject perf(double seconds, String namespace) throws Exception {
        if(seconds < 0.5 || seconds > 60) return DevTools.failure("Measure for 0.5-60 seconds.");
        if(session != null) return DevTools.failure("A measurement is already running.");
        Minecraft minecraft = Minecraft.getInstance();
        if(minecraft.level == null) return DevTools.failure("Not in a world.");
        String mod = namespace == null || namespace.isBlank() ? CreateDisplayWorks.MOD_ID : namespace;
        Session measuring = new Session(System.nanoTime() + (long)(seconds * 1e9));
        minecraft.submit(() -> install(measuring, minecraft.getBlockEntityRenderDispatcher(), mod)).get(1, TimeUnit.MINUTES);
        session = measuring;
        try {
            return measuring.result.get((long)seconds + 30, TimeUnit.SECONDS);
        } finally {
            session = null;
            minecraft.submit(() -> restore(measuring, minecraft.getBlockEntityRenderDispatcher())).get(1, TimeUnit.MINUTES);
        }
    }

    /** Called after every rendered frame. */
    static void frameRendered() {
        Session measuring = session;
        if(measuring == null || measuring.result.isDone()) return;
        long now = System.nanoTime();
        if(measuring.lastFrame != 0) {
            measuring.frames.add(now - measuring.lastFrame);
            for(Stats stats : measuring.types.values()) {
                stats.nanosPerFrame.add(stats.frameNanos);
                stats.calls += stats.frameCalls;
            }
        }
        for(Stats stats : measuring.types.values()) { stats.frameNanos = 0; stats.frameCalls = 0; }
        measuring.lastFrame = now;
        if(now >= measuring.end) measuring.result.complete(report(measuring));
    }

    private static JsonObject report(Session measuring) {
        JsonObject response = DevTools.ok();
        int count = measuring.frames.size();
        response.addProperty("frames", count);
        long total = measuring.frames.stream().mapToLong(Long::longValue).sum();
        response.addProperty("fps", count == 0 ? 0 : DevTools.round(count / (total / 1e9)));
        response.add("frameMs", summary(measuring.frames));

        JsonObject renderers = new JsonObject();
        measuring.types.forEach((type, stats) -> {
            if(stats.calls == 0) return;
            JsonObject json = summary(stats.nanosPerFrame);
            json.addProperty("rendersPerFrame", DevTools.round((double)stats.calls / Math.max(1, stats.nanosPerFrame.size())));
            renderers.add(type, json);
        });
        response.add("blockEntityRenderMsPerFrame", renderers);

        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if(server != null) {
            JsonObject tick = new JsonObject();
            tick.addProperty("avg", DevTools.round(server.getAverageTickTimeNanos() / 1e6));
            tick.addProperty("max", DevTools.round(Arrays.stream(server.getTickTimesNanos()).max().orElse(0) / 1e6));
            response.add("serverTickMsLast100Ticks", tick);
        }
        Runtime runtime = Runtime.getRuntime();
        response.addProperty("heapUsedMb", (runtime.totalMemory() - runtime.freeMemory()) >> 20);
        response.addProperty("heapMaxMb", runtime.maxMemory() >> 20);
        return response;
    }

    private static JsonObject summary(List<Long> nanos) {
        JsonObject json = new JsonObject();
        if(nanos.isEmpty()) return json;
        long[] sorted = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
        json.addProperty("avg", DevTools.round(Arrays.stream(sorted).average().orElse(0) / 1e6));
        json.addProperty("p50", DevTools.round(sorted[sorted.length / 2] / 1e6));
        json.addProperty("p95", DevTools.round(sorted[Math.min(sorted.length - 1, (int)(sorted.length * .95))] / 1e6));
        json.addProperty("p99", DevTools.round(sorted[Math.min(sorted.length - 1, (int)(sorted.length * .99))] / 1e6));
        json.addProperty("max", DevTools.round(sorted[sorted.length - 1] / 1e6));
        return json;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Void install(Session measuring, BlockEntityRenderDispatcher dispatcher, String namespace) {
        Field field = renderersField();
        try {
            measuring.original = (Map<BlockEntityType<?>, BlockEntityRenderer<?>>)field.get(dispatcher);
            Map<BlockEntityType<?>, BlockEntityRenderer<?>> timed = new HashMap<>(measuring.original);
            timed.replaceAll((type, renderer) -> {
                var id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
                if(id == null || !id.getNamespace().equals(namespace)) return renderer;
                Stats stats = new Stats();
                measuring.types.put(id.toString(), stats);
                return new Timed(renderer, stats);
            });
            measuring.installed = timed;
            field.set(dispatcher, timed);
        } catch(IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        return null;
    }

    /** Puts the original renderers back, unless a resource reload has already replaced them. */
    private static Void restore(Session measuring, BlockEntityRenderDispatcher dispatcher) {
        Field field = renderersField();
        try {
            if(field.get(dispatcher) == measuring.installed) field.set(dispatcher, measuring.original);
        } catch(IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        return null;
    }

    private static Field renderersField() {
        try {
            Field field = BlockEntityRenderDispatcher.class.getDeclaredField("renderers");
            field.setAccessible(true);
            return field;
        } catch(NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }
}
