package com.zmm2025.createdisplayworks.flipdot;

import java.util.Arrays;

/**
 * A tile's place on its board, relative to the controller: columns to the right and rows down, as seen from the front.
 * Unlike block positions, cells stay the same when the whole board is moved or turned about the vertical axis, so
 * contraptions, copies and pastes keep their links.
 */
public final class BoardCells {
    /** No known cell, such as for a tile that hasn't joined a board yet. */
    public static final int NONE = Integer.MIN_VALUE;
    /** The controller's own cell. */
    public static final int ORIGIN = 0;
    private BoardCells() {}
    public static int pack(int column, int row) { return column << 16 | row & 0xFFFF; }
    public static int column(int cell) { return cell >> 16; }
    public static int row(int cell) { return (short)cell; }
    /** The cell of the controller as seen from a tile at {@code cell}. */
    public static int inverse(int cell) { return pack(-column(cell), -row(cell)); }
    /** Whether a cell is within the largest board, so damaged or unrelated data isn't trusted. */
    public static boolean valid(int cell) {
        return cell != NONE && Math.abs(column(cell)) < FlipDotAssembly.MAX_SPAN && Math.abs(row(cell)) < FlipDotAssembly.MAX_SPAN;
    }
    /**
     * Whether the tiles of one earlier board now sit in different cells, because the whole board was turned or flipped.
     * {@code previous} holds each tile's cell before and {@code current} its cell now, in the same order.
     * {@code previousShape} is the old controller's list of its board's cells. Tiles that were added, removed or came
     * from two boards don't count.
     */
    public static boolean turned(int[] previous, int[] current, int[] previousShape) {
        if (previous.length != current.length || previous.length != previousShape.length) return false;
        if (Arrays.equals(previous, current)) return false;
        int[] before = previous.clone(), shape = previousShape.clone();
        Arrays.sort(before); Arrays.sort(shape);
        if (!Arrays.equals(before, shape)) return false;
        for (int i = 1; i < before.length; i++) if (before[i] == before[i - 1]) return false;
        return true;
    }
}
