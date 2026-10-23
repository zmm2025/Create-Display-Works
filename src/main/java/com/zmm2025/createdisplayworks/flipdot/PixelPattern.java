package com.zmm2025.createdisplayworks.flipdot;

import java.util.List;

/** Clipboard drawings use # for a light dot and . or a space for a dark dot. */
public final class PixelPattern {
    private PixelPattern() {}
    public static long[] parse(List<String> lines, int columns, int rows) {
        if (columns < 1 || rows < 1 || columns > 32 || rows > 32 || columns * rows > 256
            || lines.isEmpty() || lines.size() > rows * 8) return null;
        long[] result = new long[columns * rows];
        for (int y=0; y<lines.size(); y++) {
            String line=lines.get(y);
            if (line.length() > columns * 8) return null;
            for (int x=0; x<line.length(); x++) {
                char pixel=line.charAt(x);
                if (pixel != '#' && pixel != '.' && pixel != ' ') return null;
                if (pixel == '#') result[y / 8 * columns + x / 8] |= 1L << (y % 8 * 8 + x % 8);
            }
        }
        return result;
    }
}
