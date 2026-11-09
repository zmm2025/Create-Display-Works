package com.zmm2025.createdisplayworks;

import static com.zmm2025.createdisplayworks.CreateDisplayWorks.MOD_ID;
import static com.zmm2025.createdisplayworks.CreateDisplayWorks.REGISTRATE;
import static com.zmm2025.createdisplayworks.CreateDisplayWorks.asResource;

import com.tterrag.registrate.util.entry.RegistryEntry;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.common.data.SoundDefinitionsProvider;

/** Sound events. Each plays {@code sounds/<name>.ogg}; datagen writes it and its subtitle into sounds.json. */
public final class ModSounds {
    private static final List<String> NAMES = new ArrayList<>();

    public static final RegistryEntry<SoundEvent, SoundEvent> DISC_RATTLE =
            sound("disc_rattle", "Display discs rattle");

    private static RegistryEntry<SoundEvent, SoundEvent> sound(String name, String subtitle) {
        NAMES.add(name);
        REGISTRATE.addRawLang(subtitle(name), subtitle);
        return REGISTRATE.simple(
                name, Registries.SOUND_EVENT, () -> SoundEvent.createVariableRangeEvent(asResource(name)));
    }

    private static String subtitle(String name) {
        return "subtitles." + MOD_ID + "." + name;
    }

    static SoundDefinitionsProvider definitions(PackOutput output, ExistingFileHelper files) {
        return new SoundDefinitionsProvider(output, MOD_ID, files) {
            @Override
            public void registerSounds() {
                for (String name : NAMES)
                    add(asResource(name), definition().subtitle(subtitle(name)).with(sound(asResource(name))));
            }
        };
    }

    public static void register() {}
}
