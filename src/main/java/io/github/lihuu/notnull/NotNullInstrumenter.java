package io.github.lihuu.notnull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/**
 * Build-time bytecode pass that enforces the {@code @NotNull} contracts a
 * compilation already declared, by rewriting an existing classes directory in
 * place.
 *
 * <p>{@code org.jetbrains.annotations.NotNull} has {@code CLASS} retention, so
 * nothing enforces it at runtime. This pass reads every {@code .class} file
 * under the given root, plans the checks each method needs
 * ({@link ContractPlanner}), and rewrites only the classes that have at least
 * one: a class with no {@code @NotNull} contract keeps its bytes and its
 * timestamp. A class the pass has already rewritten carries one of the check
 * methods it generated, so a build that reuses an existing classes directory —
 * {@code mvn test} followed by {@code mvn package} without a {@code clean} —
 * never injects the same check twice.
 *
 * <p>Each check is a call to a private static method this pass appends to the
 * class being instrumented, so no instrumented class gains a dependency on a
 * type of ours; this module is build-time only and is never itself instrumented.
 */
public final class NotNullInstrumenter {

    /** The annotation whose contracts are enforced: {@code org.jetbrains.annotations.NotNull}. */
    static final String NOT_NULL_DESCRIPTOR = "Lorg/jetbrains/annotations/NotNull;";

    private NotNullInstrumenter() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException(
                    "usage: notnull-instrumenter <classes-directory>");
        }
        Path classesRoot = Path.of(args[0]);
        Result result = instrument(classesRoot);
        System.out.println(result.describe(classesRoot));
    }

    /**
     * Instruments every class under {@code classesRoot}, rewriting in place the
     * ones that declare checks they do not carry yet.
     */
    public static Result instrument(Path classesRoot) throws IOException {
        if (!Files.isDirectory(classesRoot)) {
            throw new IllegalArgumentException("not a classes directory: " + classesRoot);
        }
        List<Path> classFiles = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(classesRoot)) {
            tree.filter(path -> path.getFileName().toString().endsWith(".class"))
                    .sorted()
                    .forEach(classFiles::add);
        }
        int rewritten = 0;
        int parameterChecks = 0;
        int returnChecks = 0;
        for (Path classFile : classFiles) {
            byte[] compiled = Files.readAllBytes(classFile);
            ClassReader reader = new ClassReader(compiled);
            ContractPlan plan = ContractPlanner.plan(reader);
            if (!plan.needsRewrite()) {
                continue;
            }
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            ContractInjector injector = new ContractInjector(writer, plan);
            reader.accept(injector, 0);
            Files.write(classFile, writer.toByteArray());
            rewritten++;
            parameterChecks += injector.parameterChecks();
            returnChecks += injector.returnChecks();
        }
        return new Result(classFiles.size(), rewritten, parameterChecks, returnChecks);
    }

    /** What one run over a classes directory did. */
    public record Result(int scanned, int rewritten, int parameterChecks, int returnChecks) {

        /** Every injected check, parameter and return value alike. */
        public int checkPoints() {
            return parameterChecks + returnChecks;
        }

        String describe(Path classesRoot) {
            return "notnull: " + classesRoot
                    + ": scanned " + scanned + " classes, rewrote " + rewritten
                    + ", injected " + checkPoints() + " checks ("
                    + parameterChecks + " parameters, " + returnChecks + " return values)";
        }
    }
}
