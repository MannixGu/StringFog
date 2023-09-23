package com.github.megatronking.stringfog.plugin;

import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;


public final class TemplateClassNodeFactory {

    @NotNull
    public static final ClassNode create(@NotNull String pkgPath, @NotNull String clzName) {
        return create(pkgPath + '/' + clzName);
    }

    @NotNull
    public static final ClassNode create(@NotNull String clzName) {
        ClassNode cw = new ClassNode();

        cw.visit(Opcodes.V1_7, Opcodes.ACC_PUBLIC, clzName, null, "java/lang/Object", null);

        return cw;
    }

    private static void visitInit(ClassNode cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }
}
