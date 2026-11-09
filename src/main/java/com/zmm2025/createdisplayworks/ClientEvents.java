package com.zmm2025.createdisplayworks;

import com.zmm2025.createdisplayworks.flipdot.FlipDotBlockEntity;
import com.zmm2025.createdisplayworks.media.MediaScreen;
import com.zmm2025.createdisplayworks.ponder.DisplayWorksPonderPlugin;
import net.createmod.ponder.foundation.PonderIndex;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
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
        ModList.get()
                .getModContainerById(CreateDisplayWorks.MOD_ID)
                .ifPresent(mod -> mod.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new));
        PonderIndex.addPlugin(new DisplayWorksPonderPlugin());
    }
    // Renderers only see boards they draw, so flips that arrived for hidden boards start here.
    @SubscribeEvent
    public static void frame(RenderFrameEvent.Pre event) {
        FlipDotBlockEntity.startPendingClientMotion(event.getPartialTick().getGameTimeDeltaPartialTick(false));
    }
}
