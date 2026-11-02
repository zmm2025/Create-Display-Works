package com.zmm2025.createdisplayworks.flipdot;

import java.io.*;
import java.text.Normalizer;
import java.util.*;

/** Fixed physical pixels: no client font dependency on a dedicated server. */
public final class DotMatrix {
    private static final int ASCII_GLYPHS = 95, UNKNOWN = '?' - 32;
    /** Symbols Display Link sources send, drawn in the same 8-row cells as the ASCII font. Bit x of a row is column x; an eighth row is a descender. */
    private static final Object[][] SYMBOLS = {
        {0x2588, "#####", "#####", "#####", "#####", "#####", "#####", "#####"}, // full block: filled progress bar cell
        {0x2593, "#####", "#.#.#", "#####", ".#.#.", "#####", "#.#.#", "#####"}, // dark shade
        {0x2592, "#####", "#...#", "#...#", "#...#", "#...#", "#...#", "#####"}, // medium shade: empty progress bar cell
        {0x2591, ".....", ".....", ".....", ".....", ".....", ".....", "#####"}, // light shade
        {0x25A0, ".....", "#####", "#####", "#####", "#####", "#####", "....."}, // black square
        {0x25A1, ".....", "#####", "#...#", "#...#", "#...#", "#####", "....."}, // white square
        {0x25AA, "...", "...", "###", "###", "###", "...", "..."}, // small square
        {0x2022, "...", "...", ".#.", "###", ".#.", "...", "..."}, // bullet
        {0x00B7, ".", ".", ".", "#", ".", ".", "."}, // middle dot
        {0x00B0, ".#.", "#.#", ".#.", "...", "...", "...", "..."}, // degree
        {0x2714, ".....", "....#", "...##", "#.##.", "###..", ".#...", "....."}, // heavy check mark
        {0x2191, "..#..", ".###.", "#.#.#", "..#..", "..#..", "..#..", "..#.."}, // up arrow
        {0x2193, "..#..", "..#..", "..#..", "..#..", "#.#.#", ".###.", "..#.."}, // down arrow
        {0x2190, ".....", "..#..", ".#...", "#####", ".#...", "..#..", "....."}, // left arrow
        {0x2192, ".....", "..#..", "...#.", "#####", "...#.", "..#..", "....."}, // right arrow
        {0x20AC, "..###", ".#...", "####.", ".#...", "####.", ".#...", "..###"}, // euro
        {0x00A3, "..##.", ".#..#", ".#...", "###..", ".#...", ".#..#", "#####"}, // pound
        {0x03A9, ".###.", "#...#", "#...#", "#...#", ".#.#.", ".#.#.", "##.##"}, // omega: ohms
        {0x03BC, ".....", ".....", "#...#", "#...#", "#...#", "##..#", "#.##.", "#...."}, // mu: micro
    };
    /** Characters drawn as other glyphs or as ASCII spellings. */
    private static final Map<Integer, String> FOLDS = new HashMap<>();
    private static final Map<Integer, Integer> SYMBOL_GLYPHS = new HashMap<>();
    private static final byte[] FONT;
    static {
        try (InputStream stream = DotMatrix.class.getResourceAsStream("/assets/create_display_works/flip_dot_font.bin")) {
            if (stream == null) throw new IOException("Missing flip-dot font");
            byte[] ascii = stream.readAllBytes();
            if (ascii.length != ASCII_GLYPHS * 9) throw new IOException("Invalid flip-dot font");
            FONT = Arrays.copyOf(ascii, (ASCII_GLYPHS + SYMBOLS.length) * 9);
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
        for (int i = 0; i < SYMBOLS.length; i++) {
            int index = (ASCII_GLYPHS + i) * 9;
            FONT[index] = (byte)((String)SYMBOLS[i][1]).length();
            for (int y = 0; y < SYMBOLS[i].length - 1; y++) {
                String row = (String)SYMBOLS[i][1 + y];
                for (int x = 0; x < row.length(); x++) if (row.charAt(x) == '#') FONT[index + 1 + y] |= (byte)(1 << x);
            }
            SYMBOL_GLYPHS.put((Integer)SYMBOLS[i][0], ASCII_GLYPHS + i);
        }
        fold("'", 0x2018, 0x2019, 0x201A, 0x201B, 0x2032, 0x00B4, 0x02BC);
        fold("\"", 0x201C, 0x201D, 0x201E, 0x201F, 0x2033, 0x02DD);
        fold("-", 0x2010, 0x2011, 0x2012, 0x2013, 0x2014, 0x2015, 0x2212, 0x2043);
        fold("<<", 0x00AB); fold(">>", 0x00BB); fold("<", 0x2039); fold(">", 0x203A);
        fold("...", 0x2026); fold("x", 0x00D7); fold("/", 0x00F7, 0x2044, 0x2215); fold("X", 0x2716, 0x2717, 0x2718);
        fold("\u2714", 0x2713, 0x2705); fold("\u25A0", 0x25FC, 0x25FE, 0x2B1B); fold("\u25A1", 0x25FB, 0x25FD, 0x2B1C);
        fold("\u2022", 0x25CF, 0x2219, 0x2023); fold("\u2591", 0x2581); fold("|", 0x2502, 0x2503, 0x2758);
        fold("!", 0x00A1); fold("?", 0x00BF); fold("%", 0x2030); fold("Y", 0x00A5); fold("\u00B0", 0x00BA); fold("c", 0x00A2);
        fold("ss", 0x00DF); fold("ae", 0x00E6); fold("AE", 0x00C6); fold("oe", 0x0153); fold("OE", 0x0152);
        fold("o", 0x00F8); fold("O", 0x00D8); fold("d", 0x0111, 0x00F0); fold("D", 0x0110, 0x00D0);
        fold("l", 0x0142); fold("L", 0x0141); fold("th", 0x00FE); fold("Th", 0x00DE); fold("i", 0x0131);
    }
    private static void fold(String to, int... from) { for (int cp : from) FOLDS.put(cp, to); }
    private DotMatrix() {}

    /**
     * The glyphs the board draws for a line of text. Accents and typographic punctuation fold to ASCII, legacy section-sign
     * formatting codes and invisible characters such as emoji variation selectors are dropped, and anything else the
     * font can't draw becomes one {@code ?}.
     */
    public static int[] glyphs(String text) {
        int[] result = new int[256]; int count = 0;
        int[] points = text.codePoints().limit(1024).toArray();
        for (int i = 0; i < points.length && count < result.length; i++) {
            int cp = points[i];
            if (cp == 0x00A7) { i++; continue; }
            // A zero-width joiner merges the next emoji into the one before it.
            if (cp == 0x200D) { i++; continue; }
            if (cp == '\t' || cp == '\n' || cp == '\r') cp = ' ';
            if (ignored(cp)) continue;
            for (int glyph : glyphsOf(cp)) if (count < result.length) result[count++] = glyph;
        }
        return Arrays.copyOf(result, count);
    }
    private static boolean ignored(int cp) {
        int type = Character.getType(cp);
        return type == Character.FORMAT || type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
            || type == Character.CONTROL || (cp >= 0x1F3FB && cp <= 0x1F3FF);
    }
    private static int[] glyphsOf(int cp) {
        if (cp >= 32 && cp <= 126) return new int[]{cp - 32};
        Integer symbol = SYMBOL_GLYPHS.get(cp);
        if (symbol != null) return new int[]{symbol};
        String fold = FOLDS.get(cp);
        if (fold == null) {
            // Compatibility decomposition splits accents off letters and turns full-width, superscript and ligature forms
            // into plain ones. Only use it when every part left over is drawable.
            String decomposed = Normalizer.normalize(new String(Character.toChars(cp)), Normalizer.Form.NFKD);
            if (decomposed.codePoints().count() > 1 || decomposed.codePointAt(0) != cp) fold = decomposed;
        }
        if (fold == null) return new int[]{UNKNOWN};
        int[] parts = fold.codePoints().filter(part -> !ignored(part)).toArray();
        List<Integer> out = new ArrayList<>();
        for (int part : parts) {
            if (part >= 32 && part <= 126) { out.add(part - 32); continue; }
            Integer partSymbol = SYMBOL_GLYPHS.get(part);
            String partFold = FOLDS.get(part);
            if (partSymbol != null) out.add(partSymbol);
            else if (partFold != null && partFold.chars().allMatch(c -> c >= 32 && c <= 126)) partFold.chars().forEach(c -> out.add(c - 32));
            else return new int[]{UNKNOWN};
        }
        return out.isEmpty() ? new int[0] : out.stream().mapToInt(Integer::intValue).toArray();
    }

    public static long tile(String text, int tileColumn, int pixelWidth) { return tile(glyphs(text), tileColumn, pixelWidth); }
    public static long tile(int[] glyphs, int tileColumn, int pixelWidth) {
        long result = 0; int cursor = 0;
        for (int glyph : glyphs) {
            int index = glyph * 9;
            int width = Byte.toUnsignedInt(FONT[index]);
            for (int y = 0; y < 8; y++) for (int x = 0; x < width; x++) {
                int localX = cursor + x - tileColumn * 8;
                if (localX >= 0 && localX < 8 && cursor + x < pixelWidth && (FONT[index + 1 + y] & (1 << x)) != 0)
                    result |= 1L << (y * 8 + localX);
            }
            cursor += width + 1;
            if (cursor >= pixelWidth) break;
        }
        return result;
    }
}
