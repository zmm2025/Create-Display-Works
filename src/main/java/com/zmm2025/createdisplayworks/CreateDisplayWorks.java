package com.zmm2025.createdisplayworks;

import com.simibubi.create.foundation.data.CreateRegistrate;
import com.zmm2025.createdisplayworks.media.MediaLimits;
import com.zmm2025.createdisplayworks.media.MediaNetwork;
import com.zmm2025.createdisplayworks.media.MediaStore;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.data.loading.DatagenModLoader;

@Mod(CreateDisplayWorks.MOD_ID)
public final class CreateDisplayWorks {
    public static final String MOD_ID = "create_display_works";
    /** Registers the mod's content and generates its data. See "Adding a block" in DEVELOPMENT.md. */
    public static final CreateRegistrate REGISTRATE = CreateRegistrate.create(MOD_ID);

    public CreateDisplayWorks(IEventBus bus, ModContainer container) {
        REGISTRATE.registerEventListeners(bus);
        ModSounds.register();
        ModCreativeTabs.register();
        ModDisplayTargets.register();
        ModBlocks.register();
        ModBlockEntities.register();
        MediaNetwork.registerLifecycle();
        MediaStore.registerLifecycle();
        container.registerConfig(ModConfig.Type.SERVER, MediaLimits.SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        bus.addListener(MediaNetwork::register);
        if (DatagenModLoader.isRunningDataGen()) {
            // Before Registrate's own listener, which collects the data generators.
            bus.addListener(EventPriority.HIGHEST, ModDatagen::gatherDataHighPriority);
            bus.addListener(ModDatagen::gatherData);
        }
    }

    public static ResourceLocation asResource(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
