package com.zmm2025.createdisplayworks.media;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * A display's media: the clip, where it's stored, and how far it has played. Every block of a display has one, but only
 * its controller's plays; the others stay empty. The display decides when playback may run, by calling {@link #tick}
 * while it's powered and {@link #hold} while it isn't, and shows each frame it's handed through a {@link FrameSink}.
 */
public final class MediaPlayback {
    /**
     * The saved-data key that holds a clip's {@link MediaStore} id. {@link MediaStore} looks for it in contraptions,
     * trains and player data, so a display must save its media under it to keep the clip while it's carried.
     */
    public static final String MEDIA_ID = "MediaId";

    private static final int FRAME_TICKS = 20 / MediaClip.FPS;

    /** Shows one frame, in the clip's format (see {@link MediaClip}), on the display. */
    @FunctionalInterface
    public interface FrameSink {
        void show(long[] frame);
    }

    private final SmartBlockEntity owner;
    private final FrameSink sink;
    // Playback frames only. The editable source lives in the saved archive, which is built once and reused for every
    // save and editor request instead of being rebuilt each time.
    private MediaClip clip;
    /** Saved inline only when the store couldn't write {@link #id}'s file. Otherwise it's read from the store. */
    private byte[] archive;
    /** The {@link MediaStore} id of {@link #clip}, or null if it couldn't be stored and has to be saved inline. */
    private String id;

    private int frame, delay;
    private boolean playing, loop = true;
    /** Whether the display let playback run on its last tick. {@link MediaLimits} only counts playback that runs. */
    private boolean running;

    public MediaPlayback(SmartBlockEntity owner, FrameSink sink) {
        this.owner = owner;
        this.sink = sink;
    }

    /**
     * Shows {@code clip} from its first frame, playing if it's animated. {@code archive}, when given, must be the
     * clip's archive; it's reused if the source needs no shrinking. The display checks that the clip fits it.
     */
    public void install(MediaClip clip, byte[] archive) {
        store(clip, archive);
        claim();
        frame = 0;
        playing = clip.count() > 1;
        loop = true;
        delay = 0;
        // A still image is an ordinary latest target, including while unpowered.
        sink.show(this.clip.frame(0));
        owner.setChanged();
    }
    /** Stores the clip with its editable source shrunk to what the display can show, and keeps only playback. */
    private void store(MediaClip clip, byte[] archive) {
        var compact = clip.withCompactSource();
        byte[] bytes = compact == clip && archive != null ? archive : compact.archive();
        this.clip = compact.withoutSource();
        id = MediaStore.save(owner.getLevel().getServer(), bytes);
        this.archive = id == null ? bytes : null;
    }

    public void clear() {
        MediaLimits.release(this);
        release();
        clip = null;
        id = null;
        archive = null;
        frame = 0;
        playing = false;
        owner.setChanged();
    }
    /**
     * Claims the stored clip for the owner's position. Call it once the owner has a level too: blocks loaded from disk
     * get theirs after reading their data, and copies made with /clone read it afterwards.
     */
    public void claim() {
        if (id != null && owner.getLevel() instanceof ServerLevel server)
            MediaStore.claim(server, owner.getBlockPos(), id);
    }
    /** Releases the owner's claim, for when the block is removed. */
    public void release() {
        if (id != null && owner.getLevel() instanceof ServerLevel server)
            MediaStore.release(server, owner.getBlockPos(), id);
    }

    /** Runs a media screen button: {@code play}, {@code pause}, {@code restart} or {@code loop}. */
    public void control(String action) {
        switch (action) {
            case "play" -> playing = true;
            case "pause" -> playing = false;
            case "restart" -> {
                frame = 0;
                delay = 0;
                playing = true;
            }
            case "loop" -> loop = !loop;
            default -> {
                return;
            }
        }
        owner.setChanged();
        owner.sendData();
    }

    /**
     * Advances playback on a server tick in which the display may show new frames, at {@link MediaClip#FPS}.
     * {@code columns} and {@code rows} are the display's size and {@code blocks} how many blocks it has, for
     * {@link MediaLimits}. Returns false if the clip no longer fits the display, which clears it.
     */
    public boolean tick(int columns, int rows, int blocks) {
        running = true;
        if (clip == null || !playing || !MediaLimits.acquire(this, blocks) || delay-- > 0) return true;
        if (clip.columns() != columns || clip.rows() != rows) {
            clear();
            return false;
        }
        sink.show(clip.frame(frame));
        if (++frame >= clip.count()) {
            frame = 0;
            if (!loop) playing = false;
        }
        delay = FRAME_TICKS - 1;
        owner.setChanged();
        return true;
    }
    /** Holds playback where it is while the display can't show frames, such as while it's unpowered. */
    public void hold() {
        running = false;
        delay = 0;
        MediaLimits.release(this);
    }

    public SmartBlockEntity owner() {
        return owner;
    }

    public boolean hasMedia() {
        return clip != null;
    }
    /** Whether frames are advancing: there's media, it isn't paused, and the display lets it run. */
    public boolean isPlaying() {
        return clip != null && playing && running;
    }
    /** Whether playback is switched on, as the media screen's play button shows it, even while the display holds it. */
    public boolean playbackEnabled() {
        return playing;
    }

    public boolean loops() {
        return loop;
    }

    public int frameCount() {
        return clip == null ? 0 : clip.count();
    }

    public String filename() {
        return clip == null ? "" : clip.filename();
    }
    /** The first frame, which the media screen shows before it has the editable source. */
    public long[] thumbnail() {
        return clip == null ? new long[0] : clip.frame(0);
    }

    /** The {@link MediaStore} id of the clip, or null if there's none or it's saved inline. */
    public String id() {
        return id;
    }
    /** The saved archive, with the editable source, for the media screen. */
    public byte[] archive() {
        return id != null ? MediaStore.read(id) : archive;
    }

    public void write(CompoundTag tag, boolean clientPacket) {
        tag.putInt("PlaybackDelay", delay);
        if (clientPacket || clip == null) return;
        if (id != null) tag.putString(MEDIA_ID, id);
        else tag.putByteArray("MediaArchive", archive);
        tag.putInt("MediaFrame", frame);
        tag.putBoolean("MediaPlaying", playing);
        tag.putBoolean("MediaLoop", loop);
    }

    public void read(CompoundTag tag, boolean clientPacket) {
        delay = Math.clamp(tag.getInt("PlaybackDelay"), 0, FRAME_TICKS);
        if (clientPacket) return;
        clip = null;
        id = null;
        archive = null;
        // A display whose clip couldn't be stored keeps it inline, and moves it to the store once it can.
        if (tag.contains(MEDIA_ID)) {
            id = tag.getString(MEDIA_ID);
            var stored = MediaStore.load(id);
            if (stored == null) id = null;
            else clip = stored.withoutSource();
        } else if (tag.contains("MediaArchive"))
            try {
                byte[] inline = tag.getByteArray("MediaArchive");
                clip = MediaClip.fromArchive(inline).withoutSource();
                var server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) id = MediaStore.save(server, inline);
                if (id == null) archive = inline;
            } catch (IllegalArgumentException ignored) {
            }
        claim();
        if (clip != null) {
            frame = Math.floorMod(tag.getInt("MediaFrame"), clip.count());
            playing = tag.getBoolean("MediaPlaying");
            loop = tag.getBoolean("MediaLoop");
        } else playing = false;
    }
}
