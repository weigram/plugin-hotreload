package com.hotreload.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Registers every MyBatis {@code Configuration} that loads at least one mapped
 * statement, by injecting one call at the top of
 * {@code addMappedStatement(MappedStatement)}.
 *
 * <p>Both MyBatis itself and MyBatis-Plus are instrumented: MyBatis-Plus
 * declares its own {@code mappedStatements} field and overrides the method
 * without calling {@code super}, so instrumenting only the base class would
 * miss it. Nothing else in MyBatis is touched, which keeps MyBatis-Plus,
 * PageHelper and Flowable proxies untouched.</p>
 */
final class MyBatisConfigTransformer implements ClassFileTransformer {

    private static final Set<String> TARGETS = new HashSet<String>(Arrays.asList(
            "org/apache/ibatis/session/Configuration",
            "com/baomidou/mybatisplus/core/MybatisConfiguration"
    ));

    private static final String METHOD = "addMappedStatement";
    private static final String DESCRIPTOR = "(Lorg/apache/ibatis/mapping/MappedStatement;)V";
    private static final String REGISTRY = "com/hotreload/agent/MyBatisConfigRegistry";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || loader == null || !TARGETS.contains(className)) {
            return null;
        }
        // The injected call has to be resolvable from the class loader of the
        // MyBatis class. Keep the class untouched when it is not.
        try {
            Class.forName(REGISTRY.replace('/', '.'), false, loader);
        } catch (Throwable unavailable) {
            Log.debug("MyBatis registry not visible from " + loader + ": " + unavailable);
            return null;
        }
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!METHOD.equals(name) || !DESCRIPTOR.equals(descriptor)) {
                        return next;
                    }
                    return new MethodVisitor(Opcodes.ASM9, next) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            mv.visitVarInsn(Opcodes.ALOAD, 0);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "register",
                                    "(Ljava/lang/Object;)V", false);
                        }
                    };
                }
            };
            reader.accept(visitor, 0);
            return writer.toByteArray();
        } catch (Throwable failed) {
            Log.warn("could not instrument " + className + ": " + failed);
            return null;
        }
    }
}
