package dev.amatv.build;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Exact Foundation 1.11.4 idle-deadline backport; finite frame deadlines are untouched. */
public final class HomePrefetchBudgetPatch {
    public static final String CLASS_NAME =
            "androidx.compose.foundation.lazy.layout.AndroidPrefetchScheduler$PrefetchRequestScopeImpl";
    public static final String INTERNAL_NAME = CLASS_NAME.replace('.', '/');
    public static final String HELPER_OWNER = "dev/amatv/ui/HomePrefetchBudget";
    public static final String HELPER_METHOD = "remainingIdleBudgetNanos";

    private HomePrefetchBudgetPatch() {}

    public static void apply(ClassNode owner) {
        require(INTERNAL_NAME.equals(owner.name), "Unexpected Foundation prefetch scope: " + owner.name);
        List<MethodNode> targets = owner.methods.stream()
                .filter(method -> method.name.equals("availableTimeNanos") && method.desc.equals("()J"))
                .toList();
        require(targets.size() == 1, "Expected exactly one Foundation availableTimeNanos()J method.");
        MethodNode method = targets.get(0);
        require((method.access & Opcodes.ACC_PUBLIC) != 0
                        && (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == 0,
                "Unexpected Foundation availableTimeNanos access.");

        List<AbstractInsnNode> instructions = new ArrayList<>();
        List<LdcInsnNode> idleConstants = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() >= 0) instructions.add(instruction);
            if (instruction instanceof LdcInsnNode ldc && Long.valueOf(Long.MAX_VALUE).equals(ldc.cst)) {
                idleConstants.add(ldc);
            }
        }
        require(idleConstants.size() == 1,
                "Expected exactly one Long.MAX_VALUE idle constant in Foundation 1.11.4; review/remove the backport before upgrading.");
        int[] expectedOpcodes = {
                Opcodes.ALOAD, Opcodes.GETFIELD, Opcodes.IFEQ, Opcodes.LDC, Opcodes.GOTO,
                Opcodes.LCONST_0, Opcodes.ALOAD, Opcodes.GETFIELD, Opcodes.INVOKESTATIC,
                Opcodes.LSUB, Opcodes.INVOKESTATIC, Opcodes.LRETURN,
        };
        require(instructions.size() == expectedOpcodes.length,
                "Foundation prefetch scope instruction shape changed; review the idle-deadline backport.");
        for (int i = 0; i < expectedOpcodes.length; i++) {
            require(instructions.get(i).getOpcode() == expectedOpcodes[i],
                    "Foundation prefetch scope opcode changed at " + i + "; review the idle-deadline backport.");
        }
        require(instructions.get(3) == idleConstants.get(0), "Unexpected idle constant branch.");
        require(matchesField(instructions.get(1), "isFrameIdle", "Z")
                        && matchesField(instructions.get(7), "nextFrameTimeNs", "J"),
                "Foundation prefetch scope fields changed.");
        require(matchesCall(instructions.get(8), "java/lang/System", "nanoTime", "()J")
                        && matchesCall(instructions.get(10), "java/lang/Math", "max", "(JJ)J"),
                "Foundation finite-deadline branch changed; it must stay unmodified.");

        // Both the original LDC and this static call leave one long on the stack.
        // Existing branch structure, locals and the ordinary finite path remain byte-for-byte.
        method.instructions.set(idleConstants.get(0), new MethodInsnNode(
                Opcodes.INVOKESTATIC, HELPER_OWNER, HELPER_METHOD, "()J", false));
    }

    private static boolean matchesField(AbstractInsnNode node, String name, String descriptor) {
        return node instanceof FieldInsnNode field && field.owner.equals(INTERNAL_NAME)
                && field.name.equals(name) && field.desc.equals(descriptor);
    }

    private static boolean matchesCall(AbstractInsnNode node, String owner, String name, String descriptor) {
        return node instanceof MethodInsnNode call && call.owner.equals(owner)
                && call.name.equals(name) && call.desc.equals(descriptor) && !call.itf;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
