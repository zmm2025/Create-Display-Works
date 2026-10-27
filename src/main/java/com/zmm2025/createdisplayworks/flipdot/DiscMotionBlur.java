package com.zmm2025.createdisplayworks.flipdot;

import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * Experimental motion blur: moving discs leave fading translucent copies at the angles they passed through during the
 * last {@link #SHUTTER_TICKS}. Copies skip the sub-pixel edge faces, and boards beyond {@link #MAX_DISTANCE} blocks get no
 * blur. Toggled by {@code motionBlur} in the client config. To remove it, delete this class, the one
 * line in {@link FlipDotRenderer} that calls it, and the option in {@link com.zmm2025.createdisplayworks.ClientConfig}.
 */
final class DiscMotionBlur {
    private static final float SHUTTER_TICKS = .3f;
    private static final int GHOSTS = 4;
    private static final int PEAK_ALPHA = 110;
    private static final double MAX_DISTANCE = 24;
    private DiscMotionBlur() {}
    static boolean enabled() { return com.zmm2025.createdisplayworks.ClientConfig.MOTION_BLUR.get(); }
    static void render(FlipDotRenderer renderer, FlipDotBlockEntity be, float partial, PoseStack pose, VertexConsumer vertices, int light, int overlay) {
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        if (camera.distanceToSqr(Vec3.atCenterOf(be.getBlockPos())) > MAX_DISTANCE * MAX_DISTANCE) return;
        if (be.movingDiscs(partial - SHUTTER_TICKS) == 0) return;
        for (int row = 0; row < 8; row++) for (int col = 0; col < 8; col++) {
            int index = row * 8 + col;
            float now = be.angle(index, partial);
            if (Math.abs(now - be.angle(index, partial - SHUTTER_TICKS)) < 1) continue;
            for (int ghost = 1; ghost <= GHOSTS; ghost++) {
                float angle = be.angle(index, partial - SHUTTER_TICKS * ghost / GHOSTS);
                if (Math.abs(angle - now) < .5f) continue;
                renderer.disc(be, pose, vertices, row, col, angle, PEAK_ALPHA * (GHOSTS + 1 - ghost) / (GHOSTS + 1), 2, light, overlay);
            }
        }
    }
}
