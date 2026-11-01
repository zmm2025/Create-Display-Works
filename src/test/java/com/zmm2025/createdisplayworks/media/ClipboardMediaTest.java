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
}
