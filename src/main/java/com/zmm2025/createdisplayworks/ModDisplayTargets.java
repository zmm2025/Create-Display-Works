package com.zmm2025.createdisplayworks;

import static com.zmm2025.createdisplayworks.CreateDisplayWorks.MOD_ID;
import static com.zmm2025.createdisplayworks.CreateDisplayWorks.REGISTRATE;

import com.simibubi.create.api.behaviour.display.DisplayTarget;
import com.tterrag.registrate.util.entry.RegistryEntry;
import com.zmm2025.createdisplayworks.flipdot.FlipDotDisplayTarget;

/** What Display Links can write to. A block opts in with {@code .transform(DisplayTarget.displayTarget(...))}. */
public final class ModDisplayTargets {
    public static final RegistryEntry<DisplayTarget, FlipDotDisplayTarget> FLIP_DOT_DISPLAY = REGISTRATE
            .displayTarget("flip_dot_display", FlipDotDisplayTarget::new)
            .lang(target -> "display_target." + MOD_ID + ".flip_dot_display", "Flip-Dot Display")
            .register();

    public static void register() {}
}
