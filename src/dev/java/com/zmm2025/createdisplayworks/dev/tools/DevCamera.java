package com.zmm2025.createdisplayworks.dev.tools;

import com.google.gson.JsonObject;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Moves the player to view a point, so agents get a usable view in one call. Keeps the player flying, because creative
 * flight switches off whenever the player is on the ground, and a player who isn't flying falls after a teleport.
 */
final class DevCamera {
    private DevCamera() {}

    /**
     * Looks at {@code x y z} (block coordinates aim at the block's center; decimals aim at that exact point) from
     * {@code distance} blocks off its {@code face}, by default the side the player is on. {@code fit=W} or
     * {@code fit=WxH} picks the distance that fits that many blocks across the view instead.
     */
    static JsonObject camera(Map<String, String> query) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSingleplayerServer() == null || minecraft.player == null)
            return DevApi.failure("Not in a single-player world; on a server, use /command and /look.");
        Vec3 target = DevApi.point(query);
        Direction face;
        if (query.containsKey("face")) {
            face = Direction.byName(query.get("face"));
            if (face == null) return DevApi.failure("Unknown face; use up, down, north, south, east or west.");
        } else {
            Vec3 offset = minecraft.player.position().subtract(target);
            face = Direction.getNearest(offset.x, 0, offset.z);
        }
        double distance = query.containsKey("fit")
                ? fitDistance(query.get("fit"), face, minecraft)
                : Double.parseDouble(query.getOrDefault("distance", "3"));
        Vec3 normal = Vec3.atLowerCornerOf(face.getNormal());
        Vec3 eye = target.add(normal.scale(0.5 + distance));
        Vec3 look = target.subtract(eye);
        float yaw = face.getAxis() == Direction.Axis.Y
                ? minecraft.player.getYRot()
                : (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(look.y, Math.sqrt(look.x * look.x + look.z * look.z)));
        Vec3 feet = eye.subtract(0, minecraft.player.getEyeHeight(), 0);

        DevTools.onServer((server, player) -> {
            keepFlying(player);
            player.teleportTo(player.serverLevel(), feet.x, feet.y, feet.z, yaw, pitch);
            return null;
        });
        // Wait for the client to apply the teleport, so a screenshot taken next shows the new view.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline
                && minecraft
                        .submit(() -> minecraft.player.position().distanceTo(feet) > 0.05)
                        .get(1, TimeUnit.MINUTES)) Thread.sleep(10);

        JsonObject response = query.containsKey("screenshot") && !"false".equals(query.get("screenshot"))
                ? DevTools.screenshot(!"false".equals(query.get("hud")), 1, 0, null, query.get("keep"), false)
                : DevApi.ok();
        response.addProperty("face", face.getName());
        response.addProperty("distance", DevApi.round(distance));
        response.addProperty("eye", DevApi.position(eye));
        response.addProperty("yaw", DevApi.round(yaw));
        response.addProperty("pitch", DevApi.round(pitch));
        return response;
    }

    /** Turns flight on for a player who can fly, so a teleport leaves them hovering. Runs on the server thread. */
    static void keepFlying(ServerPlayer player) {
        if (!player.getAbilities().mayfly || player.getAbilities().flying) return;
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
    }

    /** The distance from the face at which {@code W} or {@code WxH} blocks fill the view, plus a small margin. */
    private static double fitDistance(String fit, Direction face, Minecraft minecraft) {
        String[] parts = fit.toLowerCase().split("x");
        double width = Double.parseDouble(parts[0]), height = parts.length > 1 ? Double.parseDouble(parts[1]) : width;
        double verticalFov = Math.toRadians(minecraft.options.fov().get());
        double aspect = (double) minecraft.getWindow().getWidth()
                / minecraft.getWindow().getHeight();
        double horizontalFov = 2 * Math.atan(Math.tan(verticalFov / 2) * aspect);
        double distance = Math.max(width / 2 / Math.tan(horizontalFov / 2), height / 2 / Math.tan(verticalFov / 2));
        return distance * 1.1;
    }
}
