package com.zmm2025.createdisplayworks.dev.mixin;

import com.zmm2025.createdisplayworks.dev.media.MediaPacketCommands;
import com.zmm2025.createdisplayworks.media.MediaNetwork;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Shows {@link MediaPacketCommands} what the server answers, before the media screen handles it as usual. */
@Mixin(targets = "com.zmm2025.createdisplayworks.media.MediaClientHandler")
abstract class MediaClientHandlerMixin {
    @Inject(method = "status", at = @At("HEAD"))
    private static void createDisplayWorksDev$logStatus(MediaNetwork.Status status, CallbackInfo info) {
        MediaPacketCommands.received(status);
    }

    @Inject(method = "editorPart", at = @At("HEAD"))
    private static void createDisplayWorksDev$logEditorPart(MediaNetwork.Part part, CallbackInfo info) {
        MediaPacketCommands.received(part);
    }
}
