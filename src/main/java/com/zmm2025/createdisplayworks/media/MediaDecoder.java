package com.zmm2025.createdisplayworks.media;

import com.zmm2025.createdisplayworks.ClientConfig;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;

/** Client-side bounded download and decoding; audio and excess source frames are discarded. */
public final class MediaDecoder {
    private static final String FORMATS =
            "png_pipe,jpeg_pipe,gif,mov,matroska,webm,webp_pipe,bmp_pipe,image2,image2pipe,apng,ico,webp";
    private static final String VERSION = "8.0.1-1.5.13";
    private static final int DECODER_MB = 30;
    private static final long MAX_BYTES = 100L * 1024 * 1024;
    private static final ScheduledThreadPoolExecutor TIMEOUTS = new ScheduledThreadPoolExecutor(1, r -> {
        var t = new Thread(r, "media-download-timeout");
        t.setDaemon(true);
        return t;
    });
    // Cancelled watchdogs would otherwise stay queued and hold their download stream.
    static {
        TIMEOUTS.setRemoveOnCancelPolicy(true);
    }

    private static final Duration STALL = Duration.ofSeconds(30);
    private static final Map<String, String> HASHES = Map.of(
            "windows-x86_64", "cb570de36a555156e22cac3ddb21be9051e6b3df05142a29445773ea0e7fedb9",
            "linux-x86_64", "01da6cce8fb8686abd4a0be940edafcc199255cc97b37b28c49d4f696c1d9ef3",
            "linux-arm64", "2810546c9c82154ba9a389cdbdb1135db138604b04735806caa8b3cd5211aa50",
            "macosx-x86_64", "87ada23333d9bbb392e47ae52f5fbf41a4a6fd10817b18dd081a2f3f7dc9e956",
            "macosx-arm64", "57b4cb109b60e246e0d55734bf83c9883bf9424df42816fa06af945549dba094");

    private MediaDecoder() {}

    private static String filename(String source) {
        if (YouTubeImport.matches(source)) return MediaClip.YOUTUBE;
        try {
            return Path.of(source.startsWith("http") ? URI.create(source).getPath() : source)
                    .getFileName()
                    .toString();
        } catch (Exception e) {
            return MediaClip.UNNAMED;
        }
    }

    private static HttpClient http() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    static void download(URI uri, Path target) throws Exception {
        download(uri, target, MAX_BYTES, Duration.ofSeconds(90));
    }
    /** Downloads at most {@code maxBytes}, giving up after {@code limit} or when no data arrives for {@link #STALL}. */
    static void download(URI uri, Path target, long maxBytes, Duration limit) throws Exception {
        if (!Set.of("https", "http").contains(uri.getScheme()) || uri.getUserInfo() != null)
            throw new IOException("Use a direct HTTP or HTTPS media link.");
        try (var client = http()) {
            HttpResponse<InputStream> response;
            try {
                response = client.send(
                        HttpRequest.newBuilder(uri)
                                .timeout(Duration.ofSeconds(60))
                                .header("User-Agent", "Create-Display-Works")
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                throw new MediaImportException(
                        MediaImportException.Reason.NETWORK,
                        "Cannot reach " + uri.getHost() + ": " + e,
                        uri.getHost(),
                        e);
            }
            try (var input = response.body()) {
                if (response.statusCode() != 200)
                    throw new IOException("Download returned HTTP " + response.statusCode());
                long deadline = System.nanoTime() + limit.toNanos();
                var lastData = new AtomicLong(System.nanoTime());
                var expired = new AtomicBoolean();
                // A stalled connection blocks read() indefinitely, so closing the stream is the only way to stop it.
                var watchdog = TIMEOUTS.scheduleWithFixedDelay(
                        () -> {
                            long now = System.nanoTime();
                            if (now > deadline || now - lastData.get() > STALL.toNanos()) {
                                expired.set(true);
                                try {
                                    input.close();
                                } catch (IOException ignored) {
                                }
                            }
                        },
                        1,
                        1,
                        TimeUnit.SECONDS);
                try (var output = Files.newOutputStream(target)) {
                    byte[] buffer = new byte[16384];
                    long total = 0;
                    for (int n; (n = input.read(buffer)) != -1; ) {
                        lastData.set(System.nanoTime());
                        if ((total += n) > maxBytes)
                            throw new MediaImportException(
                                    MediaImportException.Reason.TOO_LARGE,
                                    "Download exceeds " + maxBytes / 1024 / 1024 + " MB.");
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                        output.write(buffer, 0, n);
                    }
                    if (expired.get()) throw new IOException("Download stopped");
                } catch (MediaImportException | InterruptedIOException e) {
                    throw e;
                } catch (IOException e) {
                    if (expired.get())
                        throw new MediaImportException(
                                MediaImportException.Reason.TIMED_OUT,
                                "Download from " + uri.getHost() + " exceeded the time limit or stalled.");
                    throw new MediaImportException(
                            MediaImportException.Reason.NETWORK,
                            "Download from " + uri.getHost() + " failed: " + e,
                            uri.getHost(),
                            e);
                } finally {
                    watchdog.cancel(false);
                }
            }
        }
    }
    /** This JVM's OS and CPU in JavaCPP's naming, such as windows-x86_64 or macosx-arm64. */
    static String platform() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT),
                arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String cpu = arch.equals("aarch64") || arch.equals("arm64")
                ? "arm64"
                : arch.equals("amd64") || arch.equals("x86_64") ? "x86_64" : arch;
        return (os.contains("win") ? "windows" : os.contains("mac") ? "macosx" : "linux") + "-" + cpu;
    }

    private static Path decoderFolder(Path cache) {
        return cache.resolve("ffmpeg-" + VERSION + "-" + platform());
    }
    /**
     * Asks the player before an import downloads programs, and returns whether they agreed. {@code update} is true when
     * every download replaces an older copy the player already has.
     */
    @FunctionalInterface
    public interface DownloadConsent {
        boolean approve(int megabytes, boolean update) throws InterruptedException;
    }
    /**
     * Stops when the player turned Media Tools off, and asks before downloading the programs this import needs. For a
     * YouTube link it returns the yt-dlp and Deno releases the player agreed to, otherwise null.
     */
    private static YouTubeImport.Plan askForTools(
            String source, Path cache, DownloadConsent consent, Consumer<String> status) throws Exception {
        if (ClientConfig.SPEC.isLoaded() && !ClientConfig.MEDIA_TOOLS.get())
            throw new MediaImportException(
                    MediaImportException.Reason.TOOLS_OFF, "Media tools are turned off in the client config.");
        boolean decoder = !Files.isRegularFile(decoderFolder(cache).resolve(MediaTools.MANIFEST));
        int size = decoder ? DECODER_MB : 0;
        YouTubeImport.Plan tools = null;
        // Before the player first agrees to YouTube tools, the size comes from the built-in list without contacting
        // GitHub.
        boolean usedBefore = source != null && YouTubeImport.matches(source) && YouTubeImport.usedBefore(cache);
        if (source != null && YouTubeImport.matches(source)) {
            tools = YouTubeImport.plan(cache, usedBefore, status);
            size += tools.megabytes();
        }
        if (size > 0 && !consent.approve(size, !decoder && tools != null && tools.update()))
            throw new MediaImportException(
                    MediaImportException.Reason.DECLINED, "The player chose not to download media tools.");
        // Agreeing to install the tools covers the newest release, which only GitHub's list knows.
        if (tools != null && !usedBefore) tools = YouTubeImport.plan(cache, true, status);
        return tools;
    }

    static Path decoder(Path cache, Consumer<String> status) throws Exception {
        if (ClientConfig.SPEC.isLoaded() && !ClientConfig.MEDIA_TOOLS.get())
            throw new MediaImportException(
                    MediaImportException.Reason.TOOLS_OFF, "Media tools are turned off in the client config.");
        String platform = platform();
        boolean windows = platform.startsWith("windows");
        if (!HASHES.containsKey(platform))
            throw new MediaImportException(
                    MediaImportException.Reason.UNSUPPORTED,
                    "No bundled decoder is available for " + platform,
                    platform,
                    null);
        Path root = decoderFolder(cache), exe = root.resolve(windows ? "ffmpeg.exe" : "ffmpeg");
        Path ready = MediaTools.ready(root, exe.getFileName().toString());
        if (ready != null) return ready;
        status.accept("decoder");
        Files.createDirectories(root);
        Path archive = Files.createTempFile(cache, "decoder-", ".jar");
        try {
            MediaTools.fetch(
                    "https://repo.maven.apache.org/maven2/org/bytedeco/ffmpeg/" + VERSION + "/ffmpeg-" + VERSION + "-"
                            + platform + ".jar",
                    archive,
                    HASHES.get(platform));
            String prefix = "org/bytedeco/ffmpeg/" + platform + "/";
            try (var zip = new ZipInputStream(Files.newInputStream(archive))) {
                for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                    if (e.isDirectory() || !e.getName().startsWith(prefix)) continue;
                    Path output =
                            root.resolve(e.getName().substring(prefix.length())).normalize();
                    if (!output.getParent().equals(root)) throw new IOException("Invalid decoder archive path.");
                    Files.copy(zip, output, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (!windows && !exe.toFile().setExecutable(true))
                throw new IOException("Cannot make the decoder executable.");
            MediaTools.seal(root);
            return exe;
        } finally {
            deleteTree(archive);
        }
    }

    public static MediaClip decode(
            String source,
            int columns,
            int rows,
            int seconds,
            int threshold,
            boolean invert,
            Path cache,
            Consumer<String> status)
            throws Exception {
        return decode(source, columns, rows, seconds, threshold, invert, Scale.STRETCH, false, cache, status);
    }

    public enum Scale {
        STRETCH,
        FIT,
        FILL
    }

    public static MediaClip decode(
            String source,
            int columns,
            int rows,
            int seconds,
            int threshold,
            boolean invert,
            Scale scale,
            boolean dither,
            Path cache,
            Consumer<String> status)
            throws Exception {
        return decode(
                source,
                columns,
                rows,
                seconds,
                threshold,
                invert,
                scale,
                dither,
                cache,
                status,
                (megabytes, update) -> true);
    }

    public static MediaClip decode(
            String source,
            int columns,
            int rows,
            int seconds,
            int threshold,
            boolean invert,
            Scale scale,
            boolean dither,
            Path cache,
            Consumer<String> status,
            DownloadConsent consent)
            throws Exception {
        cache = cache.toAbsolutePath().normalize();
        if (columns < 1 || rows < 1 || columns > 32 || rows > 32 || columns * rows > MediaClip.MAX_TILES)
            throw new IllegalArgumentException("Media canvases support at most 256 blocks.");
        if (seconds < 1 || seconds > 60 || threshold < 0 || threshold > 255)
            throw new IllegalArgumentException("Invalid import settings");
        Path temp;
        try {
            Files.createDirectories(cache);
            sweep(cache);
            temp = Files.createTempDirectory(cache, "import-");
        } catch (IOException e) {
            throw new MediaImportException(
                    MediaImportException.Reason.CACHE, "Cannot write to " + cache + ": " + e, cache.toString(), e);
        }
        Process process = null;
        try {
            Path input = temp.resolve("input"), raw = temp.resolve("frames.raw"), errors = temp.resolve("decode.txt");
            status.accept("loading");
            if (YouTubeImport.matches(source)) {
                var tools = askForTools(source, cache, consent, status);
                YouTubeImport.download(source, seconds, cache, temp, input, decoder(cache, status), tools, status);
            } else if (source.startsWith("https://") || source.startsWith("http://"))
                download(URI.create(source), input);
            else {
                Path file = Path.of(source);
                if (!Files.isRegularFile(file))
                    throw new MediaImportException(
                            MediaImportException.Reason.NOT_FOUND, "No media file exists at " + file);
                if (Files.size(file) > MAX_BYTES)
                    throw new MediaImportException(
                            MediaImportException.Reason.TOO_LARGE, "Choose a media file smaller than 100 MB.");
                Files.copy(file, input);
            }
            int w = columns * 8, h = rows * 8;
            byte[] header;
            try (var in = Files.newInputStream(input)) {
                header = in.readNBytes(65536);
            }
            String brand = new String(header, StandardCharsets.ISO_8859_1);
            if (brand.stripLeading().startsWith("<") && brand.contains("<svg")) {
                SvgRasterizer.convert(input, temp.resolve("svg.png"));
                input = temp.resolve("svg.png");
                header = Files.readAllBytes(input);
                brand = new String(header, StandardCharsets.ISO_8859_1);
            }
            if (brand.startsWith("RIFF") && brand.contains("ANIM"))
                return WebpAnimation.decode(
                        input, columns, rows, seconds, threshold, invert, scale, dither, filename(source));
            // Animated WebP is read without programs, so only now is it known whether this import needs them.
            askForTools(null, cache, consent, status);
            Path executable = decoder(cache, status);
            status.accept("converting");
            boolean still = header.length > 2
                    && ((header[0] == (byte) 0x89 && !brand.contains("acTL"))
                            || header[0] == (byte) 0xFF
                            || brand.contains("heic")
                            || brand.contains("heix")
                            || brand.contains("mif1")
                            || brand.contains("avif")
                            || brand.startsWith("BM")
                            || (header[0] == 0 && header[1] == 0 && header[2] == 1)
                            || (brand.startsWith("RIFF") && !brand.contains("ANIM")));
            // Probe the first decoded frame, including rotation, using the same bounded decoder.
            Path first = temp.resolve("first.png");
            String bound =
                    "scale=w='min(256,iw)':h='min(256,ih)':force_original_aspect_ratio=decrease,scale=w='max(1,trunc(iw*min(1,sqrt(16384/(iw*ih)))))':h='max(1,trunc(ih*min(1,sqrt(16384/(iw*ih)))))'";
            var probe = new ProcessBuilder(
                    executable.toString(),
                    "-nostdin",
                    "-hide_banner",
                    "-loglevel",
                    "error",
                    "-y",
                    "-max_alloc",
                    "67108864",
                    "-protocol_whitelist",
                    "file,pipe",
                    "-format_whitelist",
                    FORMATS,
                    "-threads",
                    "2",
                    "-i",
                    input.toString(),
                    "-an",
                    "-sn",
                    "-dn",
                    "-vf",
                    bound,
                    "-frames:v",
                    "1",
                    "-threads",
                    "2",
                    first.toString());
            probe.environment().put("LD_LIBRARY_PATH", executable.getParent().toString());
            probe.environment().put("DYLD_LIBRARY_PATH", executable.getParent().toString());
            process = MediaTools.start(
                    probe.redirectError(errors.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD),
                    executable.getParent());
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                // Unreadable media is the usual cause, but a decoder library removed since this session's check fails
                // the same way.
                MediaTools.recheck(executable.getParent());
                throw new IOException("Cannot read this media.");
            }
            var firstImage = ImageIO.read(first.toFile());
            if (firstImage == null) throw new IOException("Cannot read this image.");
            w = firstImage.getWidth();
            h = firstImage.getHeight();
            firstImage.flush();
            String filter = (still ? "" : "fps=" + MediaClip.FPS + ":eof_action=pass,") + "format=rgba,scale=" + w + ":"
                    + h + ":flags=area,format=rgba";
            var builder = new ProcessBuilder(
                    executable.toAbsolutePath().toString(),
                    "-nostdin",
                    "-hide_banner",
                    "-loglevel",
                    "error",
                    "-y",
                    "-max_alloc",
                    "67108864",
                    "-protocol_whitelist",
                    "file,pipe",
                    "-format_whitelist",
                    FORMATS,
                    "-threads",
                    "2",
                    "-i",
                    input.toAbsolutePath().toString(),
                    "-an",
                    "-sn",
                    "-dn",
                    "-t",
                    Integer.toString(seconds),
                    "-vf",
                    filter,
                    "-frames:v",
                    Integer.toString(still ? 1 : seconds * MediaClip.FPS),
                    "-threads",
                    "2",
                    "-f",
                    "rawvideo",
                    raw.toAbsolutePath().toString());
            builder.environment()
                    .put(
                            "LD_LIBRARY_PATH",
                            executable.getParent().toAbsolutePath().toString());
            builder.environment()
                    .put(
                            "DYLD_LIBRARY_PATH",
                            executable.getParent().toAbsolutePath().toString());
            process = MediaTools.start(
                    builder.redirectError(errors.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD),
                    executable.getParent());
            if (!process.waitFor(90, TimeUnit.SECONDS)) throw new IOException("Media conversion exceeded 90 seconds.");
            if (process.exitValue() != 0 || !Files.exists(raw) || Files.size(raw) == 0) {
                System.err.println("Flip-dot decoder: " + Files.readString(errors));
                throw new IOException(
                        "Cannot decode this media. Check the file format and try a direct media link or a public YouTube video.");
            }
            long size = Files.size(raw);
            if (size > (long) w * h * 4 * MediaClip.MAX_FRAMES || size % (w * h * 4) != 0)
                throw new IOException("Decoded media exceeds the frame limit.");
            byte[] pixels = Files.readAllBytes(raw);
            var original = EditableMedia.fromRgba(w, h, pixels, columns, rows);
            return original.render(
                    columns, rows, filename(source), new MediaSettings(threshold, invert, dither, scale, seconds));
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                // A cancelled import is interrupted, and waiting must not skip the cleanup below.
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            deleteTree(temp);
        }
    }

    private static final AtomicBoolean SWEPT = new AtomicBoolean();
    /** Once per session, removes scratch folders and decoder downloads that a crash or a locked file left behind. */
    private static void sweep(Path cache) {
        if (SWEPT.getAndSet(true)) return;
        long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1);
        try (var entries = Files.list(cache)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (!(name.startsWith("import-") && Files.isDirectory(entry))
                        && !(name.startsWith("decoder-") && name.endsWith(".jar"))) continue;
                try {
                    if (Files.getLastModifiedTime(entry).toMillis() < cutoff) deleteTree(entry);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }
    /** Deletes what it can, innermost first, and never throws, so cleanup can't replace an import's real result. */
    static void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
        } catch (IOException | UncheckedIOException ignored) {
        }
    }
}
