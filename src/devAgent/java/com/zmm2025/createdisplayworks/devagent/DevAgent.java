package com.zmm2025.createdisplayworks.devagent;

import java.lang.instrument.Instrumentation;

/**
 * Development-only {@code -javaagent} that keeps the JVM's {@link Instrumentation} so the dev tools can redefine
 * classes in the running game. It is loaded by the system class loader, so the game looks it up reflectively.
 */
public final class DevAgent {
    public static volatile Instrumentation instrumentation;

    private DevAgent() {}

    public static void premain(String args, Instrumentation inst) {
        instrumentation = inst;
    }
}
