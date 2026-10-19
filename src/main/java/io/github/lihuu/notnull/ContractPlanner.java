package io.github.lihuu.notnull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.TypeReference;

/**
 * Reads a compiled class and works out which checks it owes, without writing
 * anything.
 *
 * <p>{@code @NotNull} can be written on a parameter in either of the two places
 * a {@code CLASS}-retention annotation with both {@code PARAMETER} and
 * {@code TYPE_USE} targets may land, so both are read and merged. A return type
 * is read the same way.
 *
 * <p>Parameter names are taken from {@code MethodParameters} when the compiler
 * emitted it, otherwise from the {@code LocalVariableTable} of the method, and
 * otherwise fall back to {@code argN}. The tables are only visited after the
 * code, so a method's plan is assembled in {@code visitEnd}.
 *
 * <p>The compiler numbers annotations on a constructor's parameters over the
 * parameters a caller writes, which for an inner or local class excludes the
 * synthetic leading ones; a parameter index is therefore shifted by the
 * difference between the descriptor's parameter count and the count the
 * annotation attributes cover.
 */
final class ContractPlanner extends ClassVisitor {

    private String internalName;
    private boolean isInterface;
    private boolean alreadyInstrumented;
    private final List<MethodPlanner> methods = new ArrayList<>();

    private ContractPlanner() {
        super(Opcodes.ASM9);
    }

    static ContractPlan plan(ClassReader reader) {
        ContractPlanner planner = new ContractPlanner();
        reader.accept(planner, 0);
        return planner.plan();
    }

    @Override
    public void visit(
            int version,
            int access,
            String name,
            String signature,
            String superName,
            String[] interfaces) {
        internalName = name;
        isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(
            int access, String name, String descriptor, String signature, String[] exceptions) {
        if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            return null;
        }
        if (name.startsWith(ContractInjector.CHECK_METHOD_PREFIX)) {
            // A previous pass already rewrote this class; rewriting it again would
            // double every check.
            alreadyInstrumented = true;
            return null;
        }
        MethodPlanner method = new MethodPlanner(access, name, descriptor);
        methods.add(method);
        return method;
    }

    private ContractPlan plan() {
        Map<String, ContractPlan.MethodContract> contracts = new LinkedHashMap<>();
        for (MethodPlanner method : methods) {
            contracts.put(method.key(), method.contract());
        }
        return new ContractPlan(
                internalName,
                internalName.replace('/', '.'),
                isInterface,
                alreadyInstrumented,
                contracts);
    }

    private static final class MethodPlanner extends MethodVisitor {

        private final int access;
        private final String name;
        private final String descriptor;
        private final Set<Integer> annotatedParameters = new TreeSet<>();
        private final List<String> declaredParameterNames = new ArrayList<>();
        private final Map<Integer, String> localNames = new HashMap<>();
        private int annotableParameterCount = -1;
        private boolean annotatedReturn;

        private MethodPlanner(int access, String name, String descriptor) {
            super(Opcodes.ASM9);
            this.access = access;
            this.name = name;
            this.descriptor = descriptor;
        }

        String key() {
            return name + descriptor;
        }

        @Override
        public void visitParameter(String parameterName, int parameterAccess) {
            declaredParameterNames.add(parameterName);
            super.visitParameter(parameterName, parameterAccess);
        }

        @Override
        public void visitAnnotableParameterCount(int parameterCount, boolean visible) {
            if (!visible) {
                annotableParameterCount = parameterCount;
            }
            super.visitAnnotableParameterCount(parameterCount, visible);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
            // A method declaration can carry @NotNull on its own, next to the
            // type-use form. A record accessor inherits its component's annotation
            // this way, and an array return type records it as a type annotation
            // with a type path, which the type-use branch below deliberately skips.
            if (isNotNull(annotationDescriptor, visible)) {
                annotatedReturn = true;
            }
            return super.visitAnnotation(annotationDescriptor, visible);
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(
                int parameter, String annotationDescriptor, boolean visible) {
            if (isNotNull(annotationDescriptor, visible)) {
                annotatedParameters.add(parameter);
            }
            return super.visitParameterAnnotation(parameter, annotationDescriptor, visible);
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(
                int typeRef, TypePath typePath, String annotationDescriptor, boolean visible) {
            // A type use nested in the type itself, such as List<@NotNull String>,
            // says nothing about the parameter or return value as a whole.
            if (isNotNull(annotationDescriptor, visible) && typePath == null) {
                int sort = typeRef >>> 24;
                if (sort == TypeReference.METHOD_FORMAL_PARAMETER) {
                    // ASM packs the JVM's one-byte parameter_index into bits 16-23, not
                    // the low byte, so reading the low byte reports parameter 0 for
                    // every type-use annotation. Note that TypeReference's static
                    // factories build a reference out of a sort or an index and shift
                    // it themselves; only this constructor takes the encoding as-is.
                    annotatedParameters.add(
                            new TypeReference(typeRef).getFormalParameterIndex());
                } else if (sort == TypeReference.METHOD_RETURN) {
                    annotatedReturn = true;
                }
            }
            return super.visitTypeAnnotation(typeRef, typePath, annotationDescriptor, visible);
        }

        @Override
        public void visitLocalVariable(
                String localName,
                String localDescriptor,
                String signature,
                Label start,
                Label end,
                int index) {
            localNames.putIfAbsent(index, localName);
            super.visitLocalVariable(localName, localDescriptor, signature, start, end, index);
        }

        ContractPlan.MethodContract contract() {
            Type[] arguments = Type.getArgumentTypes(descriptor);
            int shift = annotableParameterCount >= 0 && annotableParameterCount <= arguments.length
                    ? arguments.length - annotableParameterCount
                    : 0;
            List<ContractPlan.ParameterCheck> checks = new ArrayList<>();
            int slot = (access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
            for (int index = 0; index < arguments.length; index++) {
                Type argument = arguments[index];
                if (isReference(argument) && annotatedParameters.contains(index - shift)) {
                    checks.add(new ContractPlan.ParameterCheck(slot, parameterName(index, slot)));
                }
                slot += argument.getSize();
            }
            boolean returnNotNull = annotatedReturn && isReference(Type.getReturnType(descriptor));
            boolean stable = (access & (Opcodes.ACC_FINAL | Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0;
            if ((access & Opcodes.ACC_BRIDGE) != 0) {
                // A bridge only forwards to the method it bridges to, which carries
                // the same checks. It still answers for this class when a caller
                // asks whether a call returns non-null, hence the real flags.
                return new ContractPlan.MethodContract(
                        name, stable, returnNotNull, List.of(), false);
            }
            return new ContractPlan.MethodContract(
                    name, stable, returnNotNull, List.copyOf(checks), returnNotNull);
        }

        private String parameterName(int index, int slot) {
            if (index < declaredParameterNames.size()) {
                String declared = declaredParameterNames.get(index);
                if (declared != null && !declared.isBlank()) {
                    return declared;
                }
            }
            String local = localNames.get(slot);
            if (local != null && !local.isBlank()) {
                return local;
            }
            return "arg" + index;
        }

        private static boolean isNotNull(String annotationDescriptor, boolean visible) {
            return !visible && NotNullInstrumenter.NOT_NULL_DESCRIPTOR.equals(annotationDescriptor);
        }

        private static boolean isReference(Type type) {
            int sort = type.getSort();
            return sort == Type.OBJECT || sort == Type.ARRAY;
        }
    }
}
