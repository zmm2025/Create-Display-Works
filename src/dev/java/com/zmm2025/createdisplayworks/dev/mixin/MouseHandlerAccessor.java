package com.zmm2025.createdisplayworks.dev.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets the control API drive the mouse the way GLFW's callbacks do, which a hidden window never receives. */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {
    @Accessor("xpos")
    void createDisplayWorksDev$setXpos(double x);

    @Accessor("ypos")
    void createDisplayWorksDev$setYpos(double y);

    @Accessor("mouseGrabbed")
    void createDisplayWorksDev$setMouseGrabbed(boolean grabbed);

    @Accessor("activeButton")
    int createDisplayWorksDev$getActiveButton();

    @Invoker("onPress")
    void createDisplayWorksDev$onPress(long window, int button, int action, int modifiers);

    @Invoker("onScroll")
    void createDisplayWorksDev$onScroll(long window, double xOffset, double yOffset);
}
