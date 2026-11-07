package com.zmm2025.createdisplayworks.dev.tools;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import javax.management.ObjectName;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

/**
 * Loader-agnostic half of hot reload: diffs compiled output against what the game last loaded, redefines changed
 * classes through {@link Instrumentation}, and flags edits that only take effect after a restart.
 */
public final class HotReloader {
    /** Annotations whose edits are only read when the game starts. */
    private static final Set<String> STARTUP_ANNOTATIONS = Set.of(
            "Lnet/neoforged/fml/common/Mod;",
            "Lnet/neoforged/fml/common/EventBusSubscriber;",
            "Lorg/spongepowered/asm/mixin/Mixin;");

    private static final String SUBSCRIBE_EVENT = "Lnet/neoforged/bus/api/SubscribeEvent;";

    public record Changes(Map<String, byte[]> classes, Set<String> assets, Set<String> data) {
        public boolean isEmpty() {
            return classes.isEmpty() && assets.isEmpty() && data.isEmpty();
        }
    }

    public record Result(
            List<String> redefined, List<String> notYetLoaded, Changes changes, List<String> restartReasons) {}

    private final List<Path> classRoots, resourceRoots;
    private final Map<String, byte[]> loadedClasses;
    private final Map<String, Long> loadedResources;
    /** Restart reasons stay pending until the game restarts, so later reloads keep reporting them. */
    private final List<String> pendingRestart = new ArrayList<>();

    public HotReloader(List<Path> classRoots, List<Path> resourceRoots) {
        this.classRoots = classRoots;
        this.resourceRoots = resourceRoots;
        loadedClasses = readClasses();
        loadedResources = hashResources();
    }

    public synchronized Result reload(Instrumentation instrumentation, ClassLoader modLoader) {
        Map<String, byte[]> classes = readClasses();
        Map<String, Long> resources = hashResources();
        Map<String, byte[]> changedClasses = new TreeMap<>();
        classes.forEach((name, bytes) -> {
            if (!Arrays.equals(bytes, loadedClasses.get(name))) changedClasses.put(name, bytes);
        });
        Set<String> assets = new TreeSet<>(), data = new TreeSet<>(), resourcePaths = new HashSet<>(resources.keySet());
        resourcePaths.addAll(loadedResources.keySet());
        resourcePaths.forEach(path -> {
            if (Objects.equals(resources.get(path), loadedResources.get(path))) return;
            if (path.startsWith("assets/")) assets.add(path);
            else if (path.startsWith("data/")) data.add(path);
            else if (path.startsWith("META-INF/")) pendingRestart.add(path + " changed; it is only read at startup");
        });

        Map<String, Class<?>> loaded = new HashMap<>();
        for (Class<?> type : instrumentation.getAllLoadedClasses())
            if (type.getClassLoader() == modLoader && changedClasses.containsKey(type.getName()))
                loaded.put(type.getName(), type);
        List<String> redefined = new ArrayList<>(), notYetLoaded = new ArrayList<>();
        List<ClassDefinition> definitions = new ArrayList<>();
        List<String> unsafe = new ArrayList<>();
        Map<Class<?>, List<String>> initializedFields = new LinkedHashMap<>();
        changedClasses.forEach((name, bytes) -> {
            Class<?> type = loaded.get(name);
            byte[] previous = loadedClasses.get(name);
            if (previous != null) pendingRestart.addAll(startupOnlyEdits(name, previous, bytes, type != null));
            if (type == null) {
                notYetLoaded.add(name);
                return;
            }
            if (previous != null) unsafe.addAll(lambdaShifts(name, previous, bytes));
            List<String> fields = previous == null ? List.of() : addedInitializedFields(previous, bytes);
            if (!fields.isEmpty()) initializedFields.put(type, fields);
            definitions.add(new ClassDefinition(type, bytes));
            redefined.add(name);
        });
        if (!unsafe.isEmpty()) {
            // Swapping would leave running code and existing lambda objects pointing at the wrong or missing lambda
            // bodies, which crashes the game or runs the wrong code. Swap nothing and ask for a restart.
            redefined.clear();
            pendingRestart.addAll(unsafe);
        } else
            try {
                // Count before swapping, so a failed count can't leave the swap unreported.
                List<String> uninitialized = uninitializedFields(instrumentation, initializedFields);
                if (!definitions.isEmpty())
                    instrumentation.redefineClasses(definitions.toArray(ClassDefinition[]::new));
                loadedClasses.putAll(changedClasses);
                pendingRestart.addAll(uninitialized);
            } catch (Throwable e) {
                redefined.clear();
                pendingRestart.add("the JVM could not hot-swap " + definitions.size() + " class(es): " + e);
            }
        loadedResources.clear();
        loadedResources.putAll(resources);
        return new Result(
                redefined,
                notYetLoaded,
                new Changes(changedClasses, assets, data),
                List.copyOf(new LinkedHashSet<>(pendingRestart)));
    }

    /**
     * Edits that hot-swap fine but whose effect needs a restart: mixins, startup annotations, event subscriptions and,
     * for classes that already ran them, static initializers and mod constructors.
     */
    private static List<String> startupOnlyEdits(String name, byte[] before, byte[] after, boolean loaded) {
        ClassNode old = node(before), now = node(after);
        String simple = name.substring(name.lastIndexOf('.') + 1);
        List<String> reasons = new ArrayList<>();
        if (!annotations(old, STARTUP_ANNOTATIONS).equals(annotations(now, STARTUP_ANNOTATIONS))
                || annotations(now, STARTUP_ANNOTATIONS).contains("Lorg/spongepowered/asm/mixin/Mixin;"))
            reasons.add(simple + " is a mixin, @Mod or @EventBusSubscriber class whose startup wiring changed");
        if (!loaded) return reasons;
        if (!method(old, "<clinit>").equals(method(now, "<clinit>")))
            reasons.add(simple + " changed static initialization (static fields, registrations), which only runs once");
        if (!subscribers(old).equals(subscribers(now)))
            reasons.add(simple + " added or removed @SubscribeEvent handlers, which are only registered at startup");
        if (annotations(now, STARTUP_ANNOTATIONS).contains("Lnet/neoforged/fml/common/Mod;")
                && !method(old, "<init>").equals(method(now, "<init>")))
            reasons.add(simple + " changed the mod constructor, which only runs at startup");
        return reasons;
    }

    /**
     * Instance fields a class gained that its constructors give a starting value. Redefinition adds the field to
     * objects that already exist without running any constructor, so on those it stays null, 0 or false. Fields only
     * set to that default anyway ({@code = null}, {@code = 0}) are fine.
     */
    private static List<String> addedInitializedFields(byte[] before, byte[] after) {
        ClassNode old = node(before), now = node(after);
        Set<String> existing = new HashSet<>();
        for (FieldNode field : old.fields)
            if ((field.access & Opcodes.ACC_STATIC) == 0) existing.add(field.name + field.desc);
        Set<String> added = new HashSet<>();
        for (FieldNode field : now.fields)
            if ((field.access & Opcodes.ACC_STATIC) == 0 && !existing.contains(field.name + field.desc))
                added.add(field.name + field.desc);
        if (added.isEmpty()) return List.of();
        Set<String> initialized = new TreeSet<>();
        for (MethodNode method : now.methods) {
            if (!method.name.equals("<init>")) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof FieldInsnNode put)
                        || put.getOpcode() != Opcodes.PUTFIELD
                        || !put.owner.equals(now.name)) continue;
                if (added.contains(put.name + put.desc) && !storesDefault(put)) initialized.add(put.name);
            }
        }
        return List.copyOf(initialized);
    }

    /**
     * Whether the value a PUTFIELD stores is a constant null, 0 or false, which is what the field holds anyway. A label
     * before the PUTFIELD means branches meet there ({@code a > b}, {@code ready ? x : null}), so the instruction before
     * it is only one of the possible values.
     */
    private static boolean storesDefault(FieldInsnNode put) {
        AbstractInsnNode value = put.getPrevious();
        while (value != null && value.getOpcode() < 0) {
            if (value instanceof LabelNode) return false;
            value = value.getPrevious();
        }
        return value != null
                && switch (value.getOpcode()) {
                    case Opcodes.ACONST_NULL, Opcodes.ICONST_0, Opcodes.LCONST_0, Opcodes.FCONST_0, Opcodes.DCONST_0 ->
                        true;
                    default -> false;
                };
    }

    /**
     * Restart reasons for classes that gained initialized fields while objects of them (or of their subclasses) exist.
     * Classes nobody has created an object of yet are fine, because new objects run the new constructors.
     */
    private static List<String> uninitializedFields(
            Instrumentation instrumentation, Map<Class<?>, List<String>> fields) {
        if (fields.isEmpty()) return List.of();
        Map<String, Long> counts = liveInstances();
        Class<?>[] all = instrumentation.getAllLoadedClasses();
        List<String> reasons = new ArrayList<>();
        fields.forEach((type, names) -> {
            long objects = 0;
            for (Class<?> other : all)
                if (type.isAssignableFrom(other))
                    objects += counts == null ? 1 : counts.getOrDefault(other.getName(), 0L);
            if (objects == 0) return;
            String simple = type.getName().substring(type.getName().lastIndexOf('.') + 1);
            String list = names.stream().map(n -> "'" + n + "'").collect(Collectors.joining(", "));
            String existing = counts == null
                    ? "existing " + simple + " objects"
                    : "the " + objects + " existing " + simple + (objects == 1 ? " object" : " objects");
            reasons.add(simple + " added " + (names.size() == 1 ? "the field " : "the fields ") + list
                    + " with a starting value, but " + existing + " won't have " + (names.size() == 1 ? "it" : "them")
                    + " set and can crash. Restart, or re-create them (re-place the blocks, reopen the screens)");
        });
        return reasons;
    }

    /**
     * Live object count per class name, from the JVM's class histogram (what {@code jcmd GC.class_histogram} prints).
     * It runs a full GC, so it's only used when a reload adds initialized fields. Null if the JVM can't provide it.
     */
    private static Map<String, Long> liveInstances() {
        try {
            String histogram = (String) ManagementFactory.getPlatformMBeanServer()
                    .invoke(
                            new ObjectName("com.sun.management:type=DiagnosticCommand"),
                            "gcClassHistogram",
                            new Object[] {new String[0]},
                            new String[] {String[].class.getName()});
            Map<String, Long> counts = new HashMap<>();
            // Rows look like "   1:   12345   678900  com.example.Type (module@version)".
            for (String line : histogram.split("\\R")) {
                String[] columns = line.trim().split("\\s+");
                if (columns.length >= 4 && columns[0].endsWith(":"))
                    counts.merge(columns[3], Long.parseLong(columns[1]), Long::sum);
            }
            return counts;
        } catch (Exception | LinkageError e) {
            return null;
        }
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG);
        return node;
    }

    private static Set<String> annotations(ClassNode node, Set<String> wanted) {
        Set<String> found = new TreeSet<>();
        for (List<AnnotationNode> list : Arrays.asList(node.visibleAnnotations, node.invisibleAnnotations))
            if (list != null)
                for (AnnotationNode annotation : list) if (wanted.contains(annotation.desc)) found.add(annotation.desc);
        return found;
    }

    private static Set<String> subscribers(ClassNode node) {
        Set<String> found = new TreeSet<>();
        for (MethodNode method : node.methods)
            if (method.visibleAnnotations != null
                    && method.visibleAnnotations.stream().anyMatch(a -> a.desc.equals(SUBSCRIBE_EVENT)))
                found.add(method.name + method.desc);
        return found;
    }

    /**
     * javac numbers a class's lambda bodies in source order ({@code lambda$tick$3}), so adding or removing a lambda
     * renumbers the ones after it. Editing inside existing lambdas keeps their names and is safe to swap, and so is
     * appending lambdas after all existing ones. A removed lambda isn't, and neither is an added lambda alongside
     * changed existing bodies, because an insertion that shifts names looks exactly like an append plus edits.
     */
    private static List<String> lambdaShifts(String name, byte[] before, byte[] after) {
        Map<String, String> old = lambdas(node(before)), now = lambdas(node(after));
        Set<String> added = new TreeSet<>(now.keySet());
        added.removeAll(old.keySet());
        int lastOld =
                old.keySet().stream().mapToInt(HotReloader::lambdaIndex).max().orElse(-1);
        boolean edited = old.entrySet().stream()
                .anyMatch(e -> now.containsKey(e.getKey()) && !e.getValue().equals(now.get(e.getKey())));
        boolean unsafe = !now.keySet().containsAll(old.keySet())
                || !added.isEmpty() && (edited || added.stream().anyMatch(lambda -> lambdaIndex(lambda) <= lastOld));
        if (!unsafe) return List.of();
        return List.of(name.substring(name.lastIndexOf('.') + 1)
                + " added or removed lambdas, which renumbers the ones after them, so it can't be hot-swapped safely");
    }

    /** The class-wide counter javac puts at the end of a lambda's name, e.g. 3 for {@code lambda$tick$3()V}. */
    private static int lambdaIndex(String lambda) {
        String name = lambda.substring(0, lambda.indexOf('('));
        return Integer.parseInt(name.substring(name.lastIndexOf('$') + 1));
    }

    private static Map<String, String> lambdas(ClassNode node) {
        Map<String, String> lambdas = new HashMap<>();
        for (MethodNode method : node.methods)
            if (method.name.startsWith("lambda$"))
                lambdas.put(
                        method.name + method.desc, text(method).replaceAll("lambda\\$[\\w$]*?\\$\\d+", "lambda\\$"));
        return lambdas;
    }

    /** Bytecode listing of every overload of a method; constant-pool layout and line numbers don't affect it. */
    private static String method(ClassNode node, String name) {
        StringBuilder out = new StringBuilder();
        for (MethodNode method : node.methods) if (method.name.equals(name)) out.append(text(method));
        return out.toString();
    }

    private static String text(MethodNode method) {
        Textifier text = new Textifier();
        method.accept(new TraceMethodVisitor(text));
        StringWriter out = new StringWriter();
        text.print(new PrintWriter(out));
        return out.toString();
    }

    private Map<String, byte[]> readClasses() {
        Map<String, byte[]> classes = new HashMap<>();
        forEachFile(classRoots, (relative, file) -> {
            if (!relative.endsWith(".class")) return;
            String name =
                    relative.substring(0, relative.length() - ".class".length()).replace('/', '.');
            if (!name.endsWith("module-info") && !name.endsWith("package-info")) classes.put(name, read(file));
        });
        return classes;
    }

    private Map<String, Long> hashResources() {
        Map<String, Long> hashes = new HashMap<>();
        forEachFile(resourceRoots, (relative, file) -> {
            CRC32 crc = new CRC32();
            crc.update(read(file));
            hashes.put(relative, crc.getValue());
        });
        return hashes;
    }

    private static void forEachFile(List<Path> roots, BiConsumer<String, Path> action) {
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(Files::isRegularFile)
                        .forEach(file ->
                                action.accept(root.relativize(file).toString().replace('\\', '/'), file));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
