package com.smart.logging

import org.junit.Test
import javax.tools.ToolProvider
import java.nio.file.Files
import static org.junit.Assert.*

class PropertyLoggingTest {
    @Test void 'all output follows the live Android property and fails closed'() {
        File template = new File('..', 'logging/PropertyLog.java.template').canonicalFile
        assertTrue('The shared runtime property gate must exist', template.isFile())
        File dir = Files.createTempDirectory('property-log-test').toFile()
        try {
            Map<String, String> sources = [
                'fixture/PropertyLog.java': template.text.replace('@PACKAGE@', 'fixture'),
                'android/os/SystemProperties.java': '''package android.os;
                    public class SystemProperties {
                        public static String value = "";
                        public static boolean broken;
                        public static String get(String key, String fallback) {
                            if (broken) throw new SecurityException("denied");
                            if (!key.equals("persist.sys.ad.log")) throw new AssertionError(key);
                            return value;
                        }
                    }''',
                'android/util/Log.java': '''package android.util;
                    public class Log {
                        public static int count;
                        public static boolean isLoggable(String t,int p) { return true; }
                        public static String getStackTraceString(Throwable e) { return e.toString(); }
                        public static int println(int p,String t,String m) { return ++count; }
                    }'''
            ]
            ['v','d','i','w','e','wtf'].each { name ->
                sources['android/util/Log.java'] = sources['android/util/Log.java'].replaceFirst(/}\s*$/, """
                    public static int ${name}(String t,String m) { return ++count; }
                    public static int ${name}(String t,String m,Throwable e) { return ++count; }
                    public static int ${name}(String t,Throwable e) { return ++count; }
                }""")
            }
            List<File> files = sources.collect { path, source ->
                File file = new File(dir, path); file.parentFile.mkdirs(); file.text = source; file
            }
            assertEquals(0, ToolProvider.systemJavaCompiler.run(null, null, null,
                (['-d', dir.path] + files*.path) as String[]))
            URLClassLoader loader = new URLClassLoader([dir.toURI().toURL()] as URL[], (ClassLoader) null)
            def props = loader.loadClass('android.os.SystemProperties')
            def sink = loader.loadClass('android.util.Log')
            def log = loader.loadClass('fixture.PropertyLog')
            def enabled = log.getMethod('isEnabled')
            def output = log.getMethod('i', String, String)
            ['', 'false', '0', '1', 'yes', 'invalid', null].each { value ->
                props.getField('value').set(null, value)
                assertFalse("must be closed for ${value}", (boolean) enabled.invoke(null))
                assertEquals(0, output.invoke(null, 'tag', 'hidden'))
            }
            props.getField('value').set(null, ' TrUe ')
            assertTrue((boolean) enabled.invoke(null))
            ['v','d','i','w','e','wtf'].each { name ->
                assertTrue((int) log.getMethod(name, String, String).invoke(null, 'tag', 'visible') > 0)
                assertTrue((int) log.getMethod(name, String, String, Throwable)
                    .invoke(null, 'tag', 'visible', new Exception('test')) > 0)
            }
            ['w', 'wtf'].each { name ->
                assertTrue((int) log.getMethod(name, String, Throwable)
                    .invoke(null, 'tag', new Exception('test')) > 0)
            }
            assertTrue((int) log.getMethod('println', Integer.TYPE, String, String)
                .invoke(null, 4, 'tag', 'visible') > 0)
            assertEquals(true, log.getMethod('isLoggable', String, Integer.TYPE).invoke(null, 'tag', 4))
            props.getField('value').set(null, 'false')
            int count = sink.getField('count').getInt(null)
            ['v','d','i','w','e','wtf'].each { name ->
                assertEquals(0, log.getMethod(name, String, String).invoke(null, 'tag', 'hidden'))
                assertEquals(0, log.getMethod(name, String, String, Throwable)
                    .invoke(null, 'tag', 'hidden', new Exception('test')))
            }
            assertEquals(count, sink.getField('count').getInt(null))
            assertEquals(0, log.getMethod('println', Integer.TYPE, String, String)
                .invoke(null, 4, 'tag', 'hidden'))
            assertEquals(false, log.getMethod('isLoggable', String, Integer.TYPE).invoke(null, 'tag', 4))
            def originalOut = System.out
            def captured = new ByteArrayOutputStream()
            try {
                System.setOut(new PrintStream(captured))
                PrintStream cachedStream = (PrintStream) log.getMethod('stdout').invoke(null)
                cachedStream.println('hidden')
                assertEquals('', captured.toString())
                props.getField('value').set(null, 'true')
                cachedStream.println('visible')
                assertTrue(captured.toString().contains('visible'))
                captured.reset()
                props.getField('value').set(null, 'false')
                cachedStream.println('hidden after toggle')
                assertEquals('', captured.toString())
                cachedStream.close()
                props.getField('value').set(null, 'true')
                cachedStream.println('still usable')
                assertTrue(captured.toString().contains('still usable'))
            } finally { System.setOut(originalOut) }
            System.setProperty('persist.sys.ad.log', 'true')
            props.getField('value').set(null, 'true')
            props.getField('broken').setBoolean(null, true)
            assertFalse('Java property and debug flags must not bypass unreadable Android property',
                (boolean) enabled.invoke(null))
            loader.close()
        } finally {
            System.clearProperty('persist.sys.ad.log')
            dir.deleteDir()
        }
    }
}
