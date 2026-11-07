package com.zmm2025.createdisplayworks.media;

import java.util.*;

/**
 * Decides which stored clips to delete. A clip nothing references may still come back, because boards can leave the
 * world without anything the store can see holding them: Carry On, WorldEdit and Building Gadgets clipboards, schematics
 * and copied items. So unreferenced clips are only deleted when they outgrow their disk budget, longest unused first.
 */
public final class MediaRetention {
    private MediaRetention() {}
    /**
     * Returns the stored clips to delete now. {@code sizes} maps each stored clip to its size in bytes.
     * {@code unreferencedSince} maps each clip to the game time it was first found unreferenced; it's updated in place,
     * and entries for clips that are referenced, deleted or gone are removed.
     */
    public static List<String> excess(Map<String, Long> sizes, Set<String> referenced, Map<String, Long> unreferencedSince, long now, long budget) {
        unreferencedSince.keySet().retainAll(sizes.keySet());
        unreferencedSince.keySet().removeAll(referenced);
        long used = 0;
        for (var clip : sizes.entrySet()) {
            if (referenced.contains(clip.getKey())) continue;
            used += clip.getValue();
            // A clock that went backward, such as from an older copy of the world, counts as just now.
            unreferencedSince.merge(clip.getKey(), now, Math::min);
        }
        List<String> oldest = new ArrayList<>(unreferencedSince.keySet());
        oldest.sort(Comparator.<String, Long>comparing(unreferencedSince::get).thenComparing(Comparator.naturalOrder()));
        List<String> excess = new ArrayList<>();
        for (String id : oldest) {
            if (used <= budget) break;
            used -= sizes.get(id);
            excess.add(id);
        }
        excess.forEach(unreferencedSince::remove);
        return excess;
    }
}
