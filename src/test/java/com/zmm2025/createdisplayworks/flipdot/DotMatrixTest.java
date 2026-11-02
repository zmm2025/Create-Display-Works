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
    @Test void displayLinkTextFoldsToDrawableGlyphs() {
        assertArrayEquals(DotMatrix.glyphs("Cafe Uber naive"), DotMatrix.glyphs("Caf\u00E9 \u00DCber na\u00EFve"));
        assertArrayEquals(DotMatrix.glyphs("\"Hi\" - it's 1/2..."), DotMatrix.glyphs("\u201CHi\u201D \u2013 it\u2019s \u00BD\u2026"));
        assertArrayEquals(DotMatrix.glyphs("Green Bold"), DotMatrix.glyphs("\u00A7aGreen \u00A7lBold\u00A7r"));
        assertArrayEquals(DotMatrix.glyphs("a b"), DotMatrix.glyphs("a\u00A0b"));
        assertArrayEquals(DotMatrix.glyphs("ok"), DotMatrix.glyphs("o\u200Bk"));
        // Variation selectors, skin tones and joined emoji don't add extra unknown characters.
        assertArrayEquals(DotMatrix.glyphs("\u2714"), DotMatrix.glyphs("\u2714\uFE0F"));
        assertArrayEquals(DotMatrix.glyphs("?"), DotMatrix.glyphs("\uD83D\uDC4B\uD83C\uDFFD"));
        assertArrayEquals(DotMatrix.glyphs("?"), DotMatrix.glyphs("\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"));
        assertArrayEquals(DotMatrix.glyphs("??"), DotMatrix.glyphs("\u65E5\uD55C"));
        // Create's progress bars, gauge and degree symbols get their own glyphs.
        long unknown = DotMatrix.tile("?", 0, 8);
        for (String symbol : new String[]{"\u2588", "\u2592", "\u2591", "\u2714", "\u2191", "\u25AA", "\u00B0", "\u03A9", "\u03BC"}) {
            long bits = DotMatrix.tile(symbol, 0, 8);
            assertNotEquals(0, bits, symbol);
            assertNotEquals(unknown, bits, symbol);
        }
        // Unit signs addons send: ohm and micro signs, and Create Big Cannons' ordinal indicator used as a degree sign.
        assertArrayEquals(DotMatrix.glyphs("\u03A9"), DotMatrix.glyphs("\u2126"));
        assertArrayEquals(DotMatrix.glyphs("\u03BC"), DotMatrix.glyphs("\u00B5"));
        assertArrayEquals(DotMatrix.glyphs("12.5\u00B0"), DotMatrix.glyphs("12.5\u00BA"));
        assertNotEquals(DotMatrix.tile("\u2588", 0, 8), DotMatrix.tile("\u2592", 0, 8));
        assertEquals(0b11011111, DotMatrix.tile("\u2588\u2588", 0, 16) & 0xFF, "full blocks are 5 dots wide with a 1-dot gap");
        assertEquals(256, DotMatrix.glyphs("\u00E9".repeat(400)).length);
    }
    @Test void motionHasUncappedFractionalTimingAndReversesWithoutSnapping() {
        for(float rpm:new float[]{1,4,16,32,64,128,192,256,-64}) {
            assertEquals(12800/Math.abs(rpm),DiscMotion.durationTicks(rpm)*50,.00001);
        }
        var motion=new DiscMotion();motion.retarget(1,256,0);
        assertEquals(90,motion.angle(0,.5),.001);
        motion.retarget(0,256,.5);
        assertEquals(90,motion.angle(0,.5),.001);
        assertEquals(135,motion.angle(0,.75),.001);
        assertEquals(180,motion.angle(0,1),.001);
        motion.retarget(1,1,2);float angle=motion.angle(0,3);
        motion.retarget(1,256,3);assertEquals(angle,motion.angle(0,3));
        assertEquals(0,motion.angle(0,4.28),.001);
    }
    @Test void savedMotionResumesWithoutUnloadedCatchUp() {
        var motion=new DiscMotion();motion.retarget(1,1,100);
        float[] saved=motion.snapshot(120);
        motion.restore(saved,1,1,9000);
        assertEquals(saved[0],motion.angle(0,9000));
        assertEquals(0,motion.angle(0,9400));
    }
}
