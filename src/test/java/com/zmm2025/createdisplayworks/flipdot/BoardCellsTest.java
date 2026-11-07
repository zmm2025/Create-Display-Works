package com.zmm2025.createdisplayworks.flipdot;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.zmm2025.createdisplayworks.flipdot.BoardCells.*;

class BoardCellsTest {
    private static final int[] SQUARE = {pack(0, 0), pack(1, 0), pack(0, 1), pack(1, 1)};

    @Test void quarterTurnsAreToldApartFromFlips() {
        int[] wide = {pack(0, 0), pack(1, 0), pack(2, 0), pack(0, 1), pack(1, 1), pack(2, 1)};
        int[] tall = {pack(1, 0), pack(1, 1), pack(1, 2), pack(0, 0), pack(0, 1), pack(0, 2)};
        assertTrue(quarterTurned(wide, tall, wide));
        assertFalse(quarterTurned(wide, wide, wide));
        assertFalse(quarterTurned(wide, java.util.Arrays.copyOf(tall, 5), wide));
        int[] line = {pack(0, 0), pack(1, 0), pack(2, 0)}, column = {pack(0, 0), pack(0, 1), pack(0, 2)};
        assertTrue(quarterTurned(line, column, line));
        assertTrue(quarterTurned(column, line, column));
        assertTrue(quarterTurned(SQUARE, new int[]{pack(1, 0), pack(1, 1), pack(0, 0), pack(0, 1)}, SQUARE));
        assertFalse(quarterTurned(SQUARE, new int[]{pack(1, 1), pack(0, 1), pack(1, 0), pack(0, 0)}, SQUARE));
        assertFalse(quarterTurned(SQUARE, new int[]{pack(1, 0), pack(0, 0), pack(1, 1), pack(0, 1)}, SQUARE));
    }

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

    @Test void unmovedBoardsNeedNoRearranging() {
        assertNull(rearrangement(SQUARE, SQUARE, SQUARE));
    }

    @Test void upsideDownBoardsSwapOppositeCorners() {
        // Turned 180 degrees in its own plane: each tile now sits in the opposite corner.
        int[] current = {pack(1, 1), pack(0, 1), pack(1, 0), pack(0, 0)};
        assertArrayEquals(new int[]{3, 2, 1, 0}, rearrangement(SQUARE, current, SQUARE));
    }

    @Test void mirroredRowsSwapEnds() {
        int[] row = {pack(0, 0), pack(1, 0), pack(2, 0)};
        int[] mirrored = {pack(2, 0), pack(1, 0), pack(0, 0)};
        assertArrayEquals(new int[]{2, 1, 0}, rearrangement(row, mirrored, row));
    }

    @Test void changedBoardsAreNotRearranged() {
        // A new tile that hasn't joined a board yet.
        assertNull(rearrangement(new int[]{pack(0, 0), NONE}, new int[]{pack(1, 0), pack(0, 0)}, new int[]{pack(0, 0), pack(1, 0)}));
        // Two one-tile boards that were joined both remember being a controller.
        assertNull(rearrangement(new int[]{ORIGIN, ORIGIN}, new int[]{pack(0, 0), pack(1, 0)}, new int[]{ORIGIN}));
        // A tile went missing, so the controller's list doesn't match.
        int[] partial = {pack(1, 1), pack(0, 1), pack(1, 0)};
        assertNull(rearrangement(new int[]{pack(0, 0), pack(1, 0), pack(0, 1)}, partial, SQUARE));
        // An L shape turned upside down no longer has the same cells.
        int[] shape = {pack(0, 0), pack(0, 1), pack(1, 1)};
        assertNull(rearrangement(shape, new int[]{pack(1, 1), pack(1, 0), pack(0, 0)}, shape));
    }
}
