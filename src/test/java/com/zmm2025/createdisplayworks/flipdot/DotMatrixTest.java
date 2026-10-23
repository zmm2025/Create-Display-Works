package com.zmm2025.createdisplayworks.flipdot;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DotMatrixTest {
    @Test void physicalFontAndTileSeams() {
        assertEquals(0, DotMatrix.tile("", 0, 8));
        assertNotEquals(0, DotMatrix.tile("A", 0, 8));
        assertEquals(0, DotMatrix.tile("A", 1, 16));
        assertNotEquals(0, DotMatrix.tile("32", 1, 16));
        assertEquals(DotMatrix.tile("?",0,8),DotMatrix.tile("\uD83D\uDE00",0,8));
        assertEquals(DotMatrix.tile("32",0,16),DotMatrix.tile("32",0,32));
    }
    @Test void motionHasUncappedFractionalTimingAndReversesWithoutSnapping() {
        for(float rpm:new float[]{1,4,16,32,64,128,192,256,-64}) {
            assertEquals(4096/Math.abs(rpm),DiscMotion.durationTicks(rpm)*50,.00001);
        }
        var motion=new DiscMotion();motion.retarget(1,128,0);
        assertEquals(90,motion.angle(0,.32),.001);
        motion.retarget(0,128,.32);
        assertEquals(90,motion.angle(0,.32),.001);
        assertEquals(135,motion.angle(0,.48),.001);
        assertEquals(180,motion.angle(0,.64),.001);
        motion.retarget(1,1,1);float angle=motion.angle(0,2);
        motion.retarget(1,256,2);assertEquals(angle,motion.angle(0,2));
        assertEquals(0,motion.angle(0,2.32),.001);
    }
    @Test void savedMotionResumesWithoutUnloadedCatchUp() {
        var motion=new DiscMotion();motion.retarget(1,1,100);
        float[] saved=motion.snapshot(120);
        motion.restore(saved,1,1,9000);
        assertEquals(saved[0],motion.angle(0,9000));
        assertEquals(0,motion.angle(0,9100));
    }
}
