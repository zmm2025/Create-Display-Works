package com.zmm2025.createdisplayworks.dev.tools;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/**
 * Reads and changes game settings by the names {@code options.txt} uses, so every setting works, including key bindings
 * and sound levels. Going through the file means a change applies the way the game applies a loaded setting. The first
 * change keeps a copy of the file for {@code restore}.
 */
final class DevOptions {
    private static final String BACKUP = "options.dev-backup.txt";
    private static final Set<String> RESERVED = Set.of("restore", "filter");

    private DevOptions() {}

    /** With no settings in {@code query}, lists them; {@code filter} keeps names that contain it. */
    static JsonObject options(Map<String, String> query) throws Exception {
        return DevTools.onClient(() -> {
            try {
                Minecraft minecraft = Minecraft.getInstance();
                Path file = minecraft.options.getFile().toPath(), backup = file.resolveSibling(BACKUP);
                minecraft.options.save();
                Map<String, String> before = read(file);
                if (Boolean.parseBoolean(query.get("restore"))) {
                    if (!Files.exists(backup))
                        return DevApi.failure(
                                "There's nothing to restore: no setting has changed since the last restore.");
                    Files.move(backup, file, StandardCopyOption.REPLACE_EXISTING);
                    return apply(minecraft, before, read(file));
                }
                Map<String, String> wanted = new LinkedHashMap<>(query);
                wanted.keySet().removeAll(RESERVED);
                if (wanted.isEmpty()) return list(before, query.get("filter"));
                List<String> unknown = wanted.keySet().stream()
                        .filter(name -> !before.containsKey(name))
                        .toList();
                if (!unknown.isEmpty())
                    return DevApi.failure(
                            "Unknown settings: " + String.join(", ", unknown) + ". GET /options lists them.");
                if (!Files.exists(backup)) Files.copy(file, backup);
                Map<String, String> after = new LinkedHashMap<>(before);
                after.putAll(wanted);
                write(file, after);
                return apply(minecraft, before, after);
            } catch (IOException e) {
                return DevApi.failure(e.toString());
            }
        });
    }

    /** Loads the file, then does what the settings screens do for the settings that need more than a new value. */
    private static JsonObject apply(Minecraft minecraft, Map<String, String> before, Map<String, String> after) {
        Options options = minecraft.options;
        options.load();
        options.save();
        Set<String> changed = new TreeSet<>();
        after.forEach((name, value) -> {
            if (!value.equals(before.get(name))) changed.add(name);
        });
        // The video settings button does this, not the option; Fabulous crashes on the next frame without it.
        if (changed.contains("graphicsMode")) minecraft.levelRenderer.allChanged();
        if (changed.contains("lang")) minecraft.getLanguageManager().setSelected(options.languageCode);
        if (changed.contains("mipmapLevels")) {
            minecraft.updateMaxMipLevel(options.mipmapLevels().get());
            minecraft.delayTextureReload();
        }
        boolean packs = changed.contains("resourcePacks") || changed.contains("incompatibleResourcePacks");
        if (packs) options.loadSelectedResourcePacks(minecraft.getResourcePackRepository());
        if (packs || changed.contains("lang")) minecraft.reloadResourcePacks();
        JsonObject response = DevApi.ok(), changes = new JsonObject();
        changed.forEach(name -> {
            JsonObject change = new JsonObject();
            change.addProperty("before", before.get(name));
            change.addProperty("after", after.get(name));
            changes.add(name, change);
        });
        response.add("changed", changes);
        return response;
    }

    private static JsonObject list(Map<String, String> options, String filter) {
        JsonObject response = DevApi.ok(), values = new JsonObject();
        String wanted = filter == null ? "" : filter.toLowerCase(Locale.ROOT);
        options.forEach((name, value) -> {
            if (name.toLowerCase(Locale.ROOT).contains(wanted)) values.addProperty(name, value);
        });
        response.add("options", values);
        return response;
    }

    private static Map<String, String> read(Path file) throws IOException {
        Map<String, String> options = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int split = line.indexOf(':');
            if (split > 0) options.put(line.substring(0, split), line.substring(split + 1));
        }
        return options;
    }

    private static void write(Path file, Map<String, String> options) throws IOException {
        List<String> lines = new ArrayList<>();
        options.forEach((name, value) -> lines.add(name + ":" + value));
        Files.write(file, lines, StandardCharsets.UTF_8);
    }
}
