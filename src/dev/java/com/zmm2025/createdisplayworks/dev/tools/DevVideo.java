package com.zmm2025.createdisplayworks.dev.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Turns a captured frame sequence into an MP4 or GIF with ffmpeg, showing each frame for as long as it was on screen. */
final class DevVideo {
    private DevVideo() {}

    static Path encode(List<Path> frames, List<Double> timesMs, String format) throws IOException, InterruptedException {
        String type = format.toLowerCase(Locale.ROOT);
        if(!type.equals("mp4") && !type.equals("gif")) throw new IllegalArgumentException("Use video=mp4 or video=gif.");
        Path folder = frames.get(0).getParent(), list = folder.resolve("frames.ffconcat"), video = folder.resolve("video." + type);
        double average = (timesMs.get(timesMs.size() - 1) - timesMs.get(0)) / Math.max(1, timesMs.size() - 1);
        StringBuilder concat = new StringBuilder("ffconcat version 1.0\n");
        for(int i = 0; i < frames.size(); i++) {
            double ms = i + 1 < timesMs.size() ? timesMs.get(i + 1) - timesMs.get(i) : average;
            concat.append("file '").append(frames.get(i).getFileName()).append("'\nduration ").append(String.format(Locale.ROOT, "%.4f", Math.max(ms, 1) / 1000)).append('\n');
        }
        // The concat demuxer ignores the last frame's duration unless the frame is listed again.
        concat.append("file '").append(frames.get(frames.size() - 1).getFileName()).append("'\n");
        Files.writeString(list, concat, StandardCharsets.UTF_8);
        List<String> command = type.equals("mp4")
            ? List.of("ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", list.getFileName().toString(),
                "-vf", "fps=60,scale=trunc(iw/2)*2:trunc(ih/2)*2", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18", video.getFileName().toString())
            : List.of("ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", list.getFileName().toString(),
                "-vf", "fps=30,scale='min(960,iw)':-2:flags=lanczos,split[a][b];[a]palettegen[p];[b][p]paletteuse", video.getFileName().toString());
        Process process;
        try {
            process = new ProcessBuilder(command).directory(folder.toFile()).redirectErrorStream(true).start();
        } catch(IOException e) {
            throw new IOException("ffmpeg isn't on the game's PATH; install it (winget install ffmpeg) and restart the dev client.", e);
        }
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if(!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("ffmpeg took longer than 5 minutes.");
        }
        Files.deleteIfExists(list);
        if(process.exitValue() != 0) throw new IOException("ffmpeg failed: " + output);
        return video;
    }
}
