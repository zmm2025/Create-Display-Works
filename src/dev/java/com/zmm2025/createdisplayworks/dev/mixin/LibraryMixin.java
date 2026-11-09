package com.zmm2025.createdisplayworks.dev.mixin;

import com.mojang.blaze3d.audio.Library;
import com.zmm2025.createdisplayworks.dev.tools.DevAudio;
import java.nio.IntBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Gives a hidden game a silent sound device instead of the computer's speakers. See {@link DevAudio}. */
@Mixin(Library.class)
abstract class LibraryMixin {
    @Shadow
    private long currentDevice;

    @Inject(method = "openDeviceOrFallback", at = @At("HEAD"), cancellable = true)
    private static void createDisplayWorksDev$openSilentDevice(String specifier, CallbackInfoReturnable<Long> info) {
        long device = DevAudio.openDevice();
        if (device != 0) info.setReturnValue(device);
    }

    /** Head tracking would reset the device, which a silent device can't do without its output format. */
    @Inject(method = "setHrtf", at = @At("HEAD"), cancellable = true)
    private void createDisplayWorksDev$skipHrtf(boolean enable, CallbackInfo info) {
        if (DevAudio.isSilent(currentDevice)) info.cancel();
    }

    @ModifyArg(
            method = "init",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/ALC10;alcCreateContext(JLjava/nio/IntBuffer;)J"),
            index = 1)
    private IntBuffer createDisplayWorksDev$contextAttributes(IntBuffer attributes) {
        return DevAudio.isSilent(currentDevice) ? DevAudio.contextAttributes() : attributes;
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void createDisplayWorksDev$startRendering(CallbackInfo info) {
        if (DevAudio.isSilent(currentDevice)) DevAudio.startRendering();
    }

    @Inject(method = "cleanup", at = @At("HEAD"))
    private void createDisplayWorksDev$stopRendering(CallbackInfo info) {
        if (DevAudio.isSilent(currentDevice)) DevAudio.stopRendering();
    }
}
