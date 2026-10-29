package com.zmm2025.createdisplayworks.dev.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the control API move the cursor that screens hover with, which a hidden window never updates itself. */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
    @Accessor("xpos")
    void createDisplayWorksDev$setXpos(double x);

    @Accessor("ypos")
    void createDisplayWorksDev$setYpos(double y);
}
