package com.zmm2025.createdisplayworks;

import static com.zmm2025.createdisplayworks.CreateDisplayWorks.REGISTRATE;

import com.tterrag.registrate.util.entry.BlockEntityEntry;
import com.zmm2025.createdisplayworks.flipdot.FlipDotBlockEntity;
import com.zmm2025.createdisplayworks.flipdot.FlipDotRenderer;
import com.zmm2025.createdisplayworks.flipdot.FlipDotVisual;

public final class ModBlockEntities {
    // With Flywheel active, the visual replaces FlipDotRenderer entirely.
    public static final BlockEntityEntry<FlipDotBlockEntity> FLIP_DOT_DISPLAY = REGISTRATE
            .blockEntity("flip_dot_display", FlipDotBlockEntity::new)
            .visual(() -> FlipDotVisual::new, false)
            .renderer(() -> FlipDotRenderer::new)
            .validBlocks(ModBlocks.FLIP_DOT_DISPLAY)
            .register();

    public static void register() {}
}
