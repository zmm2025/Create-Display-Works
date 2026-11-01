package com.zmm2025.createdisplayworks.media;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClipboardMediaTest {
    @Test void namesComeFromTheCopiedImageAddress() {
        assertEquals("Sunset at the pier",ClipboardMedia.name("https://example.com/photos/Sunset%20at%20the%20pier.webp?w=800"));
        assertEquals("cat",ClipboardMedia.name("file:///Users/dev/Pictures/cat.heic\nCat"));
        assertNull(ClipboardMedia.name("data:image/png;base64,AAAA"));
        assertNull(ClipboardMedia.name("https://example.com/"));
        assertNull(ClipboardMedia.name("not a url"));
        assertNull(ClipboardMedia.name(null));
        assertNull(ClipboardMedia.name("https://example.com/con.png"));
        assertEquals("v2",ClipboardMedia.name("https://example.com/v2..png"));
    }
    @Test void clipboardTextIgnoresTerminatorsAndReadsUtf16() {
        byte[] html="<img src=\"https://example.com/Sunset.png\">".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] terminated=java.util.Arrays.copyOf(html,html.length+3);
        assertEquals(new String(html,java.nio.charset.StandardCharsets.UTF_8),ClipboardMedia.text(terminated));
        assertEquals("https://example.com/cat.png",ClipboardMedia.text("\uFEFFhttps://example.com/cat.png\0".getBytes(java.nio.charset.StandardCharsets.UTF_16LE)));
        assertEquals("caf\u00e9",ClipboardMedia.text("caf\u00e9".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
