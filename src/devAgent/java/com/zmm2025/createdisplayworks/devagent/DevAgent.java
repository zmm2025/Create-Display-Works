package com.zmm2025.createdisplayworks.devagent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Development-only {@code -javaagent}. It keeps the JVM's {@link Instrumentation} so the dev tools can redefine classes
 * in the running game; it is loaded by the system class loader, so the game looks it up reflectively.
 * <p>
 * It also teaches GLFW about keys and mouse buttons the control API holds. Code that reads the physical keyboard, such
 * as {@code InputConstants.isKeyDown}, Ponder's "hold W" and Create's train controls, ends up in {@code glfwGetKey}, and
 * a hidden window never receives real input. Mixins can't reach LWJGL, but an agent runs before it loads.
 */
public final class DevAgent {
    public static volatile Instrumentation instrumentation;
    /** GLFW key and mouse button codes held by the control API, kept in system properties; see {@link #patchGlfw}. */
    private static final String HELD_KEYS = "createdisplayworks.dev.heldKeys", HELD_BUTTONS = "createdisplayworks.dev.heldButtons";

    private DevAgent() {}

    public static void premain(String args, Instrumentation inst) {
        instrumentation = inst;
        System.getProperties().put(HELD_KEYS, ConcurrentHashMap.newKeySet());
        System.getProperties().put(HELD_BUTTONS, ConcurrentHashMap.newKeySet());
        inst.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefined, ProtectionDomain domain, byte[] bytes) {
                return "org/lwjgl/glfw/GLFW".equals(name) ? patchGlfw(bytes) : null;
            }
        });
    }

    /**
     * Makes {@code glfwGetKey} and {@code glfwGetMouseButton} answer {@code GLFW_PRESS} for held codes. GLFW's module
     * can't see this class, so the patch only uses {@code java.base}: the sets are shared through the system properties.
     */
    private static byte[] patchGlfw(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        Map<String, String> held = Map.of("glfwGetKey", HELD_KEYS, "glfwGetMouseButton", HELD_BUTTONS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                String property = held.get(name);
                if(property == null || !"(JI)I".equals(descriptor)) return method;
                return new MethodVisitor(Opcodes.ASM9, method) {
                    @Override public void visitCode() {
                        super.visitCode();
                        Label real = new Label();
                        visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperties", "()Ljava/util/Properties;", false);
                        visitLdcInsn(property);
                        visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Properties", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                        visitTypeInsn(Opcodes.CHECKCAST, "java/util/Set");
                        visitVarInsn(Opcodes.ILOAD, 2);
                        visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
                        visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "contains", "(Ljava/lang/Object;)Z", true);
                        visitJumpInsn(Opcodes.IFEQ, real);
                        visitInsn(Opcodes.ICONST_1);
                        visitInsn(Opcodes.IRETURN);
                        visitLabel(real);
                        visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }
}
