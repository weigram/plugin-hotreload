package com.hotreload.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Turns off HotSwapAgent's Spring XML resource listener.
 *
 * <p>HA's Spring plugin treats every changed {@code *.xml} on the classpath as a
 * Spring bean-definition file and tries to register it. A MyBatis mapper XML is
 * not a Spring XML: the attempt logs errors and can interfere with the mapper.
 * This agent's {@link MyBatisXmlReloader} owns mapper XML, so the HA listener is
 * replaced with an empty method. Class listeners, bean reprocessing, controller
 * registration and AOP handling are untouched.</p>
 */
final class HotswapSpringPatch implements ClassFileTransformer {

    private static final String SPRING_PLUGIN = "org/hotswap/agent/plugin/spring/SpringPlugin";
    private static final String METHOD = "registerResourceListeners";
    private static final String DESCRIPTOR = "(Ljava/net/URL;)V";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!SPRING_PLUGIN.equals(className)) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, 0);
            ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!METHOD.equals(name) || !DESCRIPTOR.equals(descriptor)) {
                        return next;
                    }
                    Log.debug("disabling HotSwapAgent Spring XML resource listener");
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitEnd() {
                            next.visitCode();
                            next.visitInsn(Opcodes.RETURN);
                            next.visitMaxs(0, 2);
                            next.visitEnd();
                        }
                    };
                }
            };
            reader.accept(visitor, 0);
            return writer.toByteArray();
        } catch (Throwable failure) {
            Log.warn("could not patch HotSwapAgent Spring XML listener: " + failure);
            return null;
        }
    }
}
