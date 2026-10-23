package com.zmm2025.createdisplayworks;

import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import com.simibubi.create.api.behaviour.display.DisplayTarget;

@Mod(CreateDisplayWorks.MOD_ID)
public final class CreateDisplayWorks
{
    public static final String MOD_ID = "create_display_works";

    public CreateDisplayWorks(IEventBus bus, net.neoforged.fml.ModContainer container) {
        ModContent.register(bus);
        com.zmm2025.createdisplayworks.media.MediaNetwork.registerLifecycle();
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER,com.zmm2025.createdisplayworks.media.MediaLimits.SPEC);
        bus.addListener(this::setup);
        bus.addListener(com.zmm2025.createdisplayworks.media.MediaNetwork::register);
    }

    private void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            DisplayTarget.BY_BLOCK.register(ModContent.FLIP_DOT.get(), ModContent.TARGET.get());
            com.simibubi.create.api.stress.BlockStressValues.IMPACTS.register(ModContent.FLIP_DOT.get(), () -> 1);
        });
    }
}
