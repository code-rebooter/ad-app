package com.smart.logging

import com.android.build.api.instrumentation.*
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.objectweb.asm.ClassVisitor

abstract class PropertyLogVisitorFactory implements AsmClassVisitorFactory<PropertyLogVisitorFactory.Parameters> {
    interface Parameters extends InstrumentationParameters {
        @Input Property<String> getBridgeClass()
    }

    @Override boolean isInstrumentable(ClassData data) {
        return !PropertyLogClassVisitor.isBridge(data.className)
    }

    @Override ClassVisitor createClassVisitor(ClassContext context, ClassVisitor next) {
        return new PropertyLogClassVisitor(next, parameters.get().bridgeClass.get())
    }
}
