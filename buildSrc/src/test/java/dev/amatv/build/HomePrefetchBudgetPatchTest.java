package dev.amatv.build;

import static org.junit.Assert.*;

import java.util.Arrays;
import org.junit.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

public class HomePrefetchBudgetPatchTest {
    @Test public void replacesOnlyTheIdleConstantAndPreservesEveryOtherInstructionObject() {
        ClassNode owner = fixture();
        MethodNode target = target(owner);
        AbstractInsnNode[] before = target.instructions.toArray();
        HomePrefetchBudgetPatch.apply(owner);
        AbstractInsnNode[] after = target.instructions.toArray();
        assertEquals(before.length, after.length);
        int changed = 0;
        for (int i = 0; i < before.length; i++) {
            if (before[i] != after[i]) {
                changed++;
                assertTrue(before[i] instanceof LdcInsnNode);
                assertEquals(Long.MAX_VALUE, ((LdcInsnNode) before[i]).cst);
                MethodInsnNode call = (MethodInsnNode) after[i];
                assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
                assertEquals(HomePrefetchBudgetPatch.HELPER_OWNER, call.owner);
                assertEquals(HomePrefetchBudgetPatch.HELPER_METHOD, call.name);
                assertEquals("()J", call.desc);
            }
        }
        assertEquals(1, changed);
    }

    @Test public void transformedBytecodeInvokesHelperOnlyForIdleAndKeepsExpiredFiniteBudgetZero() throws Exception {
        ClassNode owner = fixture();
        HomePrefetchBudgetPatch.apply(owner);
        ClassNode helper = helperFixture();
        ClassLoader loader = new ClassLoader(null) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                ClassNode selected = name.equals(HomePrefetchBudgetPatch.CLASS_NAME) ? owner
                        : name.equals(HomePrefetchBudgetPatch.HELPER_OWNER.replace('/', '.')) ? helper : null;
                if (selected == null) throw new ClassNotFoundException(name);
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                selected.accept(writer);
                byte[] bytes = writer.toByteArray();
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        Class<?> scope = loader.loadClass(HomePrefetchBudgetPatch.CLASS_NAME);
        Class<?> budget = loader.loadClass(HomePrefetchBudgetPatch.HELPER_OWNER.replace('/', '.'));
        Object instance = scope.getConstructor().newInstance();
        scope.getField("isFrameIdle").setBoolean(instance, true);
        assertEquals(3_000_000L, scope.getMethod("availableTimeNanos").invoke(instance));
        assertEquals(1, budget.getField("calls").getInt(null));
        scope.getField("isFrameIdle").setBoolean(instance, false);
        scope.getField("nextFrameTimeNs").setLong(instance, 0L);
        assertEquals(0L, scope.getMethod("availableTimeNanos").invoke(instance));
        long next = System.nanoTime() + 100_000_000L;
        scope.getField("nextFrameTimeNs").setLong(instance, next);
        long finite = (long) scope.getMethod("availableTimeNanos").invoke(instance);
        assertTrue(finite > 3_000_000L && finite <= 100_000_000L);
        assertEquals(1, budget.getField("calls").getInt(null));
    }

    @Test public void rejectsMissingDuplicatedOrChangedFrameworkIdleBranches() {
        ClassNode missing = fixture();
        LdcInsnNode max = Arrays.stream(target(missing).instructions.toArray())
                .filter(it -> it instanceof LdcInsnNode).map(it -> (LdcInsnNode) it).findFirst().orElseThrow();
        max.cst = 99L;
        rejects(missing);
        ClassNode duplicate = fixture();
        target(duplicate).instructions.add(new LdcInsnNode(Long.MAX_VALUE));
        rejects(duplicate);
        ClassNode changedFinite = fixture();
        Arrays.stream(target(changedFinite).instructions.toArray())
                .filter(it -> it instanceof MethodInsnNode).map(it -> (MethodInsnNode) it)
                .filter(it -> it.name.equals("max")).findFirst().orElseThrow().name = "min";
        rejects(changedFinite);
        ClassNode twice = fixture();
        HomePrefetchBudgetPatch.apply(twice);
        rejects(twice);
    }

    private static void rejects(ClassNode owner) {
        try {
            HomePrefetchBudgetPatch.apply(owner);
            fail("Changed/unsupported framework bytecode must fail the build");
        } catch (IllegalStateException expected) {
            assertFalse(expected.getMessage().isBlank());
        }
    }

    private static MethodNode target(ClassNode owner) {
        return owner.methods.stream().filter(it -> it.name.equals("availableTimeNanos")).findFirst().orElseThrow();
    }

    private static ClassNode fixture() {
        ClassNode node = new ClassNode(Opcodes.ASM9);
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = HomePrefetchBudgetPatch.INTERNAL_NAME;
        node.superName = "java/lang/Object";
        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "isFrameIdle", "Z", null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "nextFrameTimeNs", "J", null, null));
        MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        init.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(init);
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "availableTimeNanos", "()J", null, null);
        LabelNode finite = new LabelNode();
        LabelNode end = new LabelNode();
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "isFrameIdle", "Z"));
        method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, finite));
        method.instructions.add(new LdcInsnNode(Long.MAX_VALUE));
        method.instructions.add(new JumpInsnNode(Opcodes.GOTO, end));
        method.instructions.add(finite);
        method.instructions.add(new InsnNode(Opcodes.LCONST_0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "nextFrameTimeNs", "J"));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false));
        method.instructions.add(new InsnNode(Opcodes.LSUB));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(JJ)J", false));
        method.instructions.add(end);
        method.instructions.add(new InsnNode(Opcodes.LRETURN));
        node.methods.add(method);
        return node;
    }

    private static ClassNode helperFixture() {
        ClassNode helper = new ClassNode(Opcodes.ASM9);
        helper.version = Opcodes.V17;
        helper.access = Opcodes.ACC_PUBLIC;
        helper.name = HomePrefetchBudgetPatch.HELPER_OWNER;
        helper.superName = "java/lang/Object";
        helper.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "calls", "I", null, null));
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                HomePrefetchBudgetPatch.HELPER_METHOD, "()J", null, null);
        method.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, helper.name, "calls", "I"));
        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
        method.instructions.add(new InsnNode(Opcodes.IADD));
        method.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, helper.name, "calls", "I"));
        method.instructions.add(new LdcInsnNode(3_000_000L));
        method.instructions.add(new InsnNode(Opcodes.LRETURN));
        helper.methods.add(method);
        return helper;
    }
}
