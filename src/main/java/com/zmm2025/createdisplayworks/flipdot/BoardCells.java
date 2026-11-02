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
     * Finds how a board's tiles were rearranged when the whole board was turned, flipped or mirrored. {@code previous}
     * holds each tile's cell before and {@code current} its cell now, in the same order. {@code previousShape} is the
     * old controller's list of its board's cells.
     * <p>
     * Returns, for each tile, the index of the tile that used to sit in its cell, so content can follow the cells.
     * Returns null when nothing moved, or when the tiles aren't exactly one earlier board, for example because tiles
     * were added, removed or came from two boards.
     */
    public static int[] rearrangement(int[] previous, int[] current, int[] previousShape) {
        if (previous.length != current.length || previous.length != previousShape.length) return null;
        if (Arrays.equals(previous, current)) return null;
        int[] before = previous.clone(), after = current.clone(), shape = previousShape.clone();
        Arrays.sort(before); Arrays.sort(after); Arrays.sort(shape);
        if (!Arrays.equals(before, after) || !Arrays.equals(before, shape)) return null;
        for (int i = 1; i < before.length; i++) if (before[i] == before[i - 1]) return null;
        int[] source = new int[current.length];
        for (int i = 0; i < current.length; i++) {
            int index = -1;
            for (int j = 0; j < previous.length && index < 0; j++) if (previous[j] == current[i]) index = j;
            source[i] = index;
        }
        return source;
    }
}
