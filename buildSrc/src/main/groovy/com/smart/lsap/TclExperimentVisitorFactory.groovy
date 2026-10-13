package com.smart.lsap

import com.android.build.api.instrumentation.*
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode

/** Apply host-side common policies only when the experimental app is packaged. */
abstract class TclExperimentVisitorFactory implements AsmClassVisitorFactory<InstrumentationParameters.None> {
    @Override boolean isInstrumentable(ClassData data) {
        String name = data.className.replace('.', '/')
        return name.startsWith('com/tcl/') || name.startsWith('com/google/android/exoplayer2/') ||
            name.startsWith('tv/danmaku/ijk/') || name.startsWith('a/a/')
    }

    @Override ClassVisitor createClassVisitor(ClassContext context, ClassVisitor next) {
        return new ClassNode(Opcodes.ASM9) {
            @Override void visitEnd() {
                super.visitEnd()
                ClassWriter writer = new ClassWriter(0)
                accept(writer)
                byte[] patched = LsapClassPatcher.patchTclExperimentParameters(name + '.class', writer.toByteArray())
                new ClassReader(patched).accept(next, ClassReader.EXPAND_FRAMES)
            }
        }
    }
}
