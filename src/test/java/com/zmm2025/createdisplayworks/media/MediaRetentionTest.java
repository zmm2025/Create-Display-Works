package com.zmm2025.createdisplayworks.media;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class MediaRetentionTest {
    @Test
    void unusedClipsStayWhileTheyFit() {
        Map<String, Long> since = new HashMap<>();
        Map<String, Long> sizes = Map.of("a", 10L, "b", 20L);
        assertEquals(List.of(), MediaRetention.excess(sizes, Set.of("a"), since, 100, 20));
        assertEquals(Map.of("b", 100L), since);
        // Time alone never deletes anything.
        assertEquals(List.of(), MediaRetention.excess(sizes, Set.of("a"), since, 100_000_000L, 20));
    }

    @Test
    void longestUnusedClipsGoFirstWhenOverBudget() {
        Map<String, Long> since = new HashMap<>(Map.of("old", 1L, "mid", 2L, "new", 3L));
        Map<String, Long> sizes = Map.of("old", 10L, "mid", 10L, "new", 10L, "used", 1000L);
        assertEquals(List.of("old", "mid"), MediaRetention.excess(sizes, Set.of("used"), since, 10, 10));
        assertEquals(Map.of("new", 3L), since);
    }

    @Test
    void referencingAClipAgainMakesItNewest() {
        Map<String, Long> since = new HashMap<>(Map.of("a", 1L, "b", 2L));
        Map<String, Long> sizes = Map.of("a", 10L, "b", 10L);
        assertEquals(List.of(), MediaRetention.excess(sizes, Set.of("a"), since, 5, 100));
        assertEquals(Map.of("b", 2L), since);
        assertEquals(List.of("b"), MediaRetention.excess(sizes, Set.of(), since, 6, 10));
        assertEquals(Map.of("a", 6L), since);
    }

    @Test
    void forgetsClipsThatAreGoneAndClocksThatWentBack() {
        Map<String, Long> since = new HashMap<>(Map.of("gone", 0L, "a", 5000L));
        assertEquals(List.of(), MediaRetention.excess(Map.of("a", 1L), Set.of(), since, 10, 1));
        assertEquals(Map.of("a", 10L), since);
    }

    @Test
    void aBudgetOfZeroDeletesEveryUnusedClip() {
        Map<String, Long> since = new HashMap<>();
        assertEquals(List.of("a"), MediaRetention.excess(Map.of("a", 1L, "b", 1L), Set.of("b"), since, 0, 0));
    }
}
