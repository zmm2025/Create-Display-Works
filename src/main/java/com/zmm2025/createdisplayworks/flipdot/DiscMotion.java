package com.zmm2025.createdisplayworks.flipdot;

import java.util.Arrays;

/** Angles are measured from the front face. Time is fractional Minecraft ticks. */
public final class DiscMotion {
    private final float[] angles = new float[64];
    private long target;
    private double anchor;
    private float rpm;
    public DiscMotion() { Arrays.fill(angles, 180); }
    public static double durationTicks(float rpm) { return rpm == 0 ? Double.POSITIVE_INFINITY : 256.0 / Math.abs(rpm); }
    public float angle(int index, double time) {
        float end = (target & (1L << index)) == 0 ? 180 : 0;
        double distance = Math.max(0, time - anchor) * 180 / durationTicks(rpm);
        return (float) (angles[index] + Math.copySign(Math.min(Math.abs(end - angles[index]), distance), end - angles[index]));
    }
    public void retarget(long bits, float speed, double time) {
        for (int i = 0; i < 64; i++) angles[i] = angle(i, time);
        target = bits; rpm = Math.abs(speed); anchor = time;
    }
    public int moving(double time) {
        int count = 0;
        for (int i = 0; i < 64; i++) if (Math.abs(angle(i, time) - ((target & (1L << i)) == 0 ? 180 : 0)) > .001) count++;
        return count;
    }
    public float speed() { return rpm; }
    public float[] snapshot(double time) {
        float[] result = new float[64];
        for (int i = 0; i < 64; i++) result[i] = angle(i, time);
        return result;
    }
    public void restore(float[] values, long bits, float speed, double time) {
        for (int i = 0; i < 64; i++) angles[i] = Float.isFinite(values[i]) ? Math.clamp(values[i], 0, 180) : 180;
        target = bits; rpm = Float.isFinite(speed) ? Math.abs(speed) : 0; anchor = time;
    }
}
