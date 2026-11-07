package com.zmm2025.createdisplayworks;

import com.simibubi.create.api.behaviour.display.DisplayTarget;
import com.zmm2025.createdisplayworks.media.MediaLimits;
import com.zmm2025.createdisplayworks.media.MediaNetwork;
import com.zmm2025.createdisplayworks.media.MediaStore;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod(CreateDisplayWorks.MOD_ID)
public final class CreateDisplayWorks {
    public static final String MOD_ID = "create_display_works";

    public CreateDisplayWorks(IEventBus bus, ModContainer container) {
        ModContent.register(bus);
        MediaNetwork.registerLifecycle();
        MediaStore.registerLifecycle();
        container.registerConfig(ModConfig.Type.SERVER, MediaLimits.SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        bus.addListener(this::setup);
        bus.addListener(MediaNetwork::register);
    }

    private void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            DisplayTarget.BY_BLOCK.register(ModContent.FLIP_DOT.get(), ModContent.TARGET.get());
        });
    }
}
