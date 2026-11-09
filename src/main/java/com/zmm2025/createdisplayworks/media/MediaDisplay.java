package com.zmm2025.createdisplayworks.media;

/**
 * A block entity that shows media. The media screen, {@link MediaNetwork}, {@link MediaStore} and {@link MediaLimits}
 * work with any display through this, so a new kind of display only implements it and opens the screen with
 * {@link MediaNetwork#open}. A display may span several blocks; each of them implements this and answers for the whole
 * display, whose controller owns the {@link MediaPlayback}.
 * <p>
 * Every display takes the one-bit frames {@link MediaClip} describes. A display that shows another pixel format would
 * say which one in its {@link Canvas}; see {@link MediaClip} for where that format plugs in.
 */
public interface MediaDisplay {
    /** The whole display this block belongs to, as it stands now, or null while it isn't formed or fully loaded. */
    Canvas mediaCanvas();

    /**
     * This block's own media. Only the controller's plays, but each block claims what it holds, so {@link MediaStore}
     * checks claims against it.
     */
    MediaPlayback mediaPlayback();

    /**
     * Shows {@code clip} on the display this block belongs to, replacing what it showed. Does nothing if the clip
     * doesn't fit. {@code archive}, when given, must be the clip's archive.
     */
    void installMedia(MediaClip clip, byte[] archive);

    default void installMedia(MediaClip clip) {
        installMedia(clip, null);
    }

    /** Removes the media and blanks the display, as the media screen's clear button does. */
    void clearDisplay();

    /** The colors, as RGB, that the media screen previews set and unset pixels in, to match the display. */
    int litColor();

    int unlitColor();

    /**
     * A display's size in tiles of 8×8 pixels, and its controller's playback.
     *
     * @param playback the playback of the display's controller
     */
    record Canvas(int columns, int rows, MediaPlayback playback) {
        /** Whether the display is small enough for media, at most {@link MediaClip#MAX_TILES} tiles. */
        public boolean fitsMedia() {
            return columns * rows <= MediaClip.MAX_TILES;
        }
    }
}
