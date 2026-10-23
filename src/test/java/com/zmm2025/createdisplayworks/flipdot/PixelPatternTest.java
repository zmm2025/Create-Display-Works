package com.zmm2025.createdisplayworks.flipdot;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PixelPatternTest {
    @Test void crossesBothTileSeamsWithoutReversingPixels() {
        var lines=new ArrayList<>(Collections.nCopies(9,""));
        lines.set(7,".......##"); lines.set(8,"........#");
        assertArrayEquals(new long[]{Long.MIN_VALUE, 1L<<56, 0, 1}, PixelPattern.parse(lines,2,2));
    }
    @Test void rejectsOversizedOrInvalidDrawings() {
        assertNull(PixelPattern.parse(List.of("........."),1,1));
        assertNull(PixelPattern.parse(List.of("x"),1,1));
        assertNull(PixelPattern.parse(List.of("#"),32,32));
        assertNull(PixelPattern.parse(Collections.nCopies(9,"#"),1,1));
    }
}
