package com.zmm2025.createdisplayworks;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Per-player visual settings, editable from the Mods menu. */
public final class ClientConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue MOTION_BLUR;
    static {
        var builder=new ModConfigSpec.Builder();
        MOTION_BLUR=builder.comment("Experimental: flipping discs leave fading copies along their path, which makes fast flips easier to follow.").define("motionBlur",true);
        SPEC=builder.build();
    }
    private ClientConfig() {}
}
