package com.smart.lsap

import org.objectweb.asm.*
import org.objectweb.asm.tree.*

/** Only applied to the SHA-pinned native TCL app AARs, never Google or LSAP. */
final class TclRuntimeAuditPatcher implements Opcodes {
    private static final String BRIDGE = 'com/smart/android/ad_app/TclRuntimeBridge'
    private static final String BASE = 'com/tcl/ff/component/vastad/core/d/a'
    private static final String REQUEST = 'Lcom/tcl/ff/component/media/ima/api/VastRequest;'
    private static final String MATERIAL = 'Lcom/tcl/ff/component/media/ima/flat/AdsMaterialInfo;'
    private static final String EXTRA = 'Lcom/tcl/ff/component/media/ima/api/AdExtra;'

    static void verify(Map<String, byte[]> entries) {
        String http = 'com/tcl/ff/component/overseahttp/http/HttpRequester.class'
        if (entries.containsKey(http)) {
            ClassNode requester = new ClassNode(ASM9)
            new ClassReader(entries[http]).accept(requester, 0)
            boolean tagged = requester.methods.any { MethodNode method ->
                method.instructions.toArray().any { instruction ->
                    instruction instanceof MethodInsnNode && instruction.owner == BRIDGE && instruction.name == 'tagRequest'
                }
            }
            if (!tagged) throw new IllegalStateException('Missing TCL async HTTP owner tagging')
        }
        Map<String, List<String>> required = [
            (BASE + '.class'): ['bindEngine', 'bindParams'],
            'com/tcl/ff/component/vastad/core/d/e.class': ['onTag', 'onSdkEvent', 'onNativeEvent'],
            'com/tcl/ff/component/media/processor/a/a.class': ['enterParams', 'patchAddressResponse', 'leaveScope'],
            'com/tcl/ff/component/media/ima/tcl/c/f.class': ['enterRequest', 'onXml', 'leaveScope'],
            'com/tcl/ff/component/media/ima/tcl/c/d.class': ['enterRequest', 'leaveScope'],
            'com/tcl/ff/component/media/ima/tcl/b/c.class': ['bindReporter', 'enterTracking', 'leaveScope']
        ]
        // The other three AARs contain none of these native media classes.
        if (!entries.containsKey(BASE + '.class')) return
        required.each { String entry, List<String> names ->
            if (!entries.containsKey(entry)) throw new IllegalStateException("Missing TCL audit class: ${entry}")
            ClassNode node = new ClassNode(ASM9)
            new ClassReader(entries[entry]).accept(node, 0)
            Set<String> calls = new HashSet<>()
            node.methods.each { MethodNode method ->
                method.instructions.toArray().each { instruction ->
                    if (instruction instanceof MethodInsnNode && instruction.owner == BRIDGE) calls.add(instruction.name)
                }
            }
            names.each { if (!calls.contains(it)) throw new IllegalStateException("Missing TCL audit hook: ${entry} ${it}") }
        }
    }

    static byte[] patch(String entry, byte[] bytes) {
        String name = entry.replace('.class', '')
        if (!(name.startsWith('com/tcl/ff/component/vastad/core/d/') || name in [
            'com/tcl/ff/component/media/ima/tcl/c/f',
            'com/tcl/ff/component/media/ima/tcl/c/d',
            'com/tcl/ff/component/media/ima/tcl/b/c',
            'com/tcl/ff/component/overseahttp/http/HttpRequester',
            'com/tcl/ff/component/media/processor/a/a'])) return bytes
        ClassNode node = new ClassNode(ASM9)
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES)
        boolean changed = false
        node.methods.each { MethodNode method ->
            if ((method.access & (ACC_ABSTRACT | ACC_NATIVE | ACC_STATIC)) != 0) return
            if (name == 'com/tcl/ff/component/overseahttp/http/HttpRequester') {
                method.instructions.toArray().each { instruction ->
                    if (instruction instanceof MethodInsnNode &&
                        ((instruction.owner == 'okhttp3/OkHttpClient' && instruction.name == 'newCall' &&
                            instruction.desc == '(Lokhttp3/Request;)Lokhttp3/Call;') ||
                         (instruction.owner == 'com/smart/android/ad_app/Hq008XhsxAarRuntimeBridge' &&
                            instruction.name == 'newOkHttpCall' &&
                            instruction.desc == '(Lokhttp3/OkHttpClient;Lokhttp3/Request;)Lokhttp3/Call;'))) {
                        method.instructions.insertBefore(instruction,
                            bridgeCall('tagRequest', '(Lokhttp3/Request;)Lokhttp3/Request;'))
                        changed = true
                    }
                }
            }
            if (name == BASE && method.name == 'a' && method.desc.startsWith('(Landroid/content/Context;')) {
                prepend(method, [new VarInsnNode(ALOAD, 0), bridgeCall('bindEngine', '(Ljava/lang/Object;)V')])
                changed = true
            }
            if (name.startsWith('com/tcl/ff/component/vastad/core/d/')) {
                if (method.name == 'f' && method.desc == '()Lcom/tcl/ff/component/overseabasebusiness/requestparams/VastAdRequestParams;') {
                    method.instructions.toArray().findAll { it.opcode == ARETURN }.each { instruction ->
                        InsnList hook = new InsnList()
                        hook.add(new InsnNode(DUP))
                        hook.add(new VarInsnNode(ALOAD, 0))
                        hook.add(bridgeCall('bindParams', '(Ljava/lang/Object;Ljava/lang/Object;)V'))
                        method.instructions.insertBefore(instruction, hook)
                    }
                    changed = true
                }
                if (method.name == 'onAdTagLoaded' && method.desc.startsWith('(' + REQUEST)) {
                    prepend(method, [new VarInsnNode(ALOAD, 0), new VarInsnNode(ALOAD, 1),
                        bridgeCall('onTag', '(Ljava/lang/Object;' + REQUEST + ')V')])
                    changed = true
                }
                if (method.name == 'onEvent' && method.desc == '(' + REQUEST + 'Lcom/tcl/ff/component/media/ima/constant/AdsEvent;)V') {
                    prepend(method, [new VarInsnNode(ALOAD, 0), new VarInsnNode(ALOAD, 1),
                        new VarInsnNode(ALOAD, 2), bridgeCall('onSdkEvent', '(Ljava/lang/Object;' + REQUEST +
                            'Lcom/tcl/ff/component/media/ima/constant/AdsEvent;)V')])
                    changed = true
                }
                Map<String, String> events = [onLoadAd: 'material_loaded', onPlayAd: 'material_selected',
                    onPauseAd: 'material_paused', onStopAd: 'material_stopped',
                    onFirstFrame: 'first_frame', onVideoLoading: 'buffering',
                    onVideoStartPlaying: 'video_started', onVideoPrepared: 'video_prepared',
                    onComplete: 'native_media_complete', onError: 'native_player_error']
                if (events.containsKey(method.name)) {
                    prepend(method, [new VarInsnNode(ALOAD, 0), new LdcInsnNode(events[method.name]),
                        method.desc.startsWith('(' + MATERIAL) ? new VarInsnNode(ALOAD, 1) : new InsnNode(ACONST_NULL),
                        method.desc.startsWith('(' + MATERIAL + EXTRA) ? new VarInsnNode(ALOAD, 2) : new InsnNode(ACONST_NULL),
                        bridgeCall('onNativeEvent', '(Ljava/lang/Object;Ljava/lang/String;' + MATERIAL + EXTRA + ')V')])
                    changed = true
                }
                if (method.name == 'onError' && method.desc == '(Lcom/tcl/ff/component/uniplayer/data/ResultCode;II)V') {
                    prepend(method, [new VarInsnNode(ALOAD, 0), new VarInsnNode(ALOAD, 1),
                        new VarInsnNode(ILOAD, 2), new VarInsnNode(ILOAD, 3),
                        bridgeCall('onPlayerError', '(Ljava/lang/Object;Ljava/lang/Object;II)V')])
                    changed = true
                }
            }
            if (name == 'com/tcl/ff/component/media/ima/tcl/c/f' && method.name == 'a' &&
                method.desc == '(Lcom/tcl/ff/component/overseahttp/http/HttpRequester;' + REQUEST + ')Lcom/tcl/ff/component/media/ima/tcl/xmlmodels/VAST;') {
                method.instructions.toArray().each { instruction ->
                    if (instruction instanceof MethodInsnNode && instruction.owner == name &&
                        instruction.name == 'b' && instruction.desc.endsWith(')Ljava/lang/String;')) {
                        InsnList hook = new InsnList()
                        hook.add(new InsnNode(DUP))
                        hook.add(new VarInsnNode(ALOAD, 2))
                        hook.add(bridgeCall('onXml', '(Ljava/lang/String;' + REQUEST + ')V'))
                        method.instructions.insert(instruction, hook)
                    }
                }
                scoped(method, 'enterRequest', '(' + REQUEST + ')Ljava/lang/Object;', 2)
                changed = true
            }
            if (name == 'com/tcl/ff/component/media/ima/tcl/c/d' && method.name == 'a' &&
                method.desc == '(' + REQUEST + 'Lcom/tcl/ff/component/media/ima/tcl/xmlmodels/Ad;' +
                    'Lcom/tcl/ff/component/media/ima/flat/AdMaxDuration;)Lcom/tcl/ff/component/media/ima/tcl/data/VideoAdInfo;') {
                scoped(method, 'enterRequest', '(' + REQUEST + ')Ljava/lang/Object;', 1)
                changed = true
            }
            if (name == 'com/tcl/ff/component/media/processor/a/a' && method.name == 'a' &&
                method.desc.startsWith('(Ljava/lang/String;Lcom/tcl/ff/component/overseabasebusiness/requestparams/VastAdRequestParams;') &&
                method.desc.endsWith(')V')) {
                method.instructions.toArray().each { instruction ->
                    if (instruction instanceof MethodInsnNode &&
                        instruction.owner == 'com/tcl/ff/component/media/processor/a/b' &&
                        instruction.desc.endsWith(')Ljava/lang/Object;')) {
                        method.instructions.insert(instruction,
                            bridgeCall('patchAddressResponse', '(Ljava/lang/Object;)Ljava/lang/Object;'))
                    }
                }
                scoped(method, 'enterParams', '(Ljava/lang/Object;)Ljava/lang/Object;', 2)
                changed = true
            }
            if (name == 'com/tcl/ff/component/media/ima/tcl/b/c') {
                if (method.name == '<init>') {
                    method.instructions.toArray().findAll { it.opcode == RETURN }.each { instruction ->
                        InsnList hook = new InsnList()
                        hook.add(new VarInsnNode(ALOAD, 0))
                        hook.add(bridgeCall('bindReporter', '(Ljava/lang/Object;)V'))
                        method.instructions.insertBefore(instruction, hook)
                    }
                    changed = true
                }
                if (method.name in ['a', 'c'] && method.desc == '()V') {
                    scoped(method, 'enterTracking', '(Ljava/lang/Object;)Ljava/lang/Object;', 0)
                    changed = true
                }
            }
        }
        if (!changed) return bytes
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String left, String right) { 'java/lang/Object' }
        }
        node.accept(writer)
        writer.toByteArray()
    }

    private static MethodInsnNode bridgeCall(String method, String descriptor) {
        new MethodInsnNode(INVOKESTATIC, BRIDGE, method, descriptor, false)
    }

    private static void prepend(MethodNode method, List<AbstractInsnNode> instructions) {
        InsnList hook = new InsnList()
        instructions.each { hook.add(it) }
        method.instructions.insert(hook)
    }

    /** Restore the worker's previous owner on every return and every exception. */
    private static void scoped(MethodNode method, String entry, String descriptor, int argument) {
        int previous = method.maxLocals++
        int failure = method.maxLocals++
        LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode()
        prepend(method, [new VarInsnNode(ALOAD, argument), bridgeCall(entry, descriptor),
            new VarInsnNode(ASTORE, previous), start])
        method.instructions.toArray().findAll { it.opcode in [RETURN, ARETURN, IRETURN, LRETURN, FRETURN, DRETURN] }.each { instruction ->
            InsnList leave = new InsnList()
            leave.add(new VarInsnNode(ALOAD, previous))
            leave.add(bridgeCall('leaveScope', '(Ljava/lang/Object;)V'))
            method.instructions.insertBefore(instruction, leave)
        }
        method.instructions.add(end)
        method.instructions.add(handler)
        method.instructions.add(new VarInsnNode(ASTORE, failure))
        method.instructions.add(new VarInsnNode(ALOAD, previous))
        method.instructions.add(bridgeCall('leaveScope', '(Ljava/lang/Object;)V'))
        method.instructions.add(new VarInsnNode(ALOAD, failure))
        method.instructions.add(new InsnNode(ATHROW))
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null))
    }
}
