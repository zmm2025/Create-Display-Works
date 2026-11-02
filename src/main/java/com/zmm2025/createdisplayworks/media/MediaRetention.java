package com.zmm2025.createdisplayworks.media;

import java.util.*;

/**
 * Decides which stored clips to delete. A clip nothing references is kept for {@link #TICKS} more, because boards can
 * leave the world without anything the store can see holding them: Carry On, WorldEdit and Building Gadgets
 * clipboards, schematics and copied items. Once the board is placed again it claims the clip, which resets the wait.
 */
public final class MediaRetention {
    /** Seven in-game days, or about two hours and twenty minutes of play. */
    public static final long TICKS = 7 * 24000L;
    private MediaRetention() {}
    /**
     * Returns the stored clips to delete now. {@code unreferencedSince} maps each clip to the game time it was first found
     * unreferenced; it's updated in place, and entries for clips that are referenced or gone are removed.
     */
    public static List<String> expired(Collection<String> stored, Set<String> referenced, Map<String, Long> unreferencedSince, long now) {
        unreferencedSince.keySet().retainAll(new HashSet<>(stored));
        List<String> expired = new ArrayList<>();
        for (String id : stored) {
            if (referenced.contains(id)) { unreferencedSince.remove(id); continue; }
            long since = unreferencedSince.computeIfAbsent(id, k -> now);
            // A clock that went backward, such as from an older copy of the world, restarts the wait.
            if (since > now) unreferencedSince.put(id, now);
            else if (now - since >= TICKS) expired.add(id);
        }
        expired.forEach(unreferencedSince::remove);
        return expired;
    }
}
