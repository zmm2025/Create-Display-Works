package com.zmm2025.createdisplayworks.media;

import java.util.*;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class MediaLimits {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue PLAYING_BLOCKS, UPLOAD_MIB, UNUSED_MIB;
    private static final Map<MediaPlayback, Integer> PLAYING = new WeakHashMap<>();

    static {
        var builder = new ModConfigSpec.Builder();
        PLAYING_BLOCKS = builder.comment(
                        "Maximum display blocks playing animated media simultaneously across all dimensions. Static images and text are unaffected.")
                .defineInRange("playingDisplayBlocks", 4096, 256, 65536);
        UPLOAD_MIB = builder.comment("Combined memory reserved for unfinished media uploads, in MiB.")
                .defineInRange("uploadMemoryMiB", 32, 2, 1024);
        UNUSED_MIB = builder.comment(
                        "Disk space for media that no placed board is showing, in MiB. Keeping it lets a board that was picked up or copied get its picture back when it's placed again. When this fills up, the media unused for longest is deleted first.")
                .defineInRange("unusedMediaMiB", 256, 0, 1048576);
        SPEC = builder.build();
    }

    /** Counts {@code blocks} display blocks as playing for {@code playback}, if that stays within the limit. */
    public static boolean acquire(MediaPlayback playback, int blocks) {
        var server = playback.owner().getLevel().getServer();
        PLAYING.keySet().removeIf(other -> {
            var owner = other.owner();
            return owner.isRemoved()
                    || owner.getLevel() == null
                    || owner.getLevel().getServer() != server
                    || !other.isPlaying();
        });
        int total = PLAYING.entrySet().stream()
                .filter(e -> e.getKey() != playback)
                .mapToInt(Map.Entry::getValue)
                .sum();
        if (total + blocks > PLAYING_BLOCKS.get()) return false;
        PLAYING.put(playback, blocks);
        return true;
    }

    public static void release(MediaPlayback playback) {
        PLAYING.remove(playback);
    }
}
