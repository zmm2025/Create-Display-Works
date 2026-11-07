package com.zmm2025.createdisplayworks.media;

import net.minecraft.client.Minecraft;

/**
 * The client's side of {@link MediaNetwork}: it opens and updates the media screen. Only clients load this class, since
 * {@link MediaNetwork#register} calls it from lambdas that run when a packet arrives.
 */
final class MediaClientHandler {
    private MediaClientHandler() {}

    static void open(MediaNetwork.Open data) {
        Minecraft.getInstance()
                .setScreen(new MediaScreen(
                        data.pos(),
                        data.columns(),
                        data.rows(),
                        data.filename(),
                        data.thumbnail(),
                        data.frames(),
                        data.playing(),
                        data.loop()));
    }

    static void status(MediaNetwork.Status data) {
        if (Minecraft.getInstance().screen instanceof MediaScreen screen) screen.updateStatus(data);
    }
    /** A part of the editor transfer, which sends the open display's archive with its editable source. */
    static void editorPart(MediaNetwork.Part data) {
        if (Minecraft.getInstance().screen instanceof MediaScreen screen) screen.receiveEditor(data);
    }
}
