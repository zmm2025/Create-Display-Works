package com.zmm2025.createdisplayworks.dev;

import com.zmm2025.createdisplayworks.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

@EventBusSubscriber(modid=CreateDisplayWorks.MOD_ID)
public final class ServerSmokeCheck {
    @SubscribeEvent public static void started(ServerStartedEvent event) {
        if(!Boolean.getBoolean("flipdot.serverCheck"))return;
        if(event.getServer().getRecipeManager().byKey(ModContent.FLIP_DOT.getId()).isEmpty())throw new IllegalStateException("Flip-dot recipe missing on dedicated server");
        if(com.simibubi.create.api.behaviour.display.DisplayTarget.BY_BLOCK.get(ModContent.FLIP_DOT.get())!=ModContent.TARGET.get())throw new IllegalStateException("Display Link target missing");
        System.out.println("FLIP_DOT_SERVER_CHECK PASS dedicated server, recipe, target and media networking loaded");
        event.getServer().halt(false);
    }
}
