package com.zmm2025.createdisplayworks.dev.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.zmm2025.createdisplayworks.dev.mixin.KeyboardHandlerAccessor;
import com.zmm2025.createdisplayworks.dev.mixin.MouseHandlerAccessor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.ClientHooks;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Keyboard and mouse input for the control API, sent through the same handlers GLFW's callbacks call, so vanilla, mods
 * and their input events see what they'd see from a player. A key or button can be tapped, held for some ticks, or
 * pressed and released in separate requests, in the world as well as in screens. Held input also answers code that
 * reads the physical keyboard; the dev agent patches GLFW for that.
 */
final class DevInput {
    private static final int MAX_HOLD_TICKS = 1200;
    /** Long enough for the server to hear a modifier key, such as sneaking, before the click that depends on it. */
    private static final int MODIFIER_TICKS = 2;
    private static final int HELD_ITEM_TICKS = 60;
    private static final Set<Integer> HELD_KEYS = shared("createdisplayworks.dev.heldKeys"), HELD_BUTTONS = shared("createdisplayworks.dev.heldButtons");
    private static volatile long clientTicks;

    private DevInput() {}

    /** The sets the dev agent's GLFW patch reads. Without the agent, input still works, but only through events. */
    @SuppressWarnings("unchecked")
    private static Set<Integer> shared(String property) {
        return System.getProperties().get(property) instanceof Set<?> set ? (Set<Integer>)set : ConcurrentHashMap.newKeySet();
    }

    private interface Press {
        void set(boolean down);
    }

    /** Runs every client tick. A hidden window can't grab the mouse, and held attacks only continue while it's grabbed. */
    static void tick() {
        clientTicks++;
        Minecraft minecraft = Minecraft.getInstance();
        if(!DevInstance.hidden() || minecraft.player == null || minecraft.screen != null || minecraft.getOverlay() != null || minecraft.mouseHandler.isMouseGrabbed()) return;
        ((MouseHandlerAccessor)minecraft.mouseHandler).createDisplayWorksDev$setMouseGrabbed(true);
        // What grabMouse does for keys held through a closing screen.
        KeyMapping.setAll();
        HELD_BUTTONS.forEach(button -> KeyMapping.set(InputConstants.Type.MOUSE.getOrCreate(button), true));
    }

    /** Lets a shown window grab the mouse for real on its next click. */
    static void windowShown() {
        ((MouseHandlerAccessor)Minecraft.getInstance().mouseHandler).createDisplayWorksDev$setMouseGrabbed(false);
    }

    /** Blocks for {@code ticks} client ticks, for waits that have no integrated server to count on. */
    static void waitClientTicks(int ticks) throws Exception {
        if(ticks < 0 || ticks > MAX_HOLD_TICKS) throw new IllegalArgumentException("Wait 0-" + MAX_HOLD_TICKS + " ticks.");
        long target = clientTicks + ticks, deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(5_000 + ticks * 250L);
        while(clientTicks < target) {
            if(System.nanoTime() > deadline) throw new IllegalStateException("The client stopped ticking.");
            Thread.sleep(5);
        }
    }

    /**
     * Waits until the client's main hand holds {@code item}, if given. A held item set by command takes some ticks to
     * reach the client, and a click before that would use the old item.
     */
    static void awaitHeld(String item) throws Exception {
        if(item == null) return;
        for(int ticks = 0; ticks < HELD_ITEM_TICKS; ticks++) {
            if(DevTools.onClient(() -> {
                var player = Minecraft.getInstance().player;
                return player != null && BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString().equals(item);
            })) return;
            waitClientTicks(1);
        }
        throw new IllegalStateException("The player's main hand didn't become " + item + " within " + HELD_ITEM_TICKS + " ticks.");
    }

    static JsonObject key(Map<String, String> query, String body) throws Exception {
        int key = keyCode(query.getOrDefault("key", body.strip()));
        return input(query, down -> keyEvent(key, down));
    }

    /** Clicks in the open screen, or in the world at the crosshair, optionally at a position, widget or slot first. */
    static JsonObject click(Map<String, String> query) throws Exception {
        int button = button(query.getOrDefault("button", "0"));
        awaitHeld(query.get("holding"));
        JsonObject failed = moveTo(query);
        return failed != null ? failed : input(query, down -> buttonEvent(button, down));
    }

    /** Moves the cursor in the open screen, in {@code steps} even moves, dragging if a mouse button is down. */
    static JsonObject mouse(Map<String, String> query) throws Exception {
        int steps = Integer.parseInt(query.getOrDefault("steps", "1"));
        if(steps < 1 || steps > 200) return DevTools.failure("Use 1-200 steps.");
        return DevTools.onClient(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            if(minecraft.screen == null) return DevTools.failure("No screen is open to move the mouse in.");
            double[] target = DevScreens.locate(query);
            if(target == null) return DevTools.failure("Pass x and y, or the text or slot of something on the screen.");
            Window window = minecraft.getWindow();
            double fromX = minecraft.mouseHandler.xpos() * window.getWidth() / window.getScreenWidth();
            double fromY = minecraft.mouseHandler.ypos() * window.getHeight() / window.getScreenHeight();
            for(int i = 1; i <= steps && minecraft.screen != null; i++)
                moveCursor(fromX + (target[0] - fromX) * i / steps, fromY + (target[1] - fromY) * i / steps);
            return DevTools.ok();
        });
    }

    /** Scrolls the wheel over the open screen, or in the world, where it changes the hotbar slot unless a mod takes it. */
    static JsonObject scroll(Map<String, String> query) throws Exception {
        double delta = Double.parseDouble(query.getOrDefault("delta", "1"));
        JsonObject failed = moveTo(query);
        if(failed != null) return failed;
        return withModifiers(query.get("mods"), () -> DevTools.onClient(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            if(minecraft.screen == null && minecraft.player == null) return DevTools.failure("No screen is open to scroll, and the player isn't in a world.");
            ((MouseHandlerAccessor)minecraft.mouseHandler).createDisplayWorksDev$onScroll(minecraft.getWindow().getWindow(), 0, delta);
            JsonObject response = DevTools.ok();
            if(minecraft.player != null) response.addProperty("selectedSlot", minecraft.player.getInventory().selected);
            return response;
        }));
    }

    static JsonObject type(String text) throws Exception {
        return DevTools.onClient(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            if(minecraft.screen == null) return DevTools.failure("No screen is open to type into.");
            KeyboardHandlerAccessor keyboard = (KeyboardHandlerAccessor)minecraft.keyboardHandler;
            text.codePoints().forEach(c -> keyboard.createDisplayWorksDev$charTyped(minecraft.getWindow().getWindow(), c, heldModifiers()));
            return DevTools.ok();
        });
    }

    /**
     * Turns the player: {@code yaw} and {@code pitch} absolutely, {@code dx} and {@code dy} in degrees relative to the
     * current view, or to face {@code x y z} or the entity {@code entity} (an id from {@code /entities}).
     */
    static JsonObject look(Map<String, String> query) throws Exception {
        return DevTools.onClient(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            var player = minecraft.player;
            if(player == null) return DevTools.failure("Not in a world.");
            float yaw = player.getYRot(), pitch = player.getXRot();
            Vec3 target = null;
            if(query.containsKey("entity")) {
                Entity entity = minecraft.level.getEntity(Integer.parseInt(query.get("entity")));
                if(entity == null) return DevTools.failure("No entity with that id is loaded.");
                target = entity.getBoundingBox().getCenter();
            } else if(query.containsKey("x")) {
                target = new Vec3(Double.parseDouble(query.get("x")), Double.parseDouble(query.get("y")), Double.parseDouble(query.get("z")));
                if(!query.get("x").contains(".")) target = target.add(0.5, 0.5, 0.5);
            }
            if(target != null) {
                Vec3 look = target.subtract(player.getEyePosition());
                yaw = (float)Math.toDegrees(Math.atan2(-look.x, look.z));
                pitch = (float)-Math.toDegrees(Math.atan2(look.y, Math.sqrt(look.x * look.x + look.z * look.z)));
            }
            if(query.containsKey("yaw")) yaw = Float.parseFloat(query.get("yaw"));
            if(query.containsKey("pitch")) pitch = Float.parseFloat(query.get("pitch"));
            yaw += Float.parseFloat(query.getOrDefault("dx", "0"));
            pitch = Math.clamp(pitch + Float.parseFloat(query.getOrDefault("dy", "0")), -90, 90);
            player.setYRot(yaw);
            player.setXRot(pitch);
            player.setYHeadRot(yaw);
            player.yRotO = yaw;
            player.xRotO = pitch;
            minecraft.gameRenderer.pick(1);
            JsonObject response = DevTools.ok();
            response.addProperty("yaw", DevTools.round(yaw));
            response.addProperty("pitch", DevTools.round(pitch));
            response.add("target", DevTools.describe(minecraft.hitResult));
            return response;
        });
    }

    /** Hands files to the open screen, as dragging them onto the window does. Native file dialogs can't be driven. */
    static JsonObject drop(String body) throws Exception {
        List<Path> paths = body.lines().map(String::strip).filter(line -> !line.isEmpty()).map(Path::of).toList();
        if(paths.isEmpty()) return DevTools.failure("Send the file paths in the body, one per line.");
        return DevTools.onClient(() -> {
            Screen screen = Minecraft.getInstance().screen;
            if(screen == null) return DevTools.failure("No screen is open to drop files on.");
            screen.onFilesDrop(paths);
            return DevTools.ok();
        });
    }

    /** Lets go of every key and mouse button the control API is holding. */
    static JsonObject release() throws Exception {
        return DevTools.onClient(() -> {
            JsonObject response = held();
            response.addProperty("ok", true);
            releaseAll();
            return response;
        });
    }

    /** Runs on the render thread. */
    static void releaseAll() {
        new ArrayList<>(HELD_BUTTONS).forEach(button -> buttonEvent(button, false));
        new ArrayList<>(HELD_KEYS).forEach(key -> keyEvent(key, false));
    }

    /** What the control API is holding. */
    static JsonObject held() {
        JsonObject json = new JsonObject();
        JsonArray keys = new JsonArray(), buttons = new JsonArray();
        HELD_KEYS.forEach(key -> keys.add(InputConstants.Type.KEYSYM.getOrCreate(key).getName().replace("key.keyboard.", "")));
        HELD_BUTTONS.forEach(buttons::add);
        json.add("keys", keys);
        json.add("buttons", buttons);
        JsonArray mappings = new JsonArray();
        for(KeyMapping mapping : Minecraft.getInstance().options.keyMappings) if(mapping.isDown()) mappings.add(mapping.getName());
        json.add("keyMappings", mappings);
        return json;
    }

    /** Holds the modifiers in {@code mods} around {@code action}, the way a player holds shift while clicking. */
    static JsonObject withModifiers(String mods, DevTools.Action action) throws Exception {
        List<Integer> keys = modifierKeys(mods);
        if(keys.isEmpty()) return action.run();
        pressModifiers(keys, true);
        try {
            return action.run();
        } finally {
            pressModifiers(keys, false);
        }
    }

    /** A tap, by default, or {@code action=down}, {@code action=up}, or a hold of {@code ticks} that blocks until released. */
    private static JsonObject input(Map<String, String> query, Press press) throws Exception {
        String action = query.getOrDefault("action", "tap");
        int ticks = Integer.parseInt(query.getOrDefault("ticks", "0"));
        if(ticks < 0 || ticks > MAX_HOLD_TICKS) return DevTools.failure("Hold for 0-" + MAX_HOLD_TICKS + " ticks.");
        if(ticks > 0 && !action.equals("tap")) return DevTools.failure("Use ticks without action; it presses and releases.");
        return switch(action) {
            case "tap" -> withModifiers(query.get("mods"), () -> {
                DevTools.onClient(() -> { press.set(true); if(ticks == 0) press.set(false); return null; });
                if(ticks > 0) {
                    waitClientTicks(ticks);
                    DevTools.onClient(() -> { press.set(false); return null; });
                }
                JsonObject response = DevTools.ok();
                if(ticks > 0) response.addProperty("heldTicks", ticks);
                return response;
            });
            case "down" -> {
                pressModifiers(modifierKeys(query.get("mods")), true);
                DevTools.onClient(() -> { press.set(true); return null; });
                yield DevTools.ok();
            }
            case "up" -> {
                DevTools.onClient(() -> { press.set(false); return null; });
                pressModifiers(modifierKeys(query.get("mods")), false);
                yield DevTools.ok();
            }
            default -> DevTools.failure("Unknown action " + action + "; use tap, down or up.");
        };
    }

    /** Moves to the position, widget or slot in {@code query}, if any. Returns a failure to report, or null. */
    private static JsonObject moveTo(Map<String, String> query) throws Exception {
        if(!DevScreens.locates(query)) return null;
        return DevTools.onClient(() -> {
            if(Minecraft.getInstance().screen == null) return DevTools.failure("No screen is open to move the mouse in; world clicks go to the crosshair.");
            double[] target = DevScreens.locate(query);
            if(target == null) return DevTools.failure("Nothing on the screen matches; GET /screen lists what's there.");
            moveCursor(target[0], target[1]);
            return null;
        });
    }

    /** Moves the cursor to screenshot pixel coordinates. Runs on the render thread. */
    private static void moveCursor(double x, double y) {
        Minecraft minecraft = Minecraft.getInstance();
        Window window = minecraft.getWindow();
        Screen screen = minecraft.screen;
        MouseHandlerAccessor mouse = (MouseHandlerAccessor)minecraft.mouseHandler;
        // The cursor is in window coordinates, which differ from screenshot pixels on high-DPI displays.
        double cursorX = x * window.getScreenWidth() / window.getWidth(), cursorY = y * window.getScreenHeight() / window.getHeight();
        double scale = (double)window.getGuiScaledWidth() / window.getScreenWidth();
        double dragX = (cursorX - minecraft.mouseHandler.xpos()) * scale, dragY = (cursorY - minecraft.mouseHandler.ypos()) * scale;
        mouse.createDisplayWorksDev$setXpos(cursorX);
        mouse.createDisplayWorksDev$setYpos(cursorY);
        double guiX = cursorX * scale, guiY = cursorY * scale;
        screen.mouseMoved(guiX, guiY);
        int button = mouse.createDisplayWorksDev$getActiveButton();
        if(button != -1 && !ClientHooks.onScreenMouseDragPre(screen, guiX, guiY, button, dragX, dragY)
            && !screen.mouseDragged(guiX, guiY, button, dragX, dragY))
            ClientHooks.onScreenMouseDragPost(screen, guiX, guiY, button, dragX, dragY);
    }

    private static void pressModifiers(List<Integer> keys, boolean down) throws Exception {
        if(keys.isEmpty()) return;
        DevTools.onClient(() -> { keys.forEach(key -> keyEvent(key, down)); return null; });
        if(Minecraft.getInstance().screen == null && Minecraft.getInstance().player != null) waitClientTicks(MODIFIER_TICKS);
    }

    private static void keyEvent(int key, boolean down) {
        // GLFW updates its key state before calling back.
        if(down) HELD_KEYS.add(key); else HELD_KEYS.remove(key);
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.keyboardHandler.keyPress(minecraft.getWindow().getWindow(), key, 0, down ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE, heldModifiers());
    }

    private static void buttonEvent(int button, boolean down) {
        if(down) HELD_BUTTONS.add(button); else HELD_BUTTONS.remove(button);
        Minecraft minecraft = Minecraft.getInstance();
        if(minecraft.screen == null && minecraft.player == null) return;
        ((MouseHandlerAccessor)minecraft.mouseHandler).createDisplayWorksDev$onPress(minecraft.getWindow().getWindow(), button, down ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE, heldModifiers());
    }

    private static int heldModifiers() {
        int modifiers = 0;
        if(HELD_KEYS.contains(GLFW.GLFW_KEY_LEFT_SHIFT) || HELD_KEYS.contains(GLFW.GLFW_KEY_RIGHT_SHIFT)) modifiers |= GLFW.GLFW_MOD_SHIFT;
        if(HELD_KEYS.contains(GLFW.GLFW_KEY_LEFT_CONTROL) || HELD_KEYS.contains(GLFW.GLFW_KEY_RIGHT_CONTROL)) modifiers |= GLFW.GLFW_MOD_CONTROL;
        if(HELD_KEYS.contains(GLFW.GLFW_KEY_LEFT_ALT) || HELD_KEYS.contains(GLFW.GLFW_KEY_RIGHT_ALT)) modifiers |= GLFW.GLFW_MOD_ALT;
        if(HELD_KEYS.contains(GLFW.GLFW_KEY_LEFT_SUPER) || HELD_KEYS.contains(GLFW.GLFW_KEY_RIGHT_SUPER)) modifiers |= GLFW.GLFW_MOD_SUPER;
        return modifiers;
    }

    private static List<Integer> modifierKeys(String mods) {
        List<Integer> keys = new ArrayList<>();
        for(String mod : mods == null || mods.isBlank() ? new String[0] : mods.toLowerCase(Locale.ROOT).split(",")) {
            keys.add(switch(mod.strip()) {
                case "shortcut" -> Minecraft.ON_OSX ? GLFW.GLFW_KEY_LEFT_SUPER : GLFW.GLFW_KEY_LEFT_CONTROL;
                case "ctrl", "control" -> GLFW.GLFW_KEY_LEFT_CONTROL;
                case "super", "cmd" -> GLFW.GLFW_KEY_LEFT_SUPER;
                case "shift" -> GLFW.GLFW_KEY_LEFT_SHIFT;
                case "sneak" -> boundKey("key.sneak");
                case "alt" -> GLFW.GLFW_KEY_LEFT_ALT;
                default -> throw new IllegalArgumentException("Unknown modifier " + mod + "; use shortcut, ctrl, super, shift or alt.");
            });
        }
        return keys;
    }

    private static int keyCode(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if(lower.equals("sneak")) return boundKey("key.sneak");
        for(KeyMapping mapping : Minecraft.getInstance().options.keyMappings) if(mapping.getName().equals(name)) return boundKey(name);
        String full = switch(lower) {
            case "shift" -> "left.shift";
            case "ctrl", "control" -> "left.control";
            case "alt" -> "left.alt";
            default -> lower;
        };
        InputConstants.Key key = InputConstants.getKey("key.keyboard." + full);
        if(key == InputConstants.UNKNOWN) throw new IllegalArgumentException("Unknown key " + name + "; use GLFW names like escape, enter, e, left.shift, f3.");
        return key.getValue();
    }

    /** The keyboard key an action such as {@code key.sneak} is bound to. Key bindings come from the player's options. */
    private static int boundKey(String action) {
        for(KeyMapping mapping : Minecraft.getInstance().options.keyMappings) {
            if(!mapping.getName().equals(action)) continue;
            if(mapping.getKey().getType() != InputConstants.Type.KEYSYM) throw new IllegalArgumentException(action + " isn't bound to a keyboard key; use /click for mouse bindings.");
            return mapping.getKey().getValue();
        }
        throw new IllegalArgumentException("Unknown key binding " + action + ".");
    }

    private static int button(String name) {
        return switch(name.toLowerCase(Locale.ROOT)) {
            case "left" -> 0;
            case "right" -> 1;
            case "middle" -> 2;
            default -> Integer.parseInt(name);
        };
    }
}
