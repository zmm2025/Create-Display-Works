package com.zmm2025.createdisplayworks.dev.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the control API read the action bar, where many mods, including Create, report what a click did. */
@Mixin(Gui.class)
public interface GuiAccessor {
    @Accessor("overlayMessageString")
    Component createDisplayWorksDev$getOverlayMessage();

    @Accessor("overlayMessageTime")
    int createDisplayWorksDev$getOverlayMessageTime();
}
