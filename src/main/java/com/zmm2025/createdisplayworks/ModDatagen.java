package com.zmm2025.createdisplayworks;

import static com.zmm2025.createdisplayworks.CreateDisplayWorks.MOD_ID;
import static com.zmm2025.createdisplayworks.CreateDisplayWorks.REGISTRATE;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tterrag.registrate.providers.ProviderType;
import com.zmm2025.createdisplayworks.ponder.DisplayWorksPonderPlugin;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import net.createmod.ponder.foundation.PonderIndex;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * Data generation, run by {@code ./gradlew runData} into {@code src/generated/resources}. Registrate writes what each
 * registration declares. The English lang file also gets the hand-written strings in {@code src/main/lang/en_us.json}
 * and the Ponder text from the scenes' code.
 */
final class ModDatagen {
    /** Set by the data run in build.gradle. */
    private static final String LANG_SOURCE = "createdisplayworks.datagen.lang";

    static void gatherDataHighPriority(GatherDataEvent event) {
        if (!event.getMods().contains(MOD_ID)) return;
        REGISTRATE.addDataGenerator(ProviderType.LANG, provider -> {
            BiConsumer<String, String> lang = provider::add;
            handWrittenLang(lang);
            // Client setup, which adds the plugin in game, doesn't run during datagen.
            PonderIndex.addPlugin(new DisplayWorksPonderPlugin());
            PonderIndex.getLangAccess().provideLang(MOD_ID, lang);
        });
    }

    static void gatherData(GatherDataEvent event) {
        if (!event.getMods().contains(MOD_ID)) return;
        var output = event.getGenerator().getPackOutput();
        event.getGenerator()
                .addProvider(event.includeClient(), ModSounds.definitions(output, event.getExistingFileHelper()));
    }

    private static void handWrittenLang(BiConsumer<String, String> lang) {
        String source = System.getProperty(LANG_SOURCE);
        if (source == null) throw new IllegalStateException(LANG_SOURCE + " isn't set. Run datagen with runData.");
        try {
            JsonObject strings = JsonParser.parseString(Files.readString(Path.of(source), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            strings.entrySet().forEach(e -> lang.accept(e.getKey(), e.getValue().getAsString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
