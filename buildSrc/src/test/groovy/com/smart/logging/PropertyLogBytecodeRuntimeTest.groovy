package com.smart.logging

import org.junit.Test
import javax.tools.ToolProvider
import java.nio.file.Files
import java.util.logging.*
import static org.junit.Assert.*

class PropertyLogBytecodeRuntimeTest {
    @Test void 'transformed vendor exceptions and JUL calls execute safely and follow the gate'() {
        File dir = Files.createTempDirectory('logging-bytecode').toFile()
        def originalErr = System.err
        try {
            File gate = new File(dir, 'fixture/logging/PropertyLog.java')
            gate.parentFile.mkdirs()
            gate.text = '''package fixture.logging;
                public class PropertyLog { public static boolean enabled; public static boolean isEnabled() { return enabled; } }
            '''
            File caller = new File(dir, 'fixture/Caller.java')
            caller.text = '''package fixture;
                import java.io.*; import java.util.logging.*;
                public class Caller {
                    public static String run(Logger logger, boolean branch, long wide) {
                        IOException error = new IOException("diagnostic");
                        StringWriter text = new StringWriter();
                        error.printStackTrace(new PrintWriter(text));
                        logger.setLevel(Level.ALL);
                        if (branch) {
                            error.printStackTrace();
                            logger.log(Level.INFO, "message", error);
                        } else {
                            logger.info("alternate" + wide);
                        }
                        return text.toString();
                    }
                }
            '''
            assertEquals(0, ToolProvider.systemJavaCompiler.run(null, null, null,
                '-d', dir.path, gate.path, caller.path))
            File bytecode = new File(dir, 'fixture/Caller.class')
            bytecode.bytes = PropertyLogClassVisitor.patch(bytecode.bytes, 'fixture/logging/PropertyLog')
            URLClassLoader loader = new URLClassLoader([dir.toURI().toURL()] as URL[], (ClassLoader) null)
            def gateClass = loader.loadClass('fixture.logging.PropertyLog')
            def invoke = loader.loadClass('fixture.Caller').getMethod('run', Logger, Boolean.TYPE, Long.TYPE)
            ByteArrayOutputStream stderr = new ByteArrayOutputStream()
            System.setErr(new PrintStream(stderr))
            List<LogRecord> records = []
            Logger logger = Logger.getAnonymousLogger()
            logger.useParentHandlers = false
            logger.addHandler(new Handler() {
                void publish(LogRecord record) { records.add(record) }
                void flush() {}
                void close() {}
            })
            assertTrue(invoke.invoke(null, logger, true, 123L).toString().contains('diagnostic'))
            assertEquals(Level.ALL, logger.level)
            assertEquals('', stderr.toString())
            assertTrue(records.empty)
            gateClass.getField('enabled').setBoolean(null, true)
            invoke.invoke(null, logger, true, 123L)
            assertTrue(stderr.toString().contains('diagnostic'))
            assertEquals(1, records.size())
            invoke.invoke(null, logger, false, 123L)
            assertEquals(2, records.size())
            stderr.reset(); records.clear()
            gateClass.getField('enabled').setBoolean(null, false)
            invoke.invoke(null, logger, true, 123L)
            invoke.invoke(null, logger, false, 123L)
            assertEquals('', stderr.toString())
            assertTrue(records.empty)
            loader.close()
        } finally { System.setErr(originalErr); dir.deleteDir() }
    }
}
