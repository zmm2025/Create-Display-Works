package com.zmm2025.createdisplayworks;

import static com.zmm2025.createdisplayworks.CreateDisplayWorks.MOD_ID;
import static com.zmm2025.createdisplayworks.CreateDisplayWorks.REGISTRATE;

import com.simibubi.create.AllCreativeModeTabs;
import com.tterrag.registrate.util.entry.RegistryEntry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;

/** The mod's creative tab. Registrate fills it with every item registered after {@link ModBlocks} makes it the default. */
public final class ModCreativeTabs {
    private static final Component TITLE = REGISTRATE.addRawLang("itemGroup." + MOD_ID, "Create: Display Works");

    public static final RegistryEntry<CreativeModeTab, CreativeModeTab> MAIN = REGISTRATE.simple(
            "main",
            Registries.CREATIVE_MODE_TAB,
            () -> CreativeModeTab.builder()
                    .title(TITLE)
                    .icon(() -> ModBlocks.FLIP_DOT_DISPLAY.asStack())
                    .withTabsBefore(AllCreativeModeTabs.PALETTES_CREATIVE_TAB.getKey())
                    .build());

    public static void register() {}
}
