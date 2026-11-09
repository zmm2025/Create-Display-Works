package com.zmm2025.createdisplayworks;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Per-player visual settings, editable from the Mods menu. */
public final class ClientConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue MOTION_BLUR, MEDIA_TOOLS;

    static {
        var builder = new ModConfigSpec.Builder();
        MOTION_BLUR = builder.comment(
                        "Experimental: flipping discs leave fading copies along their path, which makes fast flips easier to follow.")
                .define("motionBlur", true);
        MEDIA_TOOLS = builder.comment(
                        "Lets media import download and run ffmpeg (about 30 MB, used for almost every import), and yt-dlp with Deno (60 to 80 MB more, only for YouTube links). They're saved in create_display_works/media-cache in the game folder. YouTube imports also check a version list in this mod's GitHub repository, and offer to update yt-dlp when a newer one is listed. Turn this off to stop the mod from downloading or running other programs. Importing then only works for animated WebP files.")
                .define("mediaTools", true);
        SPEC = builder.build();
    }

    private ClientConfig() {}
}
