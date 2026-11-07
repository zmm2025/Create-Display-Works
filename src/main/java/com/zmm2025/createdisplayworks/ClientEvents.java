package com.zmm2025.createdisplayworks;

import com.zmm2025.createdisplayworks.flipdot.DiscRattle;
import com.zmm2025.createdisplayworks.flipdot.FlipDotBlockEntity;
import com.zmm2025.createdisplayworks.flipdot.FlipDotRenderer;
import com.zmm2025.createdisplayworks.flipdot.FlipDotVisual;
import com.zmm2025.createdisplayworks.media.MediaNetwork;
import com.zmm2025.createdisplayworks.media.MediaScreen;
import com.zmm2025.createdisplayworks.ponder.DisplayWorksPonderPlugin;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import net.createmod.ponder.foundation.PonderIndex;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@EventBusSubscriber(modid = CreateDisplayWorks.MOD_ID, value = Dist.CLIENT)
public final class ClientEvents {
    @SubscribeEvent
    public static void models(ModelEvent.RegisterAdditional event) {
        event.register(MediaScreen.PREVIEW_MODEL);
    }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        FlipDotBlockEntity.clientTick = DiscRattle::update;
        // With Flywheel active, the visual replaces FlipDotRenderer entirely.
        event.enqueueWork(() -> SimpleBlockEntityVisualizer.builder(ModContent.FLIP_DOT_ENTITY.get())
                .factory(FlipDotVisual::new)
                .apply());
        ModList.get()
                .getModContainerById(CreateDisplayWorks.MOD_ID)
                .ifPresent(mod -> mod.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new));
        PonderIndex.addPlugin(new DisplayWorksPonderPlugin());
        MediaNetwork.openScreen = data -> Minecraft.getInstance()
                .setScreen(new MediaScreen(
                        data.pos(),
                        data.columns(),
                        data.rows(),
                        data.filename(),
                        data.thumbnail(),
                        data.frames(),
                        data.playing(),
                        data.loop()));
        MediaNetwork.receiveEditor = data -> {
            if (Minecraft.getInstance().screen instanceof MediaScreen screen) screen.receiveEditor(data);
        };
        MediaNetwork.updateScreen = data -> {
            if (Minecraft.getInstance().screen instanceof MediaScreen screen) screen.updateStatus(data);
        };
    }
    // Renderers only see boards they draw, so flips that arrived for hidden boards start here.
    @SubscribeEvent
    public static void frame(RenderFrameEvent.Pre event) {
        FlipDotBlockEntity.startPendingClientMotion(event.getPartialTick().getGameTimeDeltaPartialTick(false));
    }

    @SubscribeEvent
    public static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModContent.FLIP_DOT_ENTITY.get(), FlipDotRenderer::new);
    }
}
