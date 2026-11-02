package com.zmm2025.createdisplayworks.media;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MediaRetentionTest {
    @Test void unreferencedClipsWaitBeforeTheyreDeleted() {
        Map<String, Long> since = new HashMap<>();
        List<String> stored = List.of("a", "b");
        assertEquals(List.of(), MediaRetention.expired(stored, Set.of("a"), since, 100));
        assertEquals(Map.of("b", 100L), since);
        assertEquals(List.of(), MediaRetention.expired(stored, Set.of("a"), since, 100 + MediaRetention.TICKS - 1));
        assertEquals(List.of("b"), MediaRetention.expired(stored, Set.of("a"), since, 100 + MediaRetention.TICKS));
        assertEquals(Map.of(), since);
    }

    @Test void referencingAClipAgainRestartsTheWait() {
        Map<String, Long> since = new HashMap<>();
        MediaRetention.expired(List.of("a"), Set.of(), since, 0);
        MediaRetention.expired(List.of("a"), Set.of("a"), since, MediaRetention.TICKS / 2);
        assertTrue(since.isEmpty());
        assertEquals(List.of(), MediaRetention.expired(List.of("a"), Set.of(), since, MediaRetention.TICKS));
        assertEquals(List.of("a"), MediaRetention.expired(List.of("a"), Set.of(), since, MediaRetention.TICKS * 2));
    }

    @Test void forgetsClipsThatAreGoneAndClocksThatWentBack() {
        Map<String, Long> since = new HashMap<>(Map.of("gone", 0L, "a", 5000L));
        assertEquals(List.of(), MediaRetention.expired(List.of("a"), Set.of(), since, 10));
        assertEquals(Map.of("a", 10L), since);
    }
}
