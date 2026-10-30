package com.zmm2025.createdisplayworks.flipdot;

import java.util.Arrays;

/** Angles are measured from the front face. Time is fractional Minecraft ticks. */
public final class DiscMotion {
    private final float[] angles = new float[64];
    private long target;
    private double anchor;
    private float rpm;
    private float travel;
    private int version;
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
        changed();
    }
    public int moving(double time) {
        int count = 0;
        for (int i = 0; i < 64; i++) if (Math.abs(angle(i, time) - ((target & (1L << i)) == 0 ? 180 : 0)) > .001) count++;
        return count;
    }
    public float speed() { return rpm; }
    public double anchor() { return anchor; }
    /** Moves the start of the current motion to {@code time}, keeping its starting angles. */
    public void reanchor(double time) { anchor = time; version++; }
    /** True once no disc can move again until the motion changes, so renderers can keep the last angles they drew. */
    public boolean settled(double time) { return rpm == 0 || travel == 0 || time >= anchor + travel * durationTicks(rpm) / 180; }
    /** Changes whenever the motion is retargeted, restored or reanchored. */
    public int version() { return version; }
    private void changed() {
        travel = 0;
        for (int i = 0; i < 64; i++) travel = Math.max(travel, Math.abs(((target & (1L << i)) == 0 ? 180 : 0) - angles[i]));
        version++;
    }
    public float[] snapshot(double time) {
        float[] result = new float[64];
        for (int i = 0; i < 64; i++) result[i] = angle(i, time);
        return result;
    }
    public void restore(float[] values, long bits, float speed, double time) {
        for (int i = 0; i < 64; i++) angles[i] = Float.isFinite(values[i]) ? Math.clamp(values[i], 0, 180) : 180;
        target = bits; rpm = Float.isFinite(speed) ? Math.abs(speed) : 0; anchor = time;
        changed();
    }
}
