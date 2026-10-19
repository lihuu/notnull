package io.github.lihuu.notnull;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;

/**
 * Rewrites a class with the checks {@link ContractPlanner} planned. A later run
 * over the same classes directory finds the calls already there and leaves the
 * class alone, so nothing has to be marked on the class itself.
 *
 * <p>Every injected sequence is straight-line code with no net stack effect and
 * no new jump target, so the frames the compiler already wrote stay valid and
 * only {@code maxStack} has to be recomputed. Nothing here needs
 * {@code COMPUTE_FRAMES}, and therefore nothing here has to load the classes
 * being rewritten.
 *
 * <p>A parameter check goes at the head of the method, constructors included:
 * it only reads argument slots, so it is legal before the {@code super(...)} or
 * {@code this(...)} call.
 *
 * <p>Every check is a call to a private static method this pass appends to the
 * class, the way the JetBrains instrumenter emitted its
 * {@code $$$reportNull$$$} methods: the call site stays straight-line and the
 * class keeps to itself.
 *
 * <p>A return check goes in front of an {@code ARETURN} only when the value
 * being returned might be null. {@link NullabilityTracker} carries the same
 * "can this be null" state the JetBrains instrumenter used, so a value that was
 * just built by {@code new} or by a call to a {@code final}/{@code static}/
 * {@code private} {@code @NotNull} method of the same class is not checked
 * again.
 */
final class ContractInjector extends ClassVisitor {

    /**
     * The name every generated check method starts with; a class that already
     * carries one has been rewritten before.
     */
    static final String CHECK_METHOD_PREFIX = "$$$notNullCheck$$$";

    /** Throws when a {@code @NotNull} argument is null. */
    static final String PARAMETER_CHECK = CHECK_METHOD_PREFIX + "0";

    /** Throws when a {@code @NotNull} return value is null. */
    static final String RETURN_CHECK = CHECK_METHOD_PREFIX + "1";

    private static final String CHECK_DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/String;)V";
    private static final String IAE = "java/lang/IllegalArgumentException";
    private static final String ISE = "java/lang/IllegalStateException";

    private final ContractPlan plan;
    private int parameterChecks;
    private int returnChecks;
    private boolean parameterCheckNeeded;
    private boolean returnCheckNeeded;

    ContractInjector(ClassVisitor delegate, ContractPlan plan) {
        super(Opcodes.ASM9, delegate);
        this.plan = plan;
    }

    int parameterChecks() {
        return parameterChecks;
    }

    int returnChecks() {
        return returnChecks;
    }

    @Override
    public MethodVisitor visitMethod(
            int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
        ContractPlan.MethodContract contract = plan.contract(name, descriptor);
        if (methodVisitor == null || contract == null || !contract.injects()) {
            return methodVisitor;
        }
        return new InjectingMethodVisitor(methodVisitor, plan, contract);
    }

    @Override
    public void visitEnd() {
        // Emitted after the methods that call them. A generated method keeps the
        // check inside the class that declares the contract, so no instrumented
        // class gains a dependency on a type of ours -- which is what the
        // architecture guard on the core seams is there to prevent.
        if (parameterCheckNeeded) {
            generateCheck(PARAMETER_CHECK, IAE);
        }
        if (returnCheckNeeded) {
            generateCheck(RETURN_CHECK, ISE);
        }
        super.visitEnd();
    }

    private void generateCheck(String name, String exceptionClass) {
        MethodVisitor check = super.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name,
                CHECK_DESCRIPTOR,
                null,
                null);
        check.visitCode();
        check.visitVarInsn(Opcodes.ALOAD, 0);
        Label pass = new Label();
        check.visitJumpInsn(Opcodes.IFNONNULL, pass);
        check.visitTypeInsn(Opcodes.NEW, exceptionClass);
        check.visitInsn(Opcodes.DUP);
        check.visitVarInsn(Opcodes.ALOAD, 1);
        check.visitMethodInsn(Opcodes.INVOKESPECIAL, exceptionClass, "<init>",
                "(Ljava/lang/String;)V", false);
        check.visitInsn(Opcodes.ATHROW);
        check.visitLabel(pass);
        // The branch target needs a frame of its own; nothing has changed by then,
        // so the implicit initial frame still describes it. COMPUTE_MAXS fills in
        // the stack sizes, COMPUTE_FRAMES is not needed and would have to load the
        // class's dependencies.
        check.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        check.visitInsn(Opcodes.RETURN);
        check.visitMaxs(0, 0);
        check.visitEnd();
    }

    private final class InjectingMethodVisitor extends MethodVisitor {

        private final ContractPlan plan;
        private final ContractPlan.MethodContract contract;
        private boolean canBeNull;

        private InjectingMethodVisitor(
                MethodVisitor methodVisitor,
                ContractPlan plan,
                ContractPlan.MethodContract contract) {
            super(Opcodes.ASM9, methodVisitor);
            this.plan = plan;
            this.contract = contract;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            injectParameterChecks();
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.ARETURN && contract.checkReturn() && canBeNull) {
                returnCheckNeeded = true;
                super.visitInsn(Opcodes.DUP);
                super.visitLdcInsn("@NotNull method " + plan.binaryClassName() + "."
                        + contract.methodName() + " must not return null");
                super.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        plan.internalName(),
                        RETURN_CHECK,
                        CHECK_DESCRIPTOR,
                        plan.isInterface());
                returnChecks++;
            }
            super.visitInsn(opcode);
            canBeNull = NullabilityTracker.after(opcode, canBeNull);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            super.visitIntInsn(opcode, operand);
            canBeNull = NullabilityTracker.after(opcode, canBeNull);
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            super.visitVarInsn(opcode, varIndex);
            canBeNull = NullabilityTracker.after(opcode, canBeNull);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            super.visitTypeInsn(opcode, type);
            canBeNull = NullabilityTracker.after(opcode, canBeNull);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            super.visitFieldInsn(opcode, owner, name, descriptor);
            canBeNull = NullabilityTracker.after(opcode, canBeNull);
        }

        @Override
        public void visitMethodInsn(
                int opcode, String owner, String name, String descriptor, boolean isInterface) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            if (opcode == Opcodes.INVOKESPECIAL && "<init>".equals(name)) {
                canBeNull = false;
            } else if (opcode == Opcodes.INVOKESTATIC || opcode == Opcodes.INVOKEVIRTUAL
                    || opcode == Opcodes.INVOKESPECIAL) {
                canBeNull = !plan.alwaysNotNull(owner, name, descriptor);
            } else {
                canBeNull = true;
            }
        }

        @Override
        public void visitInvokeDynamicInsn(
                String name, String descriptor, Handle handle, Object... args) {
            super.visitInvokeDynamicInsn(name, descriptor, handle, args);
            canBeNull = NullabilityTracker.after(Opcodes.INVOKEDYNAMIC, canBeNull);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            super.visitJumpInsn(opcode, label);
            canBeNull = NullabilityTracker.after(opcode, canBeNull);
        }

        @Override
        public void visitLdcInsn(Object value) {
            super.visitLdcInsn(value);
            canBeNull = NullabilityTracker.after(Opcodes.LDC, canBeNull);
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
            super.visitIincInsn(varIndex, increment);
            canBeNull = true;
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            super.visitMultiANewArrayInsn(descriptor, numDimensions);
            canBeNull = false;
        }

        private void injectParameterChecks() {
            for (ContractPlan.ParameterCheck check : contract.parameterChecks()) {
                parameterCheckNeeded = true;
                super.visitVarInsn(Opcodes.ALOAD, check.slot());
                super.visitLdcInsn("Argument for @NotNull parameter '" + check.name() + "' of "
                        + plan.binaryClassName() + "." + contract.methodName()
                        + " must not be null");
                super.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        plan.internalName(),
                        PARAMETER_CHECK,
                        CHECK_DESCRIPTOR,
                        plan.isInterface());
                parameterChecks++;
            }
        }
    }
}
