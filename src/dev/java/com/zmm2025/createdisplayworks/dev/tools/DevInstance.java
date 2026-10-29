package com.zmm2025.createdisplayworks.dev.tools;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One dev client's place among several: each worktree runs its own game, with its own copy of the test world, so
 * threads don't fight over one window. A game an agent starts stays hidden until someone shows it. A worktree's game
 * hides again when minimized, quits after a while with no agent activity while hidden, and quits once its worktree is
 * removed, deleting what's left of the worktree and its own game folder once it has exited.
 */
final class DevInstance {
    private static final Logger LOGGER = LoggerFactory.getLogger("CSoL Dev Tools");
    /** Set for a game in a worktree, as opposed to the main checkout's game with the shared world. */
    static final boolean WORKTREE_INSTANCE = Boolean.getBoolean("csol.dev.worktreeInstance");
    private static final long IDLE_LIMIT_MS = TimeUnit.MINUTES.toMillis(Long.getLong("csol.dev.idleMinutes", 30));
    /** The frame rate a hidden game draws at; nobody sees it, and it shouldn't slow down the user's own game. */
    private static final int HIDDEN_FPS = 30;
    private static volatile boolean hidden = Boolean.getBoolean("csol.dev.hidden");
    private static volatile long lastRequest = System.currentTimeMillis();
    private static boolean closing;
    private static int checkCount;

    private DevInstance() {}

    /** Records where this game's control API listens and starts the background checks. Runs on the render thread. */
    static void started(int port, String worktree) {
        Minecraft minecraft = Minecraft.getInstance();
        JsonObject info = new JsonObject();
        info.addProperty("port", port);
        info.addProperty("pid", ProcessHandle.current().pid());
        info.addProperty("worktree", worktree);
        info.addProperty("gameDirectory", minecraft.gameDirectory.toPath().toAbsolutePath().normalize().toString());
        // Gradle finds the game through its game directory; agents look in their worktree's build folder.
        write(minecraft.gameDirectory.toPath().resolve("dev-instance.json"), info);
        if(!worktree.isEmpty()) write(Path.of(worktree, "build", "dev-client.json"), info);
        if(hidden) throttle(minecraft.getWindow(), true);
        if(WORKTREE_INSTANCE) {
            // A minimized window stops drawing, which would break screenshots, so minimizing hides it instead.
            GLFW.glfwSetWindowIconifyCallback(minecraft.getWindow().getWindow(), (window, iconified) -> {
                if(iconified) minecraft.execute(() -> setHidden(true));
            });
        }
        ScheduledExecutorService checks = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "CSoL Dev Instance");
            thread.setDaemon(true);
            return thread;
        });
        checks.scheduleWithFixedDelay(() -> check(worktree), 2, 2, TimeUnit.SECONDS);
    }

    /** Called for every control API request, so a game that agents still use isn't stopped for being idle. */
    static void touch() {
        lastRequest = System.currentTimeMillis();
    }

    static boolean hidden() {
        return hidden;
    }

    /** Shows the window and brings it to the front, or hides it again. */
    static JsonObject window(boolean show) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.submit(() -> setHidden(!show)).get(1, TimeUnit.MINUTES);
        JsonObject response = DevTools.ok();
        response.addProperty("hidden", !show);
        return response;
    }

    private static void setHidden(boolean hide) {
        Minecraft minecraft = Minecraft.getInstance();
        Window window = minecraft.getWindow();
        long handle = window.getWindow();
        if(hide) {
            if(GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE) GLFW.glfwRestoreWindow(handle);
            GLFW.glfwHideWindow(handle);
        } else {
            GLFW.glfwShowWindow(handle);
            if(GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE) GLFW.glfwRestoreWindow(handle);
            GLFW.glfwFocusWindow(handle);
        }
        hidden = hide;
        throttle(window, hide);
    }

    /**
     * A hidden game is muted and draws at a lower frame rate without vsync, because some systems stall vsync for
     * windows that aren't on screen. Showing it restores the player's settings.
     */
    private static void throttle(Window window, boolean hide) {
        Minecraft minecraft = Minecraft.getInstance();
        window.updateVsync(!hide && minecraft.options.enableVsync().get());
        if(!hide) {
            window.setFramerateLimit(minecraft.options.framerateLimit().get());
            minecraft.getSoundManager().updateSourceVolume(SoundSource.MASTER, minecraft.options.getSoundSourceVolume(SoundSource.MASTER));
        }
        tick();
    }

    /** Keeps a hidden game muted and throttled; loading resources resets both. Runs every client tick. */
    static void tick() {
        if(!hidden) return;
        Minecraft minecraft = Minecraft.getInstance();
        Window window = minecraft.getWindow();
        int limit = Math.min(HIDDEN_FPS, minecraft.options.framerateLimit().get());
        if(window.getFramerateLimit() != limit) window.setFramerateLimit(limit);
        minecraft.getSoundManager().updateSourceVolume(SoundSource.MASTER, 0);
    }

    private static void check(String worktree) {
        Minecraft minecraft = Minecraft.getInstance();
        Path gameDirectory = minecraft.gameDirectory.toPath().toAbsolutePath().normalize();
        if(checkCount++ % 30 == 0) removeOrphans(WORKTREE_INSTANCE ? gameDirectory.getParent().getParent() : gameDirectory);
        if(closing) return;
        String reason = null;
        // Removing a worktree deletes its .git file first. On Windows the rest can't go while this game has files in
        // it open, so a helper deletes it once the game has exited.
        if(WORKTREE_INSTANCE && !worktree.isEmpty() && !Files.exists(Path.of(worktree, ".git"))) {
            reason = "its worktree was removed";
            startCleanup(Path.of(worktree), gameDirectory);
        } else if(hidden && WORKTREE_INSTANCE && System.currentTimeMillis() - lastRequest > IDLE_LIMIT_MS)
            reason = "it was hidden with no agent activity for " + TimeUnit.MILLISECONDS.toMinutes(IDLE_LIMIT_MS) + " minutes";
        if(reason == null) return;
        closing = true;
        LOGGER.info("Closing the dev client because {}", reason);
        minecraft.execute(minecraft::stop);
    }

    /**
     * Deletes the game folders of worktrees removed while their game wasn't running. Every running game does this
     * once a minute, so the folders go soon after their worktrees.
     */
    private static void removeOrphans(Path runDirectory) {
        File[] folders = runDirectory.resolve("worktrees").toFile().listFiles(File::isDirectory);
        if(folders == null) return;
        for(File folder : folders) {
            try {
                Path owner = folder.toPath().resolve("dev-worktree.txt");
                if(!Files.exists(owner)) continue;
                Path worktree = Path.of(Files.readString(owner, StandardCharsets.UTF_8).trim());
                if(Files.exists(worktree.resolve(".git")) || running(folder.toPath())) continue;
                LOGGER.info("Deleting the game folder of removed worktree {}", worktree);
                deleteTree(folder.toPath());
            } catch(IOException e) {
                LOGGER.warn("Could not delete the game folder {}", folder, e);
            }
        }
    }

    /** Whether a game is running in this folder; the game holds the lock until it exits. */
    private static boolean running(Path gameDirectory) {
        Path lock = gameDirectory.resolve("dev-client.lock");
        if(!Files.exists(lock)) return false;
        try(FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE)) {
            FileLock held = channel.tryLock();
            if(held == null) return true;
            held.release();
            return false;
        } catch(OverlappingFileLockException | IOException e) {
            return true;
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try(var paths = Files.walk(root)) {
            List<Path> all = paths.toList();
            for(int i = all.size() - 1; i >= 0; i--) Files.deleteIfExists(all.get(i));
        }
    }

    /**
     * Starts a separate process that waits for this game to exit, then deletes what's left of its removed worktree and
     * this game folder, and has git forget the worktree. It runs from a temporary copy of {@link #CLEANUP}, because
     * the classes this game runs from are in the worktree being deleted.
     */
    private static void startCleanup(Path worktree, Path gameDirectory) {
        try {
            Path script = Files.createTempDirectory("csol-dev-cleanup").resolve("DevCleanup.java");
            Files.writeString(script, CLEANUP, StandardCharsets.UTF_8);
            Path java = Path.of(ProcessHandle.current().info().command().orElse("java"));
            boolean windows = System.getProperty("os.name").startsWith("Windows");
            List<String> command = new ArrayList<>();
            if(windows) {
                // javaw has no console, so closing the terminal the game ran in doesn't stop it.
                Path javaw = java.resolveSibling("javaw.exe");
                command.add((Files.exists(javaw) ? javaw : java).toString());
            } else {
                command.add("nohup");
                command.add(java.toString());
            }
            // The game folder is <main checkout>/run/worktrees/<name>.
            Path checkout = gameDirectory.getParent().getParent().getParent();
            command.addAll(List.of(script.toString(), Long.toString(ProcessHandle.current().pid()), worktree.toString(),
                    gameDirectory.toString(), checkout.toString()));
            new ProcessBuilder(command).directory(script.getParent().toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(new File(windows ? "NUL" : "/dev/null")))
                    .redirectOutput(script.resolveSibling("cleanup.log").toFile()).redirectErrorStream(true).start();
            LOGGER.info("Started the cleanup of removed worktree {}; its log is {}", worktree, script.resolveSibling("cleanup.log"));
        } catch(IOException e) {
            LOGGER.warn("Could not start the cleanup of removed worktree {}; the next launch or reload deletes it", worktree, e);
        }
    }

    /** Source of the cleanup process; see {@link #startCleanup}. Arguments: game pid, worktree, game folder, main checkout. */
    private static final String CLEANUP = """
            import java.io.IOException;
            import java.nio.file.*;
            import java.nio.file.attribute.BasicFileAttributes;

            public class DevCleanup {
                public static void main(String[] args) throws Exception {
                    ProcessHandle.of(Long.parseLong(args[0])).ifPresent(game -> game.onExit().join());
                    Path worktree = Path.of(args[1]), gameDirectory = Path.of(args[2]);
                    // Other processes, such as Gradle, may hold files in the worktree for a while after the game exits.
                    for(int attempt = 0; attempt < 150 && (Files.exists(worktree) || Files.exists(gameDirectory)); attempt++) {
                        if(attempt > 0) Thread.sleep(2000);
                        // Never touch a worktree that still has its .git file.
                        if(!Files.exists(worktree.resolve(".git"))) delete(worktree);
                        delete(gameDirectory);
                    }
                    System.out.println(Files.exists(worktree) || Files.exists(gameDirectory) ? "Gave up; files are still in use" : "Deleted");
                    new ProcessBuilder("git", "-C", args[3], "worktree", "prune").inheritIO().start().waitFor();
                }

                private static void delete(Path root) throws IOException {
                    if(!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
                    Files.walkFileTree(root, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            // A junction or link: delete the link, never what it points to.
                            if(!attrs.isOther() && !attrs.isSymbolicLink()) return FileVisitResult.CONTINUE;
                            tryDelete(dir);
                            return FileVisitResult.SKIP_SUBTREE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            tryDelete(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException e) {
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                            tryDelete(dir);
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }

                private static void tryDelete(Path path) {
                    try {
                        Files.deleteIfExists(path);
                    } catch(IOException ignored) {
                        // Still in use; the next attempt tries again.
                    }
                }
            }
            """;

    private static void write(Path file, JsonObject json) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, json.toString(), StandardCharsets.UTF_8);
        } catch(IOException e) {
            LOGGER.warn("Could not write {}", file, e);
        }
    }
}
