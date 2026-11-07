package com.zmm2025.createdisplayworks.dev.tools;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.SOFTLoopback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sound for a hidden game. Instead of opening the computer's sound device, it opens an OpenAL loopback device, which
 * mixes into memory, so nothing a hidden game plays can reach the speakers. Showing the window switches to the real
 * device. The mix keeps its real levels, and can be recorded to a WAV file.
 */
public final class DevAudio {
    private static final Logger LOGGER = LoggerFactory.getLogger("Display Works Dev Tools");
    private static final int RATE = 48000, CHANNELS = 2, BYTES_PER_FRAME = 2 * CHANNELS;
    private static final int MAX_SECONDS = 300;
    private static final long MAX_BYTES = (long) MAX_SECONDS * RATE * BYTES_PER_FRAME;
    /** Below this peak a recording counts as silence, which is about what's left when nothing played. */
    private static final double SILENCE_DB = -80;

    private static final Object LOCK = new Object();
    private static volatile boolean wanted = Boolean.getBoolean("createdisplayworks.dev.hidden");
    private static volatile long device;
    private static volatile Thread renderer;
    /** Guarded by {@link #LOCK}. Null unless recording. */
    private static ByteArrayOutputStream recording;
    /** Guarded by {@link #LOCK}. Null unless a session is being recorded. */
    private static OutputStream session;

    private static volatile long recordingStarted;

    private DevAudio() {}

    /** Opens the silent device if this game should use one, returning 0 to use the real device. */
    public static long openDevice() {
        if (!wanted) return 0;
        try {
            device = SOFTLoopback.alcLoopbackOpenDeviceSOFT((CharSequence) null);
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            LOGGER.warn("No silent sound device, so this hidden game stays muted on the real one", e);
            device = 0;
        }
        return device;
    }

    public static boolean isSilent(long handle) {
        return handle != 0 && handle == device;
    }

    /** Whether sound currently goes into memory rather than to a real device. */
    static boolean active() {
        return device != 0;
    }

    /** A loopback device takes its output format from its context. */
    public static IntBuffer contextAttributes() {
        IntBuffer attributes = BufferUtils.createIntBuffer(9);
        attributes
                .put(SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT)
                .put(SOFTLoopback.ALC_STEREO_SOFT)
                .put(SOFTLoopback.ALC_FORMAT_TYPE_SOFT)
                .put(SOFTLoopback.ALC_SHORT_SOFT)
                .put(ALC10.ALC_FREQUENCY)
                .put(RATE)
                .put(0)
                .flip();
        return attributes;
    }

    /** A loopback device only advances when asked for samples, so sounds would never finish without this. */
    public static void startRendering() {
        long handle = device;
        Thread thread = new Thread(() -> render(handle), "Display Works Silent Audio");
        thread.setDaemon(true);
        renderer = thread;
        thread.start();
    }

    public static void stopRendering() {
        Thread thread = renderer;
        renderer = null;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        device = 0;
    }

    private static void render(long handle) {
        ByteBuffer buffer =
                ByteBuffer.allocateDirect(RATE / 10 * BYTES_PER_FRAME).order(ByteOrder.nativeOrder());
        byte[] copy = new byte[buffer.capacity()];
        int capacity = buffer.capacity() / BYTES_PER_FRAME;
        long start = System.nanoTime(), rendered = 0;
        while (renderer == Thread.currentThread()) {
            long due = (System.nanoTime() - start) * RATE / 1_000_000_000L - rendered;
            if (due <= 0) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    return;
                }
                continue;
            }
            int frames = (int) Math.min(due, capacity);
            buffer.clear();
            SOFTLoopback.alcRenderSamplesSOFT(handle, buffer, frames);
            rendered += frames;
            synchronized (LOCK) {
                boolean record = recording != null && recording.size() < MAX_BYTES;
                if (!record && session == null) continue;
                buffer.get(0, copy, 0, frames * BYTES_PER_FRAME);
                if (record) recording.write(copy, 0, frames * BYTES_PER_FRAME);
                if (session != null) writeSession(copy, frames * BYTES_PER_FRAME);
            }
        }
    }

    private static void writeSession(byte[] samples, int length) {
        try {
            session.write(samples, 0, length);
        } catch (IOException e) {
            LOGGER.warn("Stopped recording the session's sound", e);
            session = null;
        }
    }

    /** Sends the mix to {@code file} as well, for a session recording; null stops. */
    static void tap(Path file) {
        synchronized (LOCK) {
            try {
                if (session != null) session.close();
                session = file == null ? null : Files.newOutputStream(file);
            } catch (IOException e) {
                LOGGER.warn("Could not record the session's sound", e);
                session = null;
            }
        }
    }

    /** Moves sound to the silent device or the real one, when a hidden game is shown or a shown one hidden. */
    static void useSilentDevice(boolean silent) {
        if (wanted == silent) return;
        wanted = silent;
        Minecraft.getInstance().getSoundManager().reload();
    }

    /**
     * Records what the game plays: for {@code seconds}, or from {@code action=start} until {@code action=stop}. Returns
     * the file and its levels, so a check needs no listening; {@code keep} saves a copy for chat.
     */
    static JsonObject audio(Map<String, String> query) throws Exception {
        String action = query.get("action");
        if ("start".equals(action)) {
            start();
            return DevTools.ok();
        }
        if (!"stop".equals(action)) {
            double seconds = Double.parseDouble(query.getOrDefault("seconds", "5"));
            if (seconds <= 0 || seconds > MAX_SECONDS)
                return DevTools.failure("Record for up to " + MAX_SECONDS + " seconds.");
            start();
            Thread.sleep((long) (seconds * 1000));
        }
        Path file = stop();
        if (file == null) return DevTools.failure("Nothing is being recorded.");
        JsonObject response = DevTools.ok();
        response.addProperty("path", file.toString());
        describe(file, response);
        if (query.containsKey("keep")) DevTools.keep(query.get("keep"), List.of(DevVideo.compress(file)), response);
        return response;
    }

    /** Starts recording. Only a silent device can record, so a recording never plays aloud. */
    static long start() {
        if (!active())
            throw new IllegalStateException(
                    "The game is playing through the real sound device, because its window is shown. Recording only works while it's hidden.");
        synchronized (LOCK) {
            if (recording != null) throw new IllegalStateException("A recording is already running.");
            recording = new ByteArrayOutputStream(RATE * BYTES_PER_FRAME);
            recordingStarted = System.nanoTime();
            return recordingStarted;
        }
    }

    /** Stops recording and writes the WAV file, or returns null if nothing was being recorded. */
    static Path stop() throws IOException {
        byte[] samples;
        synchronized (LOCK) {
            if (recording == null) return null;
            samples = recording.toByteArray();
            recording = null;
        }
        Path folder = Minecraft.getInstance()
                .gameDirectory
                .toPath()
                .resolve("screenshots/dev-tools")
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(folder);
        Path file = folder.resolve(
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss.SSS")) + ".wav");
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes())
                .putInt(36 + samples.length)
                .put("WAVEfmt ".getBytes())
                .putInt(16)
                .putShort((short) 1)
                .putShort((short) CHANNELS)
                .putInt(RATE)
                .putInt(RATE * BYTES_PER_FRAME)
                .putShort((short) BYTES_PER_FRAME)
                .putShort((short) 16)
                .put("data".getBytes())
                .putInt(samples.length);
        byte[] whole = new byte[44 + samples.length];
        System.arraycopy(header.array(), 0, whole, 0, 44);
        System.arraycopy(samples, 0, whole, 44, samples.length);
        Files.write(file, whole);
        return file;
    }

    /** Adds a WAV file's length and levels in dB below full scale. */
    private static void describe(Path file, JsonObject response) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer samples = ByteBuffer.wrap(bytes, 44, bytes.length - 44).order(ByteOrder.LITTLE_ENDIAN);
        int count = samples.remaining() / 2, peak = 0;
        double sum = 0;
        while (samples.remaining() >= 2) {
            int sample = samples.getShort();
            peak = Math.max(peak, Math.abs(sample));
            sum += (double) sample * sample;
        }
        double peakDb = decibels(peak), rmsDb = count == 0 ? -120 : decibels(Math.sqrt(sum / count));
        response.addProperty("seconds", DevTools.round((double) count / CHANNELS / RATE));
        response.addProperty("peakDb", DevTools.round(peakDb));
        response.addProperty("rmsDb", DevTools.round(rmsDb));
        response.addProperty("silent", peakDb < SILENCE_DB);
    }

    private static double decibels(double sample) {
        return sample <= 0 ? -120 : 20 * Math.log10(sample / 32768);
    }
}
