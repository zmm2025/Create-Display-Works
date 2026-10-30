package com.zmm2025.createdisplayworks;

import com.zmm2025.createdisplayworks.flipdot.FlipDotRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid=CreateDisplayWorks.MOD_ID, value=Dist.CLIENT)
public final class ClientEvents {
    @SubscribeEvent public static void models(net.neoforged.neoforge.client.event.ModelEvent.RegisterAdditional event) {
        event.register(com.zmm2025.createdisplayworks.media.MediaScreen.PREVIEW_MODEL);
    }
    @SubscribeEvent public static void setup(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent event) {
        com.zmm2025.createdisplayworks.flipdot.FlipDotBlockEntity.clientTick=com.zmm2025.createdisplayworks.flipdot.DiscRattle::update;
        // With Flywheel active, the visual replaces FlipDotRenderer entirely.
        event.enqueueWork(() -> dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer.builder(ModContent.FLIP_DOT_ENTITY.get())
            .factory(com.zmm2025.createdisplayworks.flipdot.FlipDotVisual::new).apply());
        net.neoforged.fml.ModList.get().getModContainerById(CreateDisplayWorks.MOD_ID).ifPresent(mod->mod.registerExtensionPoint(
            net.neoforged.neoforge.client.gui.IConfigScreenFactory.class,net.neoforged.neoforge.client.gui.ConfigurationScreen::new));
        net.createmod.ponder.foundation.PonderIndex.addPlugin(new com.zmm2025.createdisplayworks.ponder.DisplayWorksPonderPlugin());
        com.zmm2025.createdisplayworks.media.MediaNetwork.openScreen = data -> net.minecraft.client.Minecraft.getInstance().setScreen(
            new com.zmm2025.createdisplayworks.media.MediaScreen(data.pos(),data.columns(),data.rows(),data.filename(),data.thumbnail(),data.frames(),data.playing(),data.loop()));
        com.zmm2025.createdisplayworks.media.MediaNetwork.receiveEditor = data -> {
            if(net.minecraft.client.Minecraft.getInstance().screen instanceof com.zmm2025.createdisplayworks.media.MediaScreen screen)screen.receiveEditor(data);
        };
        com.zmm2025.createdisplayworks.media.MediaNetwork.updateScreen = data -> {
            if(net.minecraft.client.Minecraft.getInstance().screen instanceof com.zmm2025.createdisplayworks.media.MediaScreen screen)screen.updateStatus(data);
        };
    }
    @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModContent.FLIP_DOT_ENTITY.get(), FlipDotRenderer::new);
    }
}
