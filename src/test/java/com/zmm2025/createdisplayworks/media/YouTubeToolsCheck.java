package com.zmm2025.createdisplayworks.media;

import static org.junit.jupiter.api.Assertions.*;

import com.zmm2025.createdisplayworks.media.MediaToolList.Tool;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Compares the yt-dlp players get now with the one a media tools PR would give them. For main's list and the PR
 * branch's list, both fetched from GitHub, it runs the mod's own download, hash check and YouTube import on a real
 * video, on this computer's connection. Run it with {@code ./gradlew checkYouTubeTools}; see PUBLISHING.md.
 */
class YouTubeToolsCheck {
    private static final Path ROOT = Path.of(System.getProperty("youtubeTools.dir", "build/youtube-tools-check"))
            .toAbsolutePath();

    @Test
    @EnabledIfSystemProperty(named = "youtubeTools.check", matches = "true")
    void compareMainWithTheUpdate() throws Exception {
        String branch = System.getProperty("youtubeTools.branch", "chore/media-tools-update");
        String video = System.getProperty("youtubeTools.video", "https://www.youtube.com/watch?v=FtutLA63Cp8");
        URI main = MediaToolList.SOURCE, update = URI.create(main.toString().replace("/main/", "/" + branch + "/"));
        var report = new StringBuilder("YouTube tools check on " + MediaDecoder.platform() + " with " + video + "\n\n");
        report.append(check("main", main, video).line());
        var result = check(branch, update, video);
        report.append(result.line()).append('\n');
        report.append(
                result.version().equals("-")
                        ? "There's no media tool list on " + branch + ". Is the update PR still open?\n"
                        : result.works()
                                ? "The update works. Merge it if main's yt-dlp is broken, or whenever you like.\n"
                                : "The update doesn't work here, so don't merge it yet.\n");
        Files.createDirectories(ROOT);
        Files.writeString(ROOT.resolve("result.txt"), report, StandardCharsets.UTF_8);
        System.out.println("\n" + report);
        assertTrue(result.works(), branch + "'s yt-dlp failed; see " + ROOT.resolve("result.txt"));
    }

    private record Result(String name, String version, boolean works, String detail) {
        String line() {
            return String.format(
                    "  %-26s yt-dlp %-12s %s%n", name, version, (works ? "OK      " : "FAILED  ") + detail);
        }
    }
    /** One list's run, in its own cache folder so each keeps its own tools between runs. */
    private static Result check(String name, URI source, String video) throws Exception {
        Path cache = Files.createDirectories(ROOT.resolve(name.replaceAll("[^A-Za-z0-9._-]", "_")));
        // Always read the list from GitHub, never a copy from an earlier run.
        Files.deleteIfExists(cache.resolve(MediaToolList.CACHE_FILE));
        if (MediaToolList.online(cache, source, s -> {}) == null)
            return new Result(name, "-", false, "no valid list at " + source);
        var plan = YouTubeImport.plan(cache, source, MediaDecoder.platform(), s -> {});
        String version = plan.get(Tool.YT_DLP).version();
        Path temp = Files.createTempDirectory(cache, "import-");
        try {
            // Install exactly the listed releases. The import's fallback to an older copy would hide a bad download.
            for (Tool tool : Tool.values()) YouTubeImport.verified(cache, plan.get(tool), s -> {});
            Path input = temp.resolve("input");
            YouTubeImport.download(video, 2, cache, temp, input, MediaDecoder.decoder(cache, s -> {}), plan, s -> {});
            var clip = MediaDecoder.decode(input.toString(), 1, 1, 2, 128, false, cache, s -> {});
            return new Result(name, version, clip.count() > 0, clip.count() + " frames imported");
        } catch (Exception e) {
            Path errors = temp.resolve("youtube-errors.txt");
            String log = Files.isRegularFile(errors)
                    ? Files.readString(errors, StandardCharsets.UTF_8).strip()
                    : "";
            return new Result(
                    name,
                    version,
                    false,
                    (log.isEmpty()
                            ? e.getMessage()
                            : log.lines().reduce((a, b) -> b).orElse(log)));
        } finally {
            MediaDecoder.deleteTree(temp);
        }
    }
}
