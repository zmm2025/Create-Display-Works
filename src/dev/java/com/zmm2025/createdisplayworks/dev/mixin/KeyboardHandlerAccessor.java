package com.zmm2025.createdisplayworks.dev.mixin;

import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets the control API type the way GLFW's character callback does, so mods that hook screen typing see it. */
@Mixin(KeyboardHandler.class)
public interface KeyboardHandlerAccessor {
    @Invoker("charTyped")
    void createDisplayWorksDev$charTyped(long window, int codePoint, int modifiers);
}
