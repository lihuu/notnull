package io.github.lihuu.notnull;

import java.util.List;
import java.util.Map;

/**
 * The checks one class owes, as planned from the compiled class before any of
 * it is rewritten.
 *
 * @param internalName the class name as the JVM spells it, e.g.
 *     {@code com/example/Orders$Line}
 * @param binaryClassName {@code internalName} in the dotted form the injected
 *     messages use
 * @param isInterface whether the class is an interface, whose members the JVM
 *     requires instructions to reference as interface members
 * @param alreadyInstrumented whether the class already carries a generated check
 *     method, i.e. a previous pass rewrote it
 * @param contracts the classes' methods that need checks, keyed by
 *     {@code name + descriptor}
 */
record ContractPlan(
        String internalName,
        String binaryClassName,
        boolean isInterface,
        boolean alreadyInstrumented,
        Map<String, MethodContract> contracts) {

    /** Whether this class still has to be rewritten. */
    boolean needsRewrite() {
        return !alreadyInstrumented && contracts.values().stream().anyMatch(MethodContract::injects);
    }

    /** The checks {@code name} with {@code descriptor} owes, or {@code null}. */
    MethodContract contract(String name, String descriptor) {
        return contracts.get(name + descriptor);
    }

    /**
     * Whether a call from this class to {@code owner.name(descriptor)} can be
     * assumed to return a non-null value, which is what lets the caller skip its
     * own return check. Only holds inside one class, and only for a method whose
     * contract no subclass can weaken.
     */
    boolean alwaysNotNull(String owner, String name, String descriptor) {
        if (!internalName.equals(owner)) {
            return false;
        }
        MethodContract contract = contracts.get(name + descriptor);
        return contract != null && contract.stable() && contract.returnNotNull();
    }

    /**
     * The checks one method owes.
     *
     * @param methodName the JVM method name; a constructor is {@code <init>}
     * @param stable whether no subclass can weaken the contract, i.e. the method
     *     is {@code final}, {@code static} or {@code private}
     * @param returnNotNull whether the method declares a {@code @NotNull} return
     * @param parameterChecks one entry per {@code @NotNull} parameter, in
     *     declaration order
     * @param checkReturn whether an {@code ARETURN} may need its value checked
     */
    record MethodContract(
            String methodName,
            boolean stable,
            boolean returnNotNull,
            List<ParameterCheck> parameterChecks,
            boolean checkReturn) {

        /** Whether anything at all is injected into this method. */
        boolean injects() {
            return !parameterChecks.isEmpty() || checkReturn;
        }
    }

    /**
     * One {@code @NotNull} parameter to check.
     *
     * @param slot the local variable slot the argument was passed in
     * @param name the parameter name the message reports
     */
    record ParameterCheck(int slot, String name) {
    }
}
