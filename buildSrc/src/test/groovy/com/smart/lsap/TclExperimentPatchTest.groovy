package com.smart.lsap

import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import java.util.zip.ZipInputStream
import static org.junit.Assert.*

/** Compare the real pinned runtime, including existing isolation boundaries. */
class TclExperimentPatchTest {
    private static final String BRIDGE = 'com/smart/android/ad_app/Hq008XhsxAarRuntimeBridge'
    private static final Map<String, String> SXK_CLASSES = [
        'com/tcl/ff/component/uniplayer/f/k.class': 'shouldApplySxkPlaybackPatch',
        'com/tcl/uniplayer/tuniplayer/b.class': 'shouldApplySxkPlaybackPatch',
        'com/tcl/tuniplayer_exo/a.class': 'shouldApplySxkPlaybackPatch',
        'com/google/android/exoplayer2/video/MediaCodecVideoRenderer.class': 'shouldRenderSxkVideoFrame'
    ]

    @Test void 'all retained common class patches match the existing TCL patch byte for byte'() {
        int matched = 0, changed = 0
        isolatedClasses().each { name, original ->
            if (SXK_CLASSES.containsKey(name)) return
            byte[] legacy = LsapClassPatcher.patchHq008Parameters(name, original)
            byte[] experiment = LsapClassPatcher.patchTclExperimentParameters(name, original)
            assertArrayEquals(name, legacy, experiment)
            matched++
            if (!Arrays.equals(original, experiment)) changed++
        }
        assertEquals(2211, matched)
        assertTrue('The comparison must include actual common patches', changed > 0)
    }

    @Test void 'none of the four SXK specific player policies enter the experimental patch'() {
        def classes = isolatedClasses()
        SXK_CLASSES.each { name, method ->
            byte[] legacy = LsapClassPatcher.patchHq008Parameters(name, classes[name])
            byte[] experiment = LsapClassPatcher.patchTclExperimentParameters(name, classes[name])
            String desc = method == 'shouldRenderSxkVideoFrame' ? '(Ljava/lang/Object;J)Z' : '()Z'
            assertEquals(name, 1, LsapClassPatcher.countMethodCalls(legacy, BRIDGE, method, desc))
            assertEquals(name, 0, LsapClassPatcher.countMethodCalls(experiment, BRIDGE, method, desc))
        }
    }

    @Test void 'common patches preserve generic POST and isolate the business address boundary and XML types'() {
        def classes = isolatedClasses()
        assertFalse(classes.keySet().any { TclRuntimeIsolation.removedName(it) })
        String httpName = 'com/tcl/ff/component/overseahttp/http/HttpRequester.class'
        // Common UA/network bridges are intentionally applied to both paths.
        ClassNode before = read(LsapClassPatcher.patchHq008Parameters(httpName, classes[httpName]))
        ClassNode http = read(LsapClassPatcher.patchTclExperimentParameters(httpName, classes[httpName]))
        String postDescriptor = '(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;'
        assertArrayEquals('The original generic POST must remain intact',
                methodBytes(before, 'postJsonSync', postDescriptor),
                methodBytes(http, 'postJsonSync', postDescriptor))
        def post = http.methods.find { it.name == 'postJsonSync' && it.desc == postDescriptor }
        assertTrue('Generic POST must still reach the common OkHttp network bridge',
                post.instructions.toArray().any { it instanceof MethodInsnNode &&
                        it.owner == BRIDGE && it.name == 'newOkHttpCall' })
        def boundary = http.methods.find { it.name == 'resolveSuppliedAdAddress' && it.desc == postDescriptor }
        assertNotNull('The isolated business address boundary must be present', boundary)
        assertEquals([Opcodes.ACONST_NULL, Opcodes.ARETURN],
                boundary.instructions.toArray().findAll { it.opcode >= 0 }.collect { it.opcode })
        String xmlName = 'com/tcl/ff/component/media/ima/tcl/convert/XmlConverter.class'
        byte[] xml = LsapClassPatcher.patchTclExperimentParameters(xmlName, classes[xmlName])
        assertEquals(1, LsapClassPatcher.countMethodCalls(xml, 'com/thoughtworks/xstream/XStream', 'allowTypesByWildcard', '([Ljava/lang/String;)V'))
        assertFalse(new String(xml, 'ISO-8859-1').contains('AnyTypePermission'))
    }

    private static byte[] methodBytes(ClassNode node, String name, String descriptor) {
        def method = node.methods.find { it.name == name && it.desc == descriptor }
        assertNotNull(name + descriptor, method)
        ClassWriter writer = new ClassWriter(0)
        writer.visit(node.version, node.access, node.name, node.signature, node.superName,
                node.interfaces as String[])
        method.accept(writer)
        writer.visitEnd()
        return writer.toByteArray()
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode result = new ClassNode()
        new ClassReader(bytes).accept(result, 0)
        return result
    }

    private static Map<String, byte[]> isolatedClasses() {
        File repo = new File(System.getProperty('user.dir')).absoluteFile
        while (!new File(repo, 'ad-sdk-tcl/vendor/tcl-base-2.8.02.aar').isFile()) {
            repo = repo.parentFile
            if (repo == null) throw new IllegalStateException('Pinned TCL input missing')
        }
        Map<String, byte[]> result = [:]
        ['base', 'media', 'player'].each { component ->
            byte[] jar = null
            new ZipInputStream(new FileInputStream(new File(repo, "ad-sdk-tcl/vendor/tcl-${component}-2.8.02.aar"))).withCloseable { zip ->
                def entry
                while ((entry = zip.nextEntry) != null) if (entry.name == 'classes.jar') jar = zip.readAllBytes()
            }
            new ZipInputStream(new ByteArrayInputStream(TclRuntimeIsolation.isolate(jar).bytes)).withCloseable { zip ->
                def entry
                while ((entry = zip.nextEntry) != null) if (entry.name.endsWith('.class')) result[entry.name] = zip.readAllBytes()
            }
        }
        return result
    }
}
