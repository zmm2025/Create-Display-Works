package com.zmm2025.createdisplayworks.flipdot;

import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Experimental motion blur: moving discs leave fading translucent copies at the angles they passed through during the
 * last {@link #SHUTTER_TICKS}. Copies skip the sub-pixel edge faces, and boards beyond {@link #MAX_DISTANCE} blocks get no
 * blur. Toggled by {@code motionBlur} in the client config. To remove it, delete this class, the lines in
 * {@link FlipDotRenderer} and {@link FlipDotVisual} that call it, and the option in
 * {@link com.zmm2025.createdisplayworks.ClientConfig}.
 */
final class DiscMotionBlur {
    private static final float SHUTTER_TICKS = .3f;
    static final int GHOSTS = 4;
    private static final int PEAK_ALPHA = 110;
    private static final double MAX_DISTANCE = 24;
    private DiscMotionBlur() {}
    /** True when the board is close enough and has discs that moved within the shutter time. */
    static boolean active(FlipDotBlockEntity be, float partial) {
        if (!com.zmm2025.createdisplayworks.ClientConfig.MOTION_BLUR.get() || be.discsSettled(partial - SHUTTER_TICKS)) return false;
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        return camera.distanceToSqr(Vec3.atCenterOf(be.getBlockPos())) <= MAX_DISTANCE * MAX_DISTANCE;
    }
    interface Ghost { void accept(int index, float angle, int alpha); }
    /** Lists the copies to draw: up to {@link #GHOSTS} per moving disc, each with the disc index, angle and alpha. */
    static void ghosts(FlipDotBlockEntity be, float partial, Ghost out) {
        for (int index = 0; index < 64; index++) {
            float now = be.angle(index, partial);
            if (Math.abs(now - be.angle(index, partial - SHUTTER_TICKS)) < 1) continue;
            for (int ghost = 1; ghost <= GHOSTS; ghost++) {
                float angle = be.angle(index, partial - SHUTTER_TICKS * ghost / GHOSTS);
                if (Math.abs(angle - now) < .5f) continue;
                out.accept(index, angle, PEAK_ALPHA * (GHOSTS + 1 - ghost) / (GHOSTS + 1));
            }
        }
    }
    static void render(FlipDotRenderer renderer, FlipDotBlockEntity be, float partial, PoseStack pose, VertexConsumer vertices, int light, int overlay) {
        ghosts(be, partial, (index, angle, alpha) -> renderer.disc(be, pose, vertices, index / 8, index % 8, angle, alpha, 2, light, overlay));
    }
}
