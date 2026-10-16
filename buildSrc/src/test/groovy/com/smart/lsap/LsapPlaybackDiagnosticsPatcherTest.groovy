package com.smart.lsap

import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import java.util.jar.JarInputStream
import java.util.zip.ZipFile
import static org.junit.Assert.*

class LsapPlaybackDiagnosticsPatcherTest {
    private static final String BRIDGE = 'com/smart/android/ad_app/HaierAarRuntimeBridge'

    @Test
    void 'pinned JAMS AAR exposes every required diagnostic boundary'() {
        Map<String, byte[]> classes = vendorClasses()
        Map<String, List<MethodInsnNode>> hooks = [:]
        LsapPlaybackDiagnosticsPatcher.TARGETS.each { entry ->
            assertNotNull(entry, classes[entry])
            byte[] patched = LsapPlaybackDiagnosticsPatcher.patch(entry, classes[entry])
            assertFalse("No hook installed in ${entry}", Arrays.equals(classes[entry], patched))
            hooks[entry] = calls(patched).findAll { it.owner == BRIDGE }
        }
        assertEquals(['beginVastFetchDiagnostics', 'convertVastResponse', 'readVastResponseBody'],
            hooks['com/tcl/ff/component/vastad/c/e.class']*.name.sort())
        assertTrue(hooks['com/tcl/ff/component/vastad/convert/XmlConverter.class']*.name.contains('recordVastParseException'))
        assertTrue(hooks['com/tcl/ff/component/vastad/core/g.class']*.name.containsAll([
            'recordVastRequestError','recordAdPlayerError','recordAdPlayerState','recordPlayableAdPod','recordAdControllerStage','bindAdDiagnosticController']))
        assertEquals(['attachAdPlaybackProbe'], hooks['com/tcl/ff/component/vastad/core/h.class']*.name)
        assertTrue(hooks['com/itv/component/unified/UnifiedAdPrefs.class']*.name.contains('recordSdkBranchFlag'))
        assertTrue(hooks['com/itv/component/unified/UnifiedAdSdk$RequestSessionImpl.class']*.name.contains('recordAdDiagnosticStage'))
        assertTrue(hooks['com/itv/component/unified/UnifiedAdSdk$RequestSessionImpl$1.class']*.name.contains('recordPrimaryAdState'))
        ClassNode controller = new ClassNode()
        new ClassReader(LsapPlaybackDiagnosticsPatcher.patch('com/tcl/ff/component/vastad/core/g.class',
            classes['com/tcl/ff/component/vastad/core/g.class'])).accept(controller, 0)
        MethodNode configure = controller.methods.find { it.name == 'a' && it.desc.startsWith('(Landroid/content/Context;Lcom/tcl/ff/component/vastad/core/h;') }
        assertNotNull('Per-request configure method missing', configure)
        assertTrue('Cached controller must be rebound for each request', configure.instructions.toArray().any {
            it instanceof MethodInsnNode && it.owner == BRIDGE && it.name == 'bindAdDiagnosticController'
        })
        List<MethodInsnNode> provider = calls(LsapPlaybackDiagnosticsPatcher.patch(
            'com/tcl/ff/component/vastad/c/e.class', classes['com/tcl/ff/component/vastad/c/e.class']))
        assertFalse(provider.any { it.owner == 'okhttp3/ResponseBody' && it.name == 'string' })
        assertFalse(provider.any { it.owner == 'com/tcl/ff/component/vastad/convert/XmlConverter' && it.name == 'convert' })
    }

    @Test
    void 'unrelated vendor class remains byte identical'() {
        byte[] original = vendorClasses()['com/tcl/ff/component/vastad/c/e.class']
        assertArrayEquals(original, LsapPlaybackDiagnosticsPatcher.patch('example/Other.class', original))
    }

    private static List<MethodInsnNode> calls(byte[] bytes) {
        ClassNode node = new ClassNode()
        new ClassReader(bytes).accept(node, 0)
        node.methods.collectMany { MethodNode m -> m.instructions.toArray().findAll { it instanceof MethodInsnNode } }
    }

    private static Map<String, byte[]> vendorClasses() {
        File root = new File(System.getProperty('user.dir')).absoluteFile
        while (!new File(root, 'app/libs/addy_jams').isDirectory()) {
            root = root.parentFile
            assertNotNull('Repository root not found', root)
        }
        byte[] jar
        new ZipFile(new File(root, 'app/libs/addy_jams/lsapsdk-combine-com.google.android.addyjams-1.1.12-r2.aar')).withCloseable { zip ->
            jar = zip.getInputStream(zip.getEntry('classes.jar')).bytes
        }
        Map<String, byte[]> result = [:]
        new JarInputStream(new ByteArrayInputStream(jar)).withCloseable { input ->
            def entry
            while ((entry = input.nextJarEntry) != null) {
                if (LsapPlaybackDiagnosticsPatcher.TARGETS.contains(entry.name)) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream()
                    byte[] buffer = new byte[8192]
                    int count
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count)
                    result[entry.name] = output.toByteArray()
                }
            }
        }
        result
    }
}
