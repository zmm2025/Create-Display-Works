package com.zmm2025.createdisplayworks.media;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MediaToolsTest {
    @TempDir
    Path dir;

    @Test
    void damagedHelperFoldersAreDownloadedAgain() throws IOException {
        Path root = Files.createDirectories(dir.resolve("tool"));
        Files.writeString(root.resolve("tool.exe"), "program");
        Files.writeString(root.resolve("library.dll"), "library");
        MediaTools.seal(root);
        assertEquals(root.resolve("tool.exe"), MediaTools.ready(root, "tool.exe"));
        // Security software quarantining a library is the common case.
        Files.delete(root.resolve("library.dll"));
        MediaTools.recheck(root);
        assertNull(MediaTools.ready(root, "tool.exe"));
        assertFalse(Files.exists(root.resolve("verified")), "a damaged folder must stop counting as verified");
    }

    @Test
    void foldersFromBeforeManifestsAreDownloadedAgain() throws IOException {
        Path root = Files.createDirectories(dir.resolve("old"));
        Files.writeString(root.resolve("tool.exe"), "program");
        Files.writeString(root.resolve("verified"), "cb570de36a555156e22cac3ddb21be9051e6b3df05142a29445773ea0e7fedb9");
        assertNull(MediaTools.ready(root, "tool.exe"));
    }

    @Test
    void unreachableHostsAreNetworkErrors() throws IOException {
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        var error = assertThrows(
                MediaImportException.class,
                () -> MediaDecoder.download(
                        URI.create("http://127.0.0.1:" + port + "/media.png"), dir.resolve("media.png")));
        assertEquals(MediaImportException.Reason.NETWORK, error.reason());
        assertEquals("127.0.0.1", error.detail());
    }

    @Test
    void stalledDownloadsTimeOut() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var thread = new Thread(() -> {
                try (var client = server.accept()) {
                    client.getOutputStream()
                            .write("HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\n\r\npartial"
                                    .getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().flush();
                    Thread.sleep(20000);
                } catch (Exception ignored) {
                }
            });
            thread.setDaemon(true);
            thread.start();
            long start = System.nanoTime();
            var error = assertThrows(
                    MediaImportException.class,
                    () -> MediaDecoder.download(
                            URI.create("http://127.0.0.1:" + server.getLocalPort() + "/media.mp4"),
                            dir.resolve("media.mp4"),
                            1024 * 1024,
                            Duration.ofSeconds(2)));
            assertEquals(MediaImportException.Reason.TIMED_OUT, error.reason());
            assertTrue(
                    System.nanoTime() - start < Duration.ofSeconds(10).toNanos(),
                    "the stalled read must be interrupted");
        }
    }

    @Test
    void unwritableCachesAreReported() throws IOException {
        Path file = Files.writeString(dir.resolve("not-a-folder"), "");
        var error = assertThrows(
                MediaImportException.class,
                () -> MediaDecoder.decode(
                        dir.resolve("missing.png").toString(), 1, 1, 2, 128, false, file.resolve("cache"), s -> {}));
        assertEquals(MediaImportException.Reason.CACHE, error.reason());
    }
}
