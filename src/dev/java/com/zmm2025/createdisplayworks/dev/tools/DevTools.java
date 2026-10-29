package com.zmm2025.createdisplayworks.dev.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import com.zmm2025.createdisplayworks.CreateDisplayWorks;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.instrument.Instrumentation;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Development-only tooling for the {@code runDevClient} run: auto-joins the shared test world, hot-reloads code,
 * assets and data, and lets agents drive the dev player over a localhost-only HTTP API (see DEVELOPMENT.md).
 * Inert unless the run sets {@code csol.dev.tools}.
 */
@EventBusSubscriber(modid=CreateDisplayWorks.MOD_ID, value=Dist.CLIENT)
public final class DevTools {
    static final boolean ENABLED = Boolean.getBoolean("csol.dev.tools");
    private static final Logger LOGGER = LoggerFactory.getLogger("CSoL Dev Tools");
    private static final String WORKTREE = System.getProperty("csol.dev.worktree", "");
    /** Created as soon as the mod's event subscribers load, so the baseline matches the classes the game loaded. */
    private static final HotReloader RELOADER = ENABLED ? new HotReloader(paths("csol.dev.classRoots"), paths("csol.dev.resourceRoots")) : null;
    private static final String RELOADED = "Your Minecraft client has been reloaded.";
    private static volatile PendingScreenshot screenshot;
    /** Encodes and saves screenshot frames off the render thread, so a sequence doesn't stall the frames it captures. */
    private static final ExecutorService SCREENSHOT_WRITERS = Executors.newFixedThreadPool(Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 6), task -> {
        Thread thread = new Thread(task, "CSoL Screenshot Writer");
        thread.setDaemon(true);
        return thread;
    });
    /** Frames waiting to be saved before capture skips frames rather than buffer more full-size images. */
    private static final int MAX_UNSAVED_FRAMES = 32;
    /** Frames shown on a contact sheet; longer sequences show evenly spread frames. */
    private static final int SHEET_FRAMES = 36;
    /** Which worktree and branch this client runs; shown in the window title. */
    private static final String LABEL = System.getProperty("csol.dev.label", "dev client");
    private static FileChannel clientLock;
    private static boolean titled;
    private static Object titledScreen, titledLevel;

    private DevTools() {}

    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        if(!ENABLED) return;
        event.enqueueWork(() -> {
            // Keep the game running (and controllable) while its window is in the background.
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.options.pauseOnLostFocus = false;
            minecraft.options.onboardAccessibility = false;
            minecraft.getTutorial().setStep(TutorialSteps.NONE);
            Path gameDirectory = minecraft.gameDirectory.toPath().toAbsolutePath().normalize();
            try {
                // Held until the game exits; runDevClient checks it so only one dev client runs at a time.
                clientLock = FileChannel.open(gameDirectory.resolve("dev-client.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                if(clientLock.tryLock() == null) LOGGER.warn("Another dev client is already using {}", gameDirectory);
                // Worktree games pass port 0 and get a free one; DevInstance records it for Gradle and agents.
                int port = DevControlServer.start(Integer.getInteger("csol.dev.controlPort", 25595));
                DevInstance.started(port, WORKTREE);
                DevSounds.track();
                LOGGER.info("Dev control API listening on http://127.0.0.1:{} for {}", port, LABEL);
            } catch(IOException e) {
                LOGGER.error("Dev control API could not start", e);
            }
        });
    }

    /** Vanilla rewrites the window title whenever the screen or level changes, so relabel it after each change. */
    @SubscribeEvent public static void clientTick(ClientTickEvent.Post event) {
        if(!ENABLED) return;
        DevInstance.tick();
        Minecraft minecraft = Minecraft.getInstance();
        if(titled && minecraft.screen == titledScreen && minecraft.level == titledLevel) return;
        titled = true;
        titledScreen = minecraft.screen;
        titledLevel = minecraft.level;
        minecraft.getWindow().setTitle(LABEL + " | Minecraft " + SharedConstants.getCurrentVersion().getName()
            + (minecraft.getSingleplayerServer() != null ? " - Singleplayer" : ""));
    }

    @SubscribeEvent public static void loggedIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if(!ENABLED) return;
        Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[Dev] ").withStyle(ChatFormatting.DARK_AQUA)
            .append(Component.literal("Running " + LABEL).withStyle(ChatFormatting.GRAY)));
    }

    @SubscribeEvent public static void screenOpened(ScreenEvent.Opening event) {
        if(ENABLED && event.getNewScreen() instanceof TitleScreen) DevWorld.titleScreenOpened();
    }

    @SubscribeEvent public static void playerJoined(PlayerEvent.PlayerLoggedInEvent event) {
        if(ENABLED && event.getEntity() instanceof ServerPlayer player) DevWorld.playerJoined(player);
    }

    @SubscribeEvent public static void frameRendered(RenderFrameEvent.Post event) {
        if(!ENABLED) return;
        DevInspect.frameRendered();
        DevPerf.frameRendered();
        PendingScreenshot pending = screenshot;
        if(pending == null || pending.framesToWait-- > 0) return;
        long now = System.nanoTime();
        if(pending.times.isEmpty()) pending.start = now;
        else if(now < pending.start + pending.times.size() * pending.intervalNanos) return;
        if(pending.unsaved.get() >= MAX_UNSAVED_FRAMES) return;
        Minecraft minecraft = Minecraft.getInstance();
        NativeImage image;
        try {
            image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget());
        } catch(Exception e) {
            screenshot = null;
            minecraft.options.hideGui = pending.hideGuiBefore;
            pending.result.completeExceptionally(e);
            return;
        }
        Path file = pending.files.get(pending.times.size());
        pending.times.add((now - pending.start) / 1_000_000.0);
        pending.gameTimes.add(minecraft.level == null ? 0.0 : minecraft.level.getGameTime() + (double)minecraft.getTimer().getGameTimeDeltaPartialTick(false));
        pending.unsaved.incrementAndGet();
        pending.saves.add(CompletableFuture.runAsync(() -> {
            try(image) {
                Files.createDirectories(file.getParent());
                image.writeToFile(file);
            } catch(IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                pending.unsaved.decrementAndGet();
            }
        }, SCREENSHOT_WRITERS));
        if(pending.times.size() < pending.files.size()) return;
        screenshot = null;
        minecraft.options.hideGui = pending.hideGuiBefore;
        CompletableFuture.allOf(pending.saves.toArray(CompletableFuture[]::new)).whenComplete((done, error) -> {
            if(error != null) pending.result.completeExceptionally(error);
            else pending.result.complete(pending.files);
        });
    }

    // ---- Actions behind the control API. Each returns a JSON response body. ----

    static JsonObject reload(String worktree, boolean full) throws Exception {
        if(worktree != null && !samePath(worktree, WORKTREE))
            return failure("This game was launched from " + WORKTREE + ", not " + worktree + ". Close it and press Launch Minecraft in this worktree to try these changes.");
        Instrumentation instrumentation = instrumentation();
        if(instrumentation == null) return failure("The dev agent is not attached. Close Minecraft and press Launch Minecraft to try these changes.");
        HotReloader.Result result = RELOADER.reload(instrumentation, DevTools.class.getClassLoader());
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer server = minecraft.getSingleplayerServer();
        List<String> details = new ArrayList<>();
        if(!result.redefined().isEmpty()) details.add("Hot-swapped " + count(result.redefined().size(), "class") + ": " + simpleNames(result.redefined()));
        if(!result.notYetLoaded().isEmpty()) details.add(count(result.notYetLoaded().size(), "new or unused class") + " will load on first use: " + simpleNames(result.notYetLoaded()));
        boolean data = full || !result.changes().data().isEmpty(), assets = full || !result.changes().assets().isEmpty();
        if(data && server != null) {
            server.submit(() -> {
                server.getPackRepository().reload();
                return server.reloadResources(server.getPackRepository().getSelectedIds());
            }).get(1, TimeUnit.MINUTES).get(3, TimeUnit.MINUTES);
            details.add("Reloaded data packs (" + count(result.changes().data().size(), "changed file") + ")");
        } else if(data) details.add("Data changed; it will load when you join a world");
        if(assets) {
            minecraft.submit(minecraft::reloadResourcePacks).get(1, TimeUnit.MINUTES).get(3, TimeUnit.MINUTES);
            details.add("Reloaded assets (" + count(result.changes().assets().size(), "changed file") + ")");
        }
        result.restartReasons().forEach(reason -> details.add("Needs restart: " + reason));

        String status, message;
        if(!result.restartReasons().isEmpty()) {
            status = "restart-required";
            message = "Restart Minecraft to try these changes.";
        } else if(result.changes().isEmpty() && !full) {
            status = "no-changes";
            message = "No reload or restart needed.";
        } else {
            status = "reloaded";
            message = RELOADED;
        }
        minecraft.execute(() -> {
            ChatFormatting colour = status.equals("reloaded") ? ChatFormatting.GREEN : status.equals("no-changes") ? ChatFormatting.GRAY : ChatFormatting.GOLD;
            minecraft.gui.getChat().addMessage(Component.literal("[Dev] ").withStyle(ChatFormatting.DARK_AQUA).append(Component.literal(message).withStyle(colour)));
            for(String line : details) minecraft.gui.getChat().addMessage(Component.literal("  " + line).withStyle(ChatFormatting.GRAY));
        });
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("status", status);
        response.addProperty("message", message);
        response.add("details", strings(details));
        return response;
    }

    static JsonObject status() throws Exception {
        return onClient(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            JsonObject status = new JsonObject();
            status.addProperty("ok", true);
            status.addProperty("worktree", WORKTREE);
            status.addProperty("gameDirectory", minecraft.gameDirectory.toPath().toAbsolutePath().normalize().toString());
            MinecraftServer server = minecraft.getSingleplayerServer();
            status.addProperty("world", server == null ? null : server.getWorldData().getLevelName());
            status.addProperty("screen", minecraft.screen == null ? null : minecraft.screen.getClass().getName());
            status.addProperty("fps", minecraft.getFps());
            status.addProperty("hidden", DevInstance.hidden());
            Window window = minecraft.getWindow();
            JsonObject windowJson = new JsonObject();
            windowJson.addProperty("width", window.getWidth());
            windowJson.addProperty("height", window.getHeight());
            windowJson.addProperty("guiScale", window.getGuiScale());
            status.add("window", windowJson);
            if(minecraft.player != null) {
                JsonObject player = new JsonObject();
                player.addProperty("name", minecraft.player.getGameProfile().getName());
                player.addProperty("x", round(minecraft.player.getX()));
                player.addProperty("y", round(minecraft.player.getY()));
                player.addProperty("z", round(minecraft.player.getZ()));
                player.addProperty("yaw", round(minecraft.player.getYRot()));
                player.addProperty("pitch", round(minecraft.player.getXRot()));
                player.addProperty("dimension", minecraft.player.level().dimension().location().toString());
                player.addProperty("gameMode", minecraft.gameMode == null ? null : minecraft.gameMode.getPlayerMode().getName());
                player.addProperty("mainHand", BuiltInRegistries.ITEM.getKey(minecraft.player.getMainHandItem().getItem()) + " x" + minecraft.player.getMainHandItem().getCount());
                player.addProperty("selectedSlot", minecraft.player.getInventory().selected);
                status.add("player", player);
                status.add("target", describe(minecraft.hitResult));
            }
            return status;
        });
    }

    /**
     * Runs one command, or a JSON array of steps in order. A step is a command, {@code {"waitTicks": n}}, or
     * {@code {"waitUntil": "<command>", "timeoutTicks": n}}, which reruns the command each tick until it succeeds and
     * stops the batch if it never does. Consecutive commands run within one server task. A batch returns each step's
     * result and is {@code ok} only if all of them succeeded.
     */
    static JsonObject command(String body) throws Exception {
        boolean batch = body.strip().startsWith("[");
        List<JsonElement> steps = new ArrayList<>();
        if(batch) JsonParser.parseString(body).getAsJsonArray().forEach(steps::add);
        else steps.add(new JsonPrimitive(body));
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer server = minecraft.getSingleplayerServer();
        if(server == null || minecraft.player == null) return failure("Not in a single-player world.");
        UUID id = minecraft.player.getUUID();
        JsonArray results = new JsonArray();
        boolean ok = true;
        for(int i = 0; i < steps.size(); i++) {
            JsonElement step = steps.get(i);
            if(step.isJsonPrimitive()) {
                List<String> commands = new ArrayList<>();
                for(; i < steps.size() && steps.get(i).isJsonPrimitive(); i++) commands.add(steps.get(i).getAsString());
                i--;
                for(JsonObject result : runCommands(server, id, commands, batch)) {
                    ok &= result.get("ok").getAsBoolean();
                    results.add(result);
                }
                continue;
            }
            JsonObject wait = step.getAsJsonObject(), result = new JsonObject();
            if(wait.has("waitTicks")) {
                int ticks = wait.get("waitTicks").getAsInt();
                waitTicks(server, ticks);
                result.addProperty("waitTicks", ticks);
                result.addProperty("ok", true);
            } else if(wait.has("waitUntil")) {
                String command = wait.get("waitUntil").getAsString();
                int timeout = wait.has("timeoutTicks") ? wait.get("timeoutTicks").getAsInt() : 200, waited = 0;
                JsonObject attempt = runCommands(server, id, List.of(command), false).get(0);
                while(!attempt.get("ok").getAsBoolean() && waited < timeout) {
                    waitTicks(server, 1);
                    waited++;
                    attempt = runCommands(server, id, List.of(command), false).get(0);
                }
                result.addProperty("waitUntil", command);
                result.addProperty("ok", attempt.get("ok").getAsBoolean());
                result.addProperty("ticks", waited);
                result.add("output", attempt.get("output"));
            } else throw new IllegalArgumentException("Unknown step " + wait + "; use a command, waitTicks or waitUntil.");
            results.add(result);
            if(!result.get("ok").getAsBoolean()) {
                ok = false;
                result.addProperty("skippedSteps", steps.size() - i - 1);
                break;
            }
        }
        if(!batch) return results.get(0).getAsJsonObject();
        JsonObject response = new JsonObject();
        response.addProperty("ok", ok);
        response.add("results", results);
        return response;
    }

    private static List<JsonObject> runCommands(MinecraftServer server, UUID id, List<String> commands, boolean echo) throws Exception {
        return server.submit(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            List<JsonObject> done = new ArrayList<>();
            for(String command : commands) done.add(runCommand(server, player, command, echo));
            return done;
        }).get(1, TimeUnit.MINUTES);
    }

    /** Blocks until the server has run {@code ticks} more ticks. */
    private static void waitTicks(MinecraftServer server, int ticks) throws Exception {
        if(ticks < 0 || ticks > 1200) throw new IllegalArgumentException("Wait 0-1200 ticks.");
        int target = server.submit(server::getTickCount).get(1, TimeUnit.MINUTES) + ticks;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(5_000 + ticks * 250L);
        while(server.getTickCount() < target) {
            if(System.nanoTime() > deadline) throw new IllegalStateException("The server stopped ticking; is the game paused or frozen with /tick freeze?");
            Thread.sleep(5);
        }
    }

    private static JsonObject runCommand(MinecraftServer server, ServerPlayer player, String command, boolean echo) {
        String text = command.strip().startsWith("/") ? command.strip().substring(1) : command.strip();
        // A player who isn't flying falls after a teleport, which spoils the view it was meant to set up.
        if(text.startsWith("tp ") || text.startsWith("teleport ")) DevCamera.keepFlying(player);
        List<String> output = new ArrayList<>();
        boolean[] success = {false};
        int[] value = {0};
        CommandSource sink = new CommandSource() {
            @Override public void sendSystemMessage(Component message) { output.add(message.getString()); }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        };
        server.getCommands().performPrefixedCommand(player.createCommandSourceStack().withSource(sink).withPermission(4)
            .withCallback((ok, result) -> { success[0] |= ok; value[0] = result; }), text);
        JsonObject response = new JsonObject();
        if(echo) response.addProperty("command", text);
        response.addProperty("ok", success[0]);
        response.addProperty("result", value[0]);
        response.add("output", strings(output));
        return response;
    }

    /**
     * Captures one frame, or a sequence of {@code frames} taken {@code intervalMs} apart for checking motion. A sequence
     * also gets a contact sheet, up to 36 evenly spread frames in one image, left to right then top to bottom, and
     * optionally a {@code video} (mp4 or gif) that plays at the speed the frames were captured. Without the HUD, an
     * open chat is closed first; any other open screen is named in {@code screen}, since it still shows.
     */
    static JsonObject screenshot(boolean hud, int frames, int intervalMs, String video) throws Exception {
        if(frames < 1 || frames > 600 || intervalMs < 0 || intervalMs > 10_000) return failure("Use 1-600 frames and an interval of 0-10000 ms.");
        if(video != null && frames < 2) return failure("A video needs at least 2 frames.");
        Minecraft minecraft = Minecraft.getInstance();
        String name = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss.SSS"));
        Path folder = minecraft.gameDirectory.toPath().resolve("screenshots/dev-tools").toAbsolutePath().normalize();
        List<Path> files = new ArrayList<>();
        if(frames == 1) files.add(folder.resolve(name + ".png"));
        else for(int i = 0; i < frames; i++) files.add(folder.resolve(name).resolve(String.format("frame-%03d.png", i)));
        PendingScreenshot pending = new PendingScreenshot(files, intervalMs * 1_000_000L);
        String screen = minecraft.submit(() -> {
            if(!hud && minecraft.screen instanceof ChatScreen) minecraft.setScreen(null);
            pending.hideGuiBefore = minecraft.options.hideGui;
            minecraft.options.hideGui = !hud;
            screenshot = pending;
            return minecraft.screen == null ? null : minecraft.screen.getClass().getSimpleName();
        }).get(1, TimeUnit.MINUTES);
        pending.result.get(30 + (long)frames * intervalMs / 1000, TimeUnit.SECONDS);
        JsonObject response = new JsonObject();
        response.addProperty("ok", true);
        if(screen != null) response.addProperty("screen", screen);
        if(frames == 1) {
            response.addProperty("path", files.get(0).toString());
            return response;
        }
        List<Integer> sheetFrames = new ArrayList<>();
        for(int i = 0, shown = Math.min(frames, SHEET_FRAMES); i < shown; i++) sheetFrames.add(shown == frames ? i : (int)Math.round(i * (frames - 1.0) / (shown - 1)));
        response.addProperty("sheet", contactSheet(sheetFrames.stream().map(files::get).toList(), folder.resolve(name).resolve("sheet.png")).toString());
        if(sheetFrames.size() < frames) {
            JsonArray shown = new JsonArray();
            sheetFrames.forEach(shown::add);
            response.add("sheetFrames", shown);
        }
        response.add("frames", strings(files.stream().map(Path::toString).toList()));
        JsonArray times = new JsonArray(), gameTimes = new JsonArray();
        pending.times.forEach(time -> times.add(Math.round(time * 10) / 10.0));
        pending.gameTimes.forEach(time -> gameTimes.add(Math.round(time * 1000) / 1000.0));
        response.add("timesMs", times);
        response.add("gameTimes", gameTimes);
        if(video != null) {
            try {
                response.addProperty("video", DevVideo.encode(files, pending.times, video).toString());
            } catch(IOException | IllegalArgumentException e) {
                response.addProperty("videoError", e.getMessage());
            }
        }
        return response;
    }

    /**
     * Tiles the frames into one image at most 1600 pixels wide, so a whole sequence can be viewed at once. Reads one
     * frame at a time, so long sequences don't hold every full-size frame in memory.
     */
    private static Path contactSheet(List<Path> frames, Path sheet) throws IOException {
        int columns = (int)Math.ceil(Math.sqrt(frames.size())), rows = (frames.size() + columns - 1) / columns, gap = 4;
        NativeImage out = null;
        try {
            double scale = 1;
            int cellWidth = 0, cellHeight = 0;
            for(int i = 0; i < frames.size(); i++) {
                try(var in = Files.newInputStream(frames.get(i)); NativeImage image = NativeImage.read(in)) {
                    if(out == null) {
                        scale = Math.min(1, (1600.0 - gap * (columns - 1)) / columns / image.getWidth());
                        cellWidth = (int)(image.getWidth() * scale);
                        cellHeight = (int)(image.getHeight() * scale);
                        out = new NativeImage(columns * cellWidth + gap * (columns - 1), rows * cellHeight + gap * (rows - 1), true);
                        out.fillRect(0, 0, out.getWidth(), out.getHeight(), 0xFF000000);
                    }
                    int left = i % columns * (cellWidth + gap), top = i / columns * (cellHeight + gap);
                    for(int y = 0; y < cellHeight; y++) for(int x = 0; x < cellWidth; x++)
                        out.setPixelRGBA(left + x, top + y, image.getPixelRGBA((int)(x / scale), (int)(y / scale)));
                }
            }
            out.writeToFile(sheet);
        } finally {
            if(out != null) out.close();
        }
        return sheet;
    }

    /** Right-clicks (use) or left-clicks (attack) the crosshair target, or the given block face when coordinates are passed. */
    static JsonObject interact(boolean use, Map<String, String> query) throws Exception {
        return onClient(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            if(minecraft.player == null || minecraft.gameMode == null) return failure("Not in a world.");
            HitResult target = minecraft.hitResult;
            if(query.containsKey("x")) {
                BlockPos pos = new BlockPos(Integer.parseInt(query.get("x")), Integer.parseInt(query.get("y")), Integer.parseInt(query.get("z")));
                Direction face = Direction.byName(query.getOrDefault("face", "up"));
                if(face == null) return failure("Unknown face; use up, down, north, south, east or west.");
                target = new BlockHitResult(Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5)), face, pos, false);
            }
            var player = minecraft.player;
            String outcome;
            if(use) {
                InteractionResult result = switch(target == null ? HitResult.Type.MISS : target.getType()) {
                    case BLOCK -> minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, (BlockHitResult)target);
                    case ENTITY -> minecraft.gameMode.interact(player, ((EntityHitResult)target).getEntity(), InteractionHand.MAIN_HAND);
                    case MISS -> InteractionResult.PASS;
                };
                if(!result.consumesAction()) result = minecraft.gameMode.useItem(player, InteractionHand.MAIN_HAND);
                if(result.shouldSwing()) player.swing(InteractionHand.MAIN_HAND);
                outcome = result.toString();
            } else {
                if(target instanceof BlockHitResult block && target.getType() == HitResult.Type.BLOCK) outcome = minecraft.gameMode.startDestroyBlock(block.getBlockPos(), block.getDirection()) ? "BROKEN" : "STARTED";
                else if(target instanceof EntityHitResult entity) { minecraft.gameMode.attack(player, entity.getEntity()); outcome = "ATTACKED"; }
                else outcome = "MISS";
                player.swing(InteractionHand.MAIN_HAND);
            }
            JsonObject response = new JsonObject();
            response.addProperty("ok", true);
            response.addProperty("result", outcome);
            response.add("target", describe(target));
            return response;
        });
    }

    /** Presses a key (GLFW name such as {@code escape}, {@code e}, {@code enter}) in the open screen, or as a game keybind. */
    static JsonObject key(String name) throws Exception {
        InputConstants.Key key = InputConstants.getKey("key.keyboard." + name.toLowerCase(Locale.ROOT));
        if(key == InputConstants.UNKNOWN) return failure("Unknown key " + name + "; use GLFW names like escape, enter, e, left.shift, f3.");
        return onClient(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            if(minecraft.screen != null) {
                minecraft.screen.keyPressed(key.getValue(), 0, 0);
                if(minecraft.screen != null) minecraft.screen.keyReleased(key.getValue(), 0, 0);
            } else KeyMapping.click(key);
            return ok();
        });
    }

    static JsonObject type(String text) throws Exception {
        return onClient(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            if(minecraft.screen == null) return failure("No screen is open to type into.");
            text.codePoints().forEach(c -> { for(char ch : Character.toChars(c)) minecraft.screen.charTyped(ch, 0); });
            return ok();
        });
    }

    /** Clicks the open screen at screenshot pixel coordinates. */
    static JsonObject click(double x, double y, int button) throws Exception {
        return onClient(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            if(minecraft.screen == null) return failure("No screen is open to click.");
            Window window = minecraft.getWindow();
            double guiX = x * window.getGuiScaledWidth() / window.getWidth(), guiY = y * window.getGuiScaledHeight() / window.getHeight();
            minecraft.screen.mouseClicked(guiX, guiY, button);
            if(minecraft.screen != null) minecraft.screen.mouseReleased(guiX, guiY, button);
            return ok();
        });
    }

    // ---- Helpers ----

    private static final class PendingScreenshot {
        final List<Path> files;
        final long intervalNanos;
        final List<Double> times = new ArrayList<>(), gameTimes = new ArrayList<>();
        final List<CompletableFuture<Void>> saves = new ArrayList<>();
        final AtomicInteger unsaved = new AtomicInteger();
        final CompletableFuture<List<Path>> result = new CompletableFuture<>();
        /** Lets the hidden or restored HUD reach a rendered frame before capturing. */
        int framesToWait = 2;
        long start;
        boolean hideGuiBefore;
        PendingScreenshot(List<Path> files, long intervalNanos) { this.files = files; this.intervalNanos = intervalNanos; }
    }

    private static JsonObject describe(HitResult target) {
        JsonObject json = new JsonObject();
        if(target instanceof BlockHitResult block && target.getType() == HitResult.Type.BLOCK) {
            json.addProperty("type", "block");
            BlockPos pos = block.getBlockPos();
            json.addProperty("pos", pos.getX() + " " + pos.getY() + " " + pos.getZ());
            json.addProperty("face", block.getDirection().getName());
            var level = Minecraft.getInstance().level;
            if(level != null) json.addProperty("block", BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString());
        } else if(target instanceof EntityHitResult entity) {
            json.addProperty("type", "entity");
            json.addProperty("entity", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getEntity().getType()).toString());
            json.addProperty("id", entity.getEntity().getId());
        } else json.addProperty("type", "miss");
        return json;
    }

    private static <T> T onClient(Supplier<T> action) throws Exception {
        return Minecraft.getInstance().submit(action).get(1, TimeUnit.MINUTES);
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

    private static JsonArray strings(Collection<String> values) {
        JsonArray array = new JsonArray();
        synchronized(values) { values.forEach(array::add); }
        return array;
    }

    private static Instrumentation instrumentation() {
        try {
            return (Instrumentation)ClassLoader.getSystemClassLoader().loadClass("com.zmm2025.createdisplayworks.devagent.DevAgent").getField("instrumentation").get(null);
        } catch(ReflectiveOperationException e) {
            return null;
        }
    }

    private static List<Path> paths(String property) {
        return Arrays.stream(System.getProperty(property, "").split(File.pathSeparator)).filter(s -> !s.isBlank()).map(Path::of).toList();
    }

    private static boolean samePath(String a, String b) {
        try { return Path.of(a).toRealPath().equals(Path.of(b).toRealPath()); } catch(IOException e) { return a.equals(b); }
    }

    private static String simpleNames(List<String> names) {
        return names.stream().map(n -> n.substring(n.lastIndexOf('.') + 1)).limit(8).reduce((a, b) -> a + ", " + b).orElse("")
            + (names.size() > 8 ? ", ..." : "");
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : noun.endsWith("s") ? "es" : "s");
    }

    static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
