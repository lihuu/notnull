package io.github.lihuu.notnull;

import org.objectweb.asm.Opcodes;

/**
 * Tracks whether the value a method is about to return might be null.
 *
 * <p>This is the rule the JetBrains instrumenter applied before deciding to
 * check an {@code ARETURN}: a value the compiler can see was constructed right
 * there cannot be null, so checking it would only cost a branch. Keeping the
 * same rule keeps the injected checks — and therefore the observable behavior —
 * identical to the ones the {@code javac2} task used to inject.
 *
 * <p>Only the opcodes that differ from the default matter. Most instructions
 * leave a value that might be null on the stack; a handful clearly do not, and
 * another handful do not touch the top of the stack at all and so leave the
 * previous answer standing.
 */
final class NullabilityTracker {

    private NullabilityTracker() {
    }

    /**
     * The state after {@code opcode} ran, given the state before it.
     *
     * @param opcode the instruction that just ran
     * @param currentCanBeNull whether the value was considered nullable before it
     * @return whether a value produced or kept by {@code opcode} can be null
     */
    static boolean after(int opcode, boolean currentCanBeNull) {
        return switch (opcode) {
            // Values these build are never null. An invokedynamic is not on the
            // list: string concatenation goes through one and its result is a
            // fresh, non-null string, but the JetBrains instrumenter still checked
            // it, and the checks must not drift.
            case Opcodes.NEW, Opcodes.NEWARRAY, Opcodes.ANEWARRAY, Opcodes.MULTIANEWARRAY -> false;
            // A loaded constant is never null either.
            case Opcodes.LDC -> false;
            // These do not produce a reference at all, so the previous answer
            // still describes the value a return would hand back.
            case Opcodes.NOP, Opcodes.L2F, Opcodes.L2D, Opcodes.F2I, Opcodes.F2L, Opcodes.F2D,
                    Opcodes.D2I, Opcodes.GOTO, Opcodes.JSR, Opcodes.RET, Opcodes.CHECKCAST -> currentCanBeNull;
            default -> true;
        };
    }
}
