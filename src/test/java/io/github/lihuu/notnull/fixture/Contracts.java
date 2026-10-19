package io.github.lihuu.notnull.fixture;

import org.jetbrains.annotations.NotNull;

/**
 * A stand-in for a compiled type whose {@code @NotNull} contracts nothing has
 * injected yet.
 *
 * <p>The build never instruments test classes, so this file's compiled form is
 * what the pass sees before it has run once. Copies of it are the only way to
 * observe a first pass that has something to do, which is what makes the second
 * pass meaningful.
 *
 * <p>It references nothing outside {@code java.base}, so the instrumented copy
 * can be loaded by a class loader whose parent is the platform loader — the
 * uninstrumented class on this test's own class path therefore cannot shadow it.
 */
public final class Contracts {

    private final String text;

    public Contracts(@NotNull String text) {
        this.text = text;
    }

    public @NotNull String echo(@NotNull String text) {
        return text;
    }

    /** Declares a non-null return and then breaks it; javac does not refuse this. */
    public static @NotNull String broken() {
        return null;
    }

    /** No contract, so the pass must leave it alone. */
    public static String plain(String text) {
        return text;
    }
}
