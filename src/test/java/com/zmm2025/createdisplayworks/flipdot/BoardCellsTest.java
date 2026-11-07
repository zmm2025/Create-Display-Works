package com.zmm2025.createdisplayworks.flipdot;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.zmm2025.createdisplayworks.flipdot.BoardCells.*;

class BoardCellsTest {
    private static final int[] SQUARE = {pack(0, 0), pack(1, 0), pack(0, 1), pack(1, 1)};

    @Test void cellsPackNegativeColumnsAndRows() {
        for (int column = -31; column <= 31; column++) for (int row = -31; row <= 31; row++) {
            int cell = pack(column, row);
            assertEquals(column, column(cell));
            assertEquals(row, row(cell));
            assertEquals(pack(-column, -row), inverse(cell));
            assertTrue(valid(cell));
        }
        assertEquals(ORIGIN, pack(0, 0));
        assertFalse(valid(NONE));
        assertFalse(valid(pack(32, 0)));
        assertFalse(valid(pack(0, -32)));
    }

    @Test void turnsAndFlipsAreNoticed() {
        int[] wide = {pack(0, 0), pack(1, 0), pack(2, 0), pack(0, 1), pack(1, 1), pack(2, 1)};
        int[] tall = {pack(1, 0), pack(1, 1), pack(1, 2), pack(0, 0), pack(0, 1), pack(0, 2)};
        assertTrue(turned(wide, tall, wide));
        // A quarter turn, half a turn and a mirror image of a square board.
        assertTrue(turned(SQUARE, new int[]{pack(1, 0), pack(1, 1), pack(0, 0), pack(0, 1)}, SQUARE));
        assertTrue(turned(SQUARE, new int[]{pack(1, 1), pack(0, 1), pack(1, 0), pack(0, 0)}, SQUARE));
        assertTrue(turned(SQUARE, new int[]{pack(1, 0), pack(0, 0), pack(1, 1), pack(0, 1)}, SQUARE));
    }

    @Test void unmovedOrChangedBoardsDontCount() {
        assertFalse(turned(SQUARE, SQUARE, SQUARE));
        // A new tile that hasn't joined a board yet.
        assertFalse(turned(new int[]{pack(0, 0), NONE}, new int[]{pack(1, 0), pack(0, 0)}, new int[]{pack(0, 0), pack(1, 0)}));
        // Two one-tile boards that were joined both remember being a controller.
        assertFalse(turned(new int[]{ORIGIN, ORIGIN}, new int[]{pack(0, 0), pack(1, 0)}, new int[]{ORIGIN}));
        // A tile went missing, so the controller's list doesn't match.
        assertFalse(turned(new int[]{pack(0, 0), pack(1, 0), pack(0, 1)}, new int[]{pack(1, 1), pack(0, 1), pack(1, 0)}, SQUARE));
    }
}
