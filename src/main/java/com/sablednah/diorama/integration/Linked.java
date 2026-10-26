package com.sablednah.diorama.integration;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;

import com.sablednah.diorama.Diorama;
import org.jspecify.annotations.Nullable;

/**
 * Another mod's public API, reached by reflection, once.
 *
 * <p>Diorama must build and start without any of the mods it can draw, so it cannot compile
 * against them. Each integration looks up exactly the methods it needs when the server starts; if
 * any is missing — the mod is older than the API Diorama reads — the whole integration stays off
 * and {@link #status()} says which mod, which version it needs, and why, rather than a layer just
 * never appearing (the series rule: whenever correct behaviour is to show nothing, say so).</p>
 */
public final class Linked {

    private static final List<String> STATUS = new ArrayList<>();

    private Linked() {}

    /** What each integration decided this start, for {@code /diorama status}. */
    public static synchronized List<String> status() {
        return List.copyOf(STATUS);
    }

    static synchronized void report(String line) {
        STATUS.add(line);
        Diorama.LOGGER.info("Diorama: {}", line);
    }

    public static synchronized void clear() {
        STATUS.clear();
    }

    /** A static method, or null — logged by the caller as one failure for the whole integration. */
    static @Nullable MethodHandle find(Class<?> owner, String name, Class<?> returns, Class<?>... params) {
        try {
            return MethodHandles.publicLookup().findStatic(owner, name, MethodType.methodType(returns, params));
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    static @Nullable Class<?> type(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }
}
