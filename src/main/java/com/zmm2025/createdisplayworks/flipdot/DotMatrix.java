package com.zmm2025.createdisplayworks.flipdot;

import java.io.*;

/** Fixed physical pixels: no client font dependency on a dedicated server. */
public final class DotMatrix {
    private static final byte[] FONT;
    static {
        try (InputStream stream = DotMatrix.class.getResourceAsStream("/assets/create_display_works/flip_dot_font.bin")) {
            if (stream == null) throw new IOException("Missing flip-dot font");
            FONT = stream.readAllBytes();
            if (FONT.length != 95 * 9) throw new IOException("Invalid flip-dot font");
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    private DotMatrix() {}
    public static long tile(String text, int tileColumn, int pixelWidth) {
        long result = 0; int cursor = 0;
        for (int cp : text.codePoints().limit(256).toArray()) {
            int index = ((cp >= 32 && cp <= 126 ? cp : '?') - 32) * 9;
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
