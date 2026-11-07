package com.zmm2025.createdisplayworks.media;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public record MediaClip(
        int columns, int rows, long[] frames, String filename, EditableMedia editable, MediaSettings settings) {
    public MediaClip(int columns, int rows, long[] frames, String filename) {
        this(columns, rows, frames, filename, null, MediaSettings.DEFAULT);
    }

    public MediaClip(int columns, int rows, long[] frames) {
        this(columns, rows, frames, UNNAMED);
    }
    /** Stored names for media without a file name. The media screen shows them translated. */
    public static final String UNNAMED = "Imported media", YOUTUBE = "YouTube video";

    public static final int FPS = 10, MAX_FRAMES = 600, MAX_TILES = 256;

    public MediaClip {
        filename = filename == null
                ? UNNAMED
                : filename.codePoints()
                        .filter(c -> c >= 32 && c != 127)
                        .limit(128)
                        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                        .toString();
        if (filename.length() > 128)
            filename = filename.substring(0, Character.isHighSurrogate(filename.charAt(127)) ? 127 : 128);
        if (settings == null) throw new IllegalArgumentException("Missing media settings");
        if (columns < 1
                || rows < 1
                || columns > 32
                || rows > 32
                || columns * rows > MAX_TILES
                || frames.length == 0
                || frames.length % (columns * rows) != 0
                || frames.length > columns * rows * MAX_FRAMES)
            throw new IllegalArgumentException("Invalid media dimensions or frame count");
    }

    public byte[] packed() {
        try (var bytes = new ByteArrayOutputStream()) {
            try (var out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
                for (long frame : frames) out.writeLong(frame);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static MediaClip unpack(int columns, int rows, int count, byte[] packed, String filename) {
        if (columns < 1
                || rows < 1
                || columns > 32
                || rows > 32
                || columns * rows > MAX_TILES
                || count < 1
                || count > MAX_FRAMES
                || packed.length > MAX_TILES * MAX_FRAMES * 8 + 4096)
            throw new IllegalArgumentException("Invalid saved media");
        try (var in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(packed)))) {
            long[] frames = new long[columns * rows * count];
            for (int i = 0; i < frames.length; i++) frames[i] = in.readLong();
            if (in.read() != -1) throw new IllegalArgumentException("Excess saved media");
            return new MediaClip(columns, rows, frames, filename);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid saved media", e);
        }
    }

    public int count() {
        return frames.length / (columns * rows);
    }

    public byte[] archive() {
        try (var bytes = new ByteArrayOutputStream();
                var out = new DataOutputStream(bytes)) {
            out.writeInt(1);
            out.writeInt(columns);
            out.writeInt(rows);
            out.writeInt(count());
            out.writeUTF(filename);
            byte[] bits = packed();
            out.writeInt(bits.length);
            out.write(bits);
            out.writeInt(settings.threshold());
            out.writeBoolean(settings.invert());
            out.writeBoolean(settings.dither());
            out.writeInt(settings.scale().ordinal());
            out.writeInt(settings.seconds());
            out.writeBoolean(editable != null);
            if (editable != null) editable.write(out);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static MediaClip fromArchive(byte[] bytes) {
        if (bytes.length > EditableMedia.MAX_BYTES) throw new IllegalArgumentException("Media archive too large");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 1) throw new IllegalArgumentException("Unknown media archive");
            int w = in.readInt(), h = in.readInt(), count = in.readInt();
            String name = in.readUTF();
            int size = in.readInt();
            if (size < 1 || size > MAX_TILES * MAX_FRAMES * 8 + 4096)
                throw new IllegalArgumentException("Invalid frame data size");
            var clip = unpack(w, h, count, in.readNBytes(size), name);
            int threshold = in.readInt();
            boolean invert = in.readBoolean(), dither = in.readBoolean();
            int scale = in.readInt(), seconds = in.readInt();
            if (scale < 0 || scale >= MediaDecoder.Scale.values().length)
                throw new IllegalArgumentException("Invalid framing");
            var settings = new MediaSettings(threshold, invert, dither, MediaDecoder.Scale.values()[scale], seconds);
            var source = in.readBoolean() ? EditableMedia.read(in) : null;
            if (in.read() != -1) throw new IllegalArgumentException("Excess archive data");
            if (source != null) {
                if (source.frameCount(settings) != count)
                    throw new IllegalArgumentException("Mismatched source duration");
                source.validate();
            }
            return new MediaClip(w, h, clip.frames, name, source, settings);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid media archive", e);
        }
    }
    /** The same clip with its editable source shrunk to what this board can show. */
    public MediaClip withCompactSource() {
        var compact = editable == null ? null : editable.compactFor(columns, rows);
        return compact == editable ? this : new MediaClip(columns, rows, frames, filename, compact, settings);
    }
    /** The same clip without its editable source, for holding playback only. */
    public MediaClip withoutSource() {
        return editable == null ? this : new MediaClip(columns, rows, frames, filename, null, settings);
    }

    public long[] frame(int index) {
        int size = columns * rows;
        return Arrays.copyOfRange(frames, index * size, (index + 1) * size);
    }
}
