package com.zmm2025.createdisplayworks.dev.mixin;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Creates the game window hidden when an agent launches the dev client, so it never pops up or takes focus. Only the
 * dev client loads this mixin, and only with NeoForge's loading window turned off, since that window would show itself.
 */
@Mixin(Window.class)
abstract class WindowMixin {
    @Inject(method="<init>", at=@At(value="INVOKE", target="Lorg/lwjgl/glfw/GLFW;glfwDefaultWindowHints()V", shift=At.Shift.AFTER))
    private void createDisplayWorksDev$startHidden(CallbackInfo info) {
        if(Boolean.getBoolean("createdisplayworks.dev.hidden")) GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
    }
}
