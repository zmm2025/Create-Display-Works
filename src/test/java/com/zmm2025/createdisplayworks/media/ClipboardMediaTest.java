package com.zmm2025.createdisplayworks.media;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ClipboardMediaTest {
    @Test
    void namesComeFromTheCopiedImageAddress() {
        assertEquals(
                "Sunset at the pier",
                ClipboardMedia.name("https://example.com/photos/Sunset%20at%20the%20pier.webp?w=800"));
        assertEquals("cat", ClipboardMedia.name("file:///Users/dev/Pictures/cat.heic\nCat"));
        assertNull(ClipboardMedia.name("data:image/png;base64,AAAA"));
        assertNull(ClipboardMedia.name("https://example.com/"));
        assertNull(ClipboardMedia.name("not a url"));
        assertNull(ClipboardMedia.name(null));
        assertNull(ClipboardMedia.name("https://example.com/con.png"));
        assertEquals("v2", ClipboardMedia.name("https://example.com/v2..png"));
    }

    @Test
    void clipboardTextIgnoresTerminatorsAndReadsUtf16() {
        byte[] html = "<img src=\"https://example.com/Sunset.png\">".getBytes(StandardCharsets.UTF_8);
        byte[] terminated = Arrays.copyOf(html, html.length + 3);
        assertEquals(new String(html, StandardCharsets.UTF_8), ClipboardMedia.text(terminated));
        assertEquals(
                "https://example.com/cat.png",
                ClipboardMedia.text("\uFEFFhttps://example.com/cat.png\0".getBytes(StandardCharsets.UTF_16LE)));
        assertEquals("caf\u00e9", ClipboardMedia.text("caf\u00e9".getBytes(StandardCharsets.UTF_8)));
    }
}
