package io.github.lihuu.notnull;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The pass, exercised on classes it has not seen before and on the classes it
 * produced.
 *
 * <p>Every test starts from a copy of a fixture class taken off the test class
 * path, so the pass always runs against bytecode no one has rewritten. The copy
 * is then loaded by a class loader whose parent is the platform loader, which is
 * what makes the *instrumented* copy the only copy the JVM can find.
 */
class NotNullInstrumenterTest {

    private static final String CONTRACTS = "io.github.lihuu.notnull.fixture.Contracts";
    private static final String PLAIN = "io.github.lihuu.notnull.fixture.Plain";

    @TempDir
    Path work;

    @Test
    void rejectsNullForAnInstrumentedParameter() throws Exception {
        // Without the injected check this constructor stores null and says nothing.
        // An IllegalArgumentException naming the parameter is the contract.
        Class<?> contracts = instrumentAndLoad(CONTRACTS);
        Constructor<?> constructor = contracts.getConstructor(String.class);

        InvocationTargetException failure = assertThrows(
                InvocationTargetException.class,
                () -> constructor.newInstance((Object) null));

        IllegalArgumentException cause =
                assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertEquals(
                "Argument for @NotNull parameter 'text' of " + CONTRACTS
                        + ".<init> must not be null",
                cause.getMessage());
    }

    @Test
    void leavesSatisfiedContractsAlone() throws Exception {
        Class<?> contracts = instrumentAndLoad(CONTRACTS);

        Object instance = contracts.getConstructor(String.class).newInstance("hello");

        assertEquals("hello", contracts.getMethod("echo", String.class).invoke(instance, "hello"));
        assertEquals("hello", contracts.getMethod("plain", String.class).invoke(null, "hello"));
    }

    @Test
    void reportsANullReturnValueOfAnInstrumentedMethod() throws Exception {
        Class<?> contracts = instrumentAndLoad(CONTRACTS);

        InvocationTargetException failure = assertThrows(
                InvocationTargetException.class,
                () -> contracts.getMethod("broken").invoke(null));

        IllegalStateException cause =
                assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertEquals(
                "@NotNull method " + CONTRACTS + ".broken must not return null",
                cause.getMessage());
    }

    @Test
    void injectsEachCheckOnceAndReportsWhatItDid() throws IOException {
        Path classes = work.resolve("classes");
        copyFixture(classes, CONTRACTS);

        NotNullInstrumenter.Result first = NotNullInstrumenter.instrument(classes);

        assertEquals(1, first.scanned());
        assertEquals(1, first.rewritten());
        // Contracts declares: one on the constructor parameter, one on echo's
        // parameter; echo and broken each return a value that might be null.
        assertEquals(2, first.parameterChecks());
        assertEquals(2, first.returnChecks());
        assertEquals(4, first.checkPoints());
    }

    @Test
    void rewritesAnAlreadyInstrumentedTreeOnASecondPassByDoingNothing() throws IOException {
        Path classes = work.resolve("classes");
        copyFixture(classes, CONTRACTS);
        NotNullInstrumenter.instrument(classes);
        byte[] afterFirstPass = Files.readAllBytes(classes.resolve(CONTRACTS.replace('.', '/') + ".class"));

        NotNullInstrumenter.Result second = NotNullInstrumenter.instrument(classes);

        // The generated check method is the marker: a class that carries one has
        // been rewritten, and rewriting it again would double every check.
        assertEquals(0, second.rewritten());
        assertEquals(0, second.checkPoints());
        assertArrayEquals(afterFirstPass,
                Files.readAllBytes(classes.resolve(CONTRACTS.replace('.', '/') + ".class")));
    }

    @Test
    void leavesAClassWithoutContractsByteForByteAlone() throws IOException {
        Path classes = work.resolve("classes");
        Path fixture = copyFixture(classes, PLAIN);
        byte[] before = Files.readAllBytes(fixture);

        NotNullInstrumenter.Result result = NotNullInstrumenter.instrument(classes);

        assertEquals(1, result.scanned());
        assertEquals(0, result.rewritten());
        assertArrayEquals(before, Files.readAllBytes(fixture));
    }

    @Test
    void refusesAPathThatIsNotAClassesDirectory() {
        assertThrows(IllegalArgumentException.class,
                () -> NotNullInstrumenter.instrument(work.resolve("missing")));
    }

    private Class<?> instrumentAndLoad(String binaryName) throws Exception {
        Path classes = work.resolve("classes");
        copyFixture(classes, binaryName);
        NotNullInstrumenter.instrument(classes);
        URLClassLoader loader = new URLClassLoader(
                new URL[] {classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        try {
            return Class.forName(binaryName, true, loader);
        } finally {
            loader.close();
        }
    }

    private Path copyFixture(Path classes, String binaryName) throws IOException {
        String resource = "/" + binaryName.replace('.', '/') + ".class";
        Path target = classes.resolve(resource.substring(1));
        Files.createDirectories(target.getParent());
        try (InputStream in = NotNullInstrumenterTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "fixture is not on the test class path: " + resource);
            Files.copy(in, target);
        }
        return target;
    }
}
