package com.zmm2025.createdisplayworks.dev.tools;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Records a whole game session while a file named {@value #FLAG} is in the game's folder: video with real timing, the
 * sound mix, and a log of control API calls. Frames are copied on the render thread and encoded by ffmpeg, so an
 * agent's own captures don't change, and a slow moment shows up in the video as a slow moment.
 */
final class DevRecorder {
    private static final Logger LOGGER = LoggerFactory.getLogger("Display Works Dev Tools");
    private static final String FLAG = "dev-record-session";
    private static final int FPS = 15, QUEUED_FRAMES = 8;
    private static volatile boolean wanted;
    private static volatile Path log;
    private static long nextFrame;
    private static int width, height;
    private static BlockingQueue<ByteBuffer> free, ready;
    private static Thread writer;
    private static Process encoder;

    private DevRecorder() {}

    /** Runs every couple of seconds off the render thread, so a flag can be added to a running game. */
    static void check(Path gameDirectory) {
        wanted = Files.exists(gameDirectory.resolve(FLAG));
    }

    /** Runs after every rendered frame. */
    static void frameRendered() {
        if(!wanted) {
            if(writer != null) stop();
            return;
        }
        long now = System.nanoTime();
        if(now < nextFrame) return;
        nextFrame = now + 1_000_000_000L / FPS;
        RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
        if(target.width != width || target.height != height || writer == null) {
            stop();
            if(!start(target.width, target.height)) return;
        }
        ByteBuffer frame = free.poll();
        if(frame == null) return;
        frame.clear();
        RenderSystem.bindTexture(target.getColorTextureId());
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, frame);
        ready.add(frame);
    }

    /** Adds a line to the session's log of what was asked of the game. */
    static void log(String call, long milliseconds) {
        Path file = log;
        if(file == null) return;
        String line = System.currentTimeMillis() + "\t" + milliseconds + "\t" + call.replaceAll("\\s+", " ") + "\n";
        try {
            synchronized(DevRecorder.class) {
                Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch(IOException e) {
            LOGGER.warn("Could not log a control API call", e);
        }
    }

    private static boolean start(int frameWidth, int frameHeight) {
        try {
            Path folder = DevTools.evidenceDirectory();
            Files.createDirectories(folder);
            String name = "session-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss"));
            log = folder.resolve(name + ".log");
            log("start " + frameWidth + "x" + frameHeight, 0);
            DevAudio.tap(folder.resolve(name + ".pcm"));
            encoder = new ProcessBuilder(List.of("ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgba", "-s", frameWidth + "x" + frameHeight,
                "-use_wallclock_as_timestamps", "1", "-i", "-", "-vf", "vflip", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "26", "-pix_fmt", "yuv420p",
                "-fps_mode", "vfr", folder.resolve(name + ".mkv").toString()))
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            width = frameWidth;
            height = frameHeight;
            free = new ArrayBlockingQueue<>(QUEUED_FRAMES);
            ready = new ArrayBlockingQueue<>(QUEUED_FRAMES);
            for(int i = 0; i < QUEUED_FRAMES; i++) free.add(ByteBuffer.allocateDirect(frameWidth * frameHeight * 4));
            BlockingQueue<ByteBuffer> freeFrames = free, readyFrames = ready;
            OutputStream out = encoder.getOutputStream();
            writer = new Thread(() -> write(readyFrames, freeFrames, out), "Display Works Session Recorder");
            writer.setDaemon(true);
            writer.start();
            return true;
        } catch(IOException e) {
            LOGGER.warn("Could not start recording the session; is ffmpeg on the game's PATH?", e);
            wanted = false;
            return false;
        }
    }

    private static void write(BlockingQueue<ByteBuffer> readyFrames, BlockingQueue<ByteBuffer> freeFrames, OutputStream out) {
        try(WritableByteChannel channel = Channels.newChannel(out)) {
            while(true) {
                ByteBuffer frame = readyFrames.take();
                frame.position(0).limit(frame.capacity());
                while(frame.hasRemaining()) channel.write(frame);
                freeFrames.add(frame);
            }
        } catch(IOException | InterruptedException e) {
            // Closing the channel ends the video. An interrupt is how a recording stops.
        }
    }

    private static void stop() {
        if(writer != null) writer.interrupt();
        writer = null;
        DevAudio.tap(null);
    }
}
