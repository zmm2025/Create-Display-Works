package com.zmm2025.createdisplayworks.dev.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.Window;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.zmm2025.createdisplayworks.dev.mixin.ChatComponentAccessor;
import com.zmm2025.createdisplayworks.dev.mixin.GuiAccessor;
import net.createmod.catnip.gui.widget.AbstractSimiWidget;
import net.minecraft.ChatFormatting;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.Slot;

import java.util.*;
import java.util.function.Consumer;

/**
 * Reads what a player would read off the screen, so agents can find a button or a message without a screenshot:
 * widgets and slots of the open screen, chat and the action bar, and nearby entities.
 */
final class DevScreens {
    private DevScreens() {}

    /**
     * The open screen's title, widgets and inventory slots. Positions are screenshot pixels, the same coordinates
     * {@code /click} and {@code /mouse} take.
     */
    static JsonObject screen() throws Exception {
        return DevTools.onClient(() -> {
            Screen screen = Minecraft.getInstance().screen;
            JsonObject response = DevTools.ok();
            if(screen == null) return response;
            response.addProperty("screen", screen.getClass().getName());
            response.addProperty("title", screen.getTitle().getString());
            JsonArray widgets = new JsonArray();
            eachWidget(screen, widget -> {
                if(!widget.visible) return;
                JsonObject json = box(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight());
                json.addProperty("type", widget.getClass().getSimpleName());
                String label = label(widget);
                if(!label.isEmpty()) json.addProperty("text", label);
                if(!widget.active) json.addProperty("active", false);
                widgets.add(json);
            });
            response.add("widgets", widgets);
            if(screen instanceof AbstractContainerScreen<?> container) {
                JsonArray slots = new JsonArray();
                for(Slot slot : container.getMenu().slots) {
                    if(slot.getItem().isEmpty() || !slot.isActive()) continue;
                    JsonObject json = box(container.getGuiLeft() + slot.x, container.getGuiTop() + slot.y, 16, 16);
                    json.addProperty("slot", slot.index);
                    json.addProperty("item", BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()) + " x" + slot.getItem().getCount());
                    slots.add(json);
                }
                response.add("slots", slots);
            }
            return response;
        });
    }

    /** Closes the open screen, if any. Escape with no screen open would open the pause menu, which pauses the game. */
    static JsonObject close() throws Exception {
        return DevTools.onClient(() -> {
            Screen screen = Minecraft.getInstance().screen;
            JsonObject response = DevTools.ok();
            response.addProperty("closed", screen != null);
            if(screen != null) screen.onClose();
            return response;
        });
    }

    /** Whether {@code query} names a place to move the cursor to. */
    static boolean locates(Map<String, String> query) {
        return query.containsKey("x") || query.containsKey("text") || query.containsKey("slot");
    }

    /** The screenshot pixel {@code query} points at: {@code x y}, a widget's {@code text}, or a {@code slot}. Null if there's no match. */
    static double[] locate(Map<String, String> query) {
        if(query.containsKey("x")) return new double[]{Double.parseDouble(query.get("x")), Double.parseDouble(query.get("y"))};
        Minecraft minecraft = Minecraft.getInstance();
        Screen screen = minecraft.screen;
        double scale = (double)minecraft.getWindow().getWidth() / minecraft.getWindow().getGuiScaledWidth();
        if(query.containsKey("slot") && screen instanceof AbstractContainerScreen<?> container) {
            Slot slot = container.getMenu().getSlot(Integer.parseInt(query.get("slot")));
            return new double[]{(container.getGuiLeft() + slot.x + 8) * scale, (container.getGuiTop() + slot.y + 8) * scale};
        }
        if(!query.containsKey("text") || screen == null) return null;
        String wanted = query.get("text").toLowerCase(Locale.ROOT);
        List<AbstractWidget> matches = new ArrayList<>();
        eachWidget(screen, widget -> { if(widget.visible && label(widget).toLowerCase(Locale.ROOT).contains(wanted)) matches.add(widget); });
        if(matches.isEmpty()) return null;
        AbstractWidget widget = matches.stream().filter(match -> match.active).findFirst().orElse(matches.get(0));
        return new double[]{(widget.getX() + widget.getWidth() / 2.0) * scale, (widget.getY() + widget.getHeight() / 2.0) * scale};
    }

    /** The last {@code lines} chat messages, oldest first, and the action bar if it's showing. */
    static JsonObject chat(int lines) throws Exception {
        return DevTools.onClient(() -> {
            List<GuiMessage> messages = messages();
            JsonArray chat = new JsonArray();
            for(int i = Math.min(lines, messages.size()) - 1; i >= 0; i--) chat.add(messages.get(i).content().getString());
            JsonObject response = DevTools.ok();
            response.add("chat", chat);
            GuiAccessor gui = (GuiAccessor)Minecraft.getInstance().gui;
            if(gui.createDisplayWorksDev$getOverlayMessageTime() > 0 && gui.createDisplayWorksDev$getOverlayMessage() != null)
                response.addProperty("actionBar", gui.createDisplayWorksDev$getOverlayMessage().getString());
            return response;
        });
    }

    /** Chat messages, newest first. Only for the render thread. */
    static List<GuiMessage> messages() {
        return ((ChatComponentAccessor)Minecraft.getInstance().gui.getChat()).createDisplayWorksDev$getAllMessages();
    }

    /** The newest chat message, to pass to {@link #since} later. Only for the render thread. */
    static GuiMessage newest() {
        return messages().isEmpty() ? null : messages().get(0);
    }

    /** The messages newer than {@code mark}, oldest first. Only for the render thread. */
    static List<Component> since(GuiMessage mark) {
        List<Component> added = new ArrayList<>();
        for(GuiMessage message : messages()) {
            if(message == mark) break;
            added.add(0, message.content());
        }
        return added;
    }

    /** Commands report failure in red. */
    static boolean failed(Component message) {
        return TextColor.fromLegacyFormat(ChatFormatting.RED).equals(message.getStyle().getColor()) || message.getSiblings().stream().anyMatch(DevScreens::failed);
    }

    /** Entities near the player, nearest first. Their ids work with {@code /look} and {@code /use}. */
    static JsonObject entities(Map<String, String> query) throws Exception {
        double radius = Double.parseDouble(query.getOrDefault("radius", "32"));
        String type = query.get("type");
        int limit = Integer.parseInt(query.getOrDefault("limit", "50"));
        return DevTools.onClient(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            if(minecraft.player == null) return DevTools.failure("Not in a world.");
            List<Entity> near = new ArrayList<>(minecraft.level.getEntities(minecraft.player, minecraft.player.getBoundingBox().inflate(radius)));
            near.removeIf(entity -> type != null && !BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().contains(type));
            near.sort(Comparator.comparingDouble(minecraft.player::distanceToSqr));
            JsonArray list = new JsonArray();
            near.stream().limit(limit).forEach(entity -> {
                JsonObject json = new JsonObject();
                json.addProperty("id", entity.getId());
                json.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
                json.addProperty("name", entity.getName().getString());
                json.addProperty("pos", DevTools.round(entity.getX()) + " " + DevTools.round(entity.getY()) + " " + DevTools.round(entity.getZ()));
                if(entity.getVehicle() != null) json.addProperty("vehicle", entity.getVehicle().getId());
                if(entity instanceof CarriageContraptionEntity carriage && carriage.trainId != null) json.addProperty("train", carriage.trainId.toString());
                list.add(json);
            });
            JsonObject response = DevTools.ok();
            response.addProperty("count", near.size());
            response.add("entities", list);
            return response;
        });
    }

    private static void eachWidget(ContainerEventHandler parent, Consumer<AbstractWidget> visitor) {
        for(GuiEventListener child : parent.children()) {
            if(child instanceof AbstractWidget widget) visitor.accept(widget);
            if(child instanceof ContainerEventHandler nested) eachWidget(nested, visitor);
        }
    }

    /** A widget's text, or for Create's icon buttons, which have none, the first line of their tooltip. */
    private static String label(AbstractWidget widget) {
        String text = widget.getMessage().getString();
        if(text.isEmpty() && widget instanceof AbstractSimiWidget simi && !simi.getToolTip().isEmpty()) text = simi.getToolTip().get(0).getString();
        return text;
    }

    private static JsonObject box(int x, int y, int width, int height) {
        Window window = Minecraft.getInstance().getWindow();
        double scale = (double)window.getWidth() / window.getGuiScaledWidth();
        JsonObject json = new JsonObject();
        json.addProperty("x", Math.round(x * scale));
        json.addProperty("y", Math.round(y * scale));
        json.addProperty("width", Math.round(width * scale));
        json.addProperty("height", Math.round(height * scale));
        return json;
    }
}
