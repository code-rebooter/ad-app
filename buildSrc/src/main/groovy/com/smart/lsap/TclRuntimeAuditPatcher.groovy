package com.smart.lsap

import org.objectweb.asm.*
import org.objectweb.asm.tree.*

/** Only applied to the SHA-pinned native TCL app AARs, never Google or LSAP. */
final class TclRuntimeAuditPatcher implements Opcodes {
    private static final String BRIDGE = 'com/smart/android/ad_app/TclRuntimeBridge'
    private static final String BASE = 'com/tcl/ff/component/vastad/core/d/a'
    private static final String REQUEST = 'Lcom/tcl/ff/component/media/ima/api/VastRequest;'
    private static final String MATERIAL = 'Lcom/tcl/ff/component/media/ima/flat/AdsMaterialInfo;'
    private static final String LOADER_CALLBACK = 'com/tcl/ff/component/media/ima/tcl/DevelopedVastAdsLoader$a'
    private static final String NATIVE_REQUESTER = 'com/tcl/ff/component/media/ima/tcl/c/g'
    private static final String ADDRESS_FETCHER = 'com/tcl/ff/component/media/processor/imp/b'
    private static final String PARAMS = 'Lcom/tcl/ff/component/overseabasebusiness/requestparams/VastAdRequestParams;'
    private static final String CALLBACK = 'Lcom/tcl/ff/component/media/ima/tcl/d/a$a;'
    private static final String EXTRA = 'Lcom/tcl/ff/component/media/ima/api/AdExtra;'

    static void verify(Map<String, byte[]> entries, String bridge = BRIDGE, boolean recoverMaterialParse = false) {
        String http = 'com/tcl/ff/component/overseahttp/http/HttpRequester.class'
        if (entries.containsKey(http)) {
            ClassNode requester = new ClassNode(ASM9)
            new ClassReader(entries[http]).accept(requester, 0)
            boolean tagged = requester.methods.any { MethodNode method ->
                method.instructions.toArray().any { instruction ->
                    instruction instanceof MethodInsnNode && instruction.owner == bridge && instruction.name == 'tagRequest'
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
        if (bridge != BRIDGE || recoverMaterialParse) required['com/tcl/ff/component/media/ima/tcl/c/d.class'] += ['onMaterialParseFailure']
        if (bridge != BRIDGE) {
            required['com/tcl/ff/component/media/ima/api/VastRequest.class'] = ['bindForkedRequest']
            required[LOADER_CALLBACK + '.class'] = ['nativeRequestCommitLock', 'isNativeRequestOpen']
            required[NATIVE_REQUESTER + '.class'] = ['isNativeRequestOpen']
            required[ADDRESS_FETCHER + '.class'] = ['nativeParamsCommitLock', 'isNativeParamsOpen', 'enterParams', 'leaveScope']
        }
        // The other three AARs contain none of these native media classes.
        if (!entries.containsKey(BASE + '.class')) return
        required.each { String entry, List<String> names ->
            if (!entries.containsKey(entry)) throw new IllegalStateException("Missing TCL audit class: ${entry}")
            ClassNode node = new ClassNode(ASM9)
            new ClassReader(entries[entry]).accept(node, 0)
            Set<String> calls = new HashSet<>()
            node.methods.each { MethodNode method ->
                method.instructions.toArray().each { instruction ->
                    if (instruction instanceof MethodInsnNode && instruction.owner == bridge) calls.add(instruction.name)
                }
            }
            names.each { if (!calls.contains(it)) throw new IllegalStateException("Missing TCL audit hook: ${entry} ${it}") }
        }
        ClassNode tracking = new ClassNode(ASM9)
        new ClassReader(entries['com/tcl/ff/component/media/ima/tcl/c/d.class']).accept(tracking, 0)
        MethodNode progress = tracking.methods.find { it.name == 'a' &&
            it.desc == '(JJLcom/tcl/ff/component/media/ima/tcl/data/VideoAdInfo;Z' + REQUEST +
                ')Lcom/tcl/ff/component/media/ima/constant/AdsEvent;' }
        ['b', 'd', 'c'].each { name ->
            int count = progress?.instructions?.toArray()?.count { instruction ->
                instruction instanceof MethodInsnNode && instruction.owner == 'com/tcl/ff/component/media/ima/tcl/c/a' &&
                    instruction.name == name && instruction.desc == '(' + REQUEST + ')Z'
            } ?: 0
            if (count != 2) throw new IllegalStateException('Missing TCL native quartile catch-up: ' + name)
        }
    }

    static byte[] patch(String entry, byte[] bytes, boolean recoverMaterialParse = false, boolean guardNativeDispatch = false) {
        String name = entry.replace('.class', '')
        if (!(name.startsWith('com/tcl/ff/component/vastad/core/d/') || name in [
            'com/tcl/ff/component/media/ima/tcl/c/f',
            'com/tcl/ff/component/media/ima/tcl/c/d',
            'com/tcl/ff/component/media/ima/tcl/b/c',
            'com/tcl/ff/component/overseahttp/http/HttpRequester',
            'com/tcl/ff/component/media/processor/a/a',
            LOADER_CALLBACK, NATIVE_REQUESTER, ADDRESS_FETCHER,
            'com/tcl/ff/component/media/ima/api/VastRequest'])) return bytes
        ClassNode node = new ClassNode(ASM9)
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES)
        boolean changed = false
        node.methods.each { MethodNode method ->
            if ((method.access & (ACC_ABSTRACT | ACC_NATIVE | ACC_STATIC)) != 0) return
            if (name == 'com/tcl/ff/component/media/ima/tcl/c/d' && method.name == 'a' &&
                method.desc == '(JJLcom/tcl/ff/component/media/ima/tcl/data/VideoAdInfo;Z' + REQUEST +
                    ')Lcom/tcl/ff/component/media/ima/constant/AdsEvent;') {
                repairMissedQuartileWindows(method)
                changed = true
            }
            if (guardNativeDispatch && name == 'com/tcl/ff/component/media/ima/api/VastRequest' &&
                method.name == 'forkedAdRequest' && method.desc == '(Ljava/lang/String;)' + REQUEST) {
                // Only the pinned native fork operation may authorize a derived identity.
                method.instructions.toArray().findAll { it.opcode == ARETURN }.each { instruction ->
                    InsnList hook = new InsnList()
                    hook.add(new InsnNode(DUP))
                    hook.add(new VarInsnNode(ALOAD, 0))
                    hook.add(new InsnNode(SWAP))
                    hook.add(bridgeCall('bindForkedRequest', '(' + REQUEST + REQUEST + ')V'))
                    method.instructions.insertBefore(instruction, hook)
                }
                changed = true
            }
            if (guardNativeDispatch && method.name == 'a' && name == LOADER_CALLBACK) {
                Map<String, Integer> slots = [
                    ('(' + REQUEST + 'I)V'): 1,
                    ('(' + REQUEST + ')V'): 1,
                    ('(Lcom/tcl/ff/component/media/ima/tcl/data/VideoAdInfo;' + REQUEST + ')V'): 2
                ]
                if (slots.containsKey(method.desc)) {
                    commitVoid(method, 'nativeRequestCommitLock', '(' + REQUEST + ')Ljava/lang/Object;',
                        'isNativeRequestOpen', '(' + REQUEST + ')Z', slots[method.desc])
                    changed = true
                }
            }
            if (guardNativeDispatch && method.name in ['a', 'b'] && name == NATIVE_REQUESTER) {
                Map<String, Integer> slots = [
                    ('(' + REQUEST + CALLBACK + ')V'): 1,
                    ('(Lcom/tcl/ff/component/media/ima/tcl/xmlmodels/VAST;' + REQUEST + CALLBACK + ')V'): 2,
                    ('(' + REQUEST + 'Lcom/tcl/ff/component/media/ima/tcl/xmlmodels/VAST;' + CALLBACK + ')V'): 1,
                    ('(' + REQUEST + 'Lcom/tcl/ff/component/media/ima/tcl/xmlmodels/Ad;' +
                        'Lcom/tcl/ff/component/media/ima/flat/AdMaxDuration;' + CALLBACK + ')V'): 1
                ]
                if (slots.containsKey(method.desc)) {
                    guardVoid(method, 'isNativeRequestOpen', '(' + REQUEST + ')Z', slots[method.desc])
                    changed = true
                }
            }
            if (guardNativeDispatch && name == ADDRESS_FETCHER && method.name == 'a' &&
                method.desc in ['(II' + PARAMS + 'Lcom/tcl/ff/component/media/processor/address/data/VastUrlInfo;)V',
                    '(II' + PARAMS + 'Lcom/tcl/ff/component/media/processor/address/data/MixedWaterfallVastUrlInfo;)V']) {
                // Capture the original params owner, including delayed/null/error and mixed material callbacks.
                scoped(method, 'enterParams', '(Ljava/lang/Object;)Ljava/lang/Object;', 3)
                commitVoid(method, 'nativeParamsCommitLock', '(Ljava/lang/Object;)Ljava/lang/Object;',
                    'isNativeParamsOpen', '(Ljava/lang/Object;)Z', 3)
                changed = true
            }
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
                if (recoverMaterialParse) guardMaterialParse(method)
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

    /** Apply the same hooks to the isolated SDK, without a dependency on the app package. */
    static byte[] patchForBridge(String entry, byte[] bytes, String bridge) {
        byte[] patched = patch(entry, bytes, true, true)
        if (patched.is(bytes)) return bytes
        ClassReader reader = new ClassReader(patched)
        ClassWriter writer = new ClassWriter(0)
        reader.accept(new ClassVisitor(ASM9, writer) {
            @Override MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor target = super.visitMethod(access, name, descriptor, signature, exceptions)
                return new MethodVisitor(ASM9, target) {
                    @Override void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                        super.visitMethodInsn(opcode, owner == BRIDGE ? bridge : owner, method, desc, itf)
                    }
                }
            }
        }, 0)
        return writer.toByteArray()
    }

    /**
     * Preserve the pinned vendor body and its AdsEvent/start/complete decisions.
     * Only catch up tracking whose original time window has already been passed.
     * Native EventHandler flags remain the sole per-ad HTTP/BI dedup authority.
     */
    private static void repairMissedQuartileWindows(MethodNode method) {
        String handlerType = 'com/tcl/ff/component/media/ima/tcl/c/a'
        int handler = method.maxLocals++
        LabelNode original = new LabelNode()
        InsnList prefix = new InsnList()
        prefix.add(new VarInsnNode(LLOAD, 3)); prefix.add(new InsnNode(LCONST_0))
        prefix.add(new InsnNode(LCMP)); prefix.add(new JumpInsnNode(IFLE, original))
        prefix.add(new VarInsnNode(LLOAD, 1)); prefix.add(new InsnNode(LCONST_0))
        prefix.add(new InsnNode(LCMP)); prefix.add(new JumpInsnNode(IFLE, original))
        prefix.add(new VarInsnNode(ALOAD, 0))
        prefix.add(new FieldInsnNode(GETFIELD, 'com/tcl/ff/component/media/ima/tcl/c/d', 'b', 'Ljava/util/Map;'))
        prefix.add(new VarInsnNode(ALOAD, 5))
        prefix.add(new MethodInsnNode(INVOKEINTERFACE, 'java/util/Map', 'get', '(Ljava/lang/Object;)Ljava/lang/Object;', true))
        prefix.add(new TypeInsnNode(CHECKCAST, handlerType)); prefix.add(new VarInsnNode(ASTORE, handler))
        prefix.add(new VarInsnNode(ALOAD, handler)); prefix.add(new JumpInsnNode(IFNULL, original))
        // FIRST originally only accepts [duration/4, duration/3).
        // MID originally only accepts [duration/2, 3*(duration/4)).
        // THIRD originally only accepts [3*(duration/4), duration).
        [['b', 3L, 1L], ['d', 4L, 3L], ['c', 1L, 1L]].each { spec ->
            LabelNode next = new LabelNode()
            prefix.add(new VarInsnNode(LLOAD, 1)); prefix.add(new VarInsnNode(LLOAD, 3))
            if (spec[1] != 1L) { prefix.add(new LdcInsnNode(spec[1])); prefix.add(new InsnNode(LDIV)) }
            if (spec[2] != 1L) { prefix.add(new LdcInsnNode(spec[2])); prefix.add(new InsnNode(LMUL)) }
            prefix.add(new InsnNode(LCMP)); prefix.add(new JumpInsnNode(IFLT, next))
            prefix.add(new VarInsnNode(ALOAD, handler)); prefix.add(new VarInsnNode(ALOAD, 7))
            prefix.add(new MethodInsnNode(INVOKEINTERFACE, handlerType, (String) spec[0], '(' + REQUEST + ')Z', true))
            prefix.add(new InsnNode(POP)); prefix.add(next)
        }
        prefix.add(original)
        method.instructions.insert(prefix)
    }

    /** Hold only a per-session callback commit lock; no HTTP, patch-await or parser is inside. */
    private static void commitVoid(MethodNode method, String acquire, String acquireDesc,
                                   String gate, String gateDesc, int argument) {
        int monitor = method.maxLocals++
        int failure = method.maxLocals++
        LabelNode present = new LabelNode(), start = new LabelNode(), denied = new LabelNode()
        LabelNode end = new LabelNode(), handler = new LabelNode()
        // Save the original exits before adding the no-token return (which owns no monitor).
        def exits = method.instructions.toArray().findAll { it.opcode == RETURN }
        prepend(method, [new VarInsnNode(ALOAD, argument), bridgeCall(acquire, acquireDesc),
            new InsnNode(DUP), new VarInsnNode(ASTORE, monitor), new JumpInsnNode(IFNONNULL, present),
            new InsnNode(RETURN), present, new VarInsnNode(ALOAD, monitor), new InsnNode(MONITORENTER),
            start, new VarInsnNode(ALOAD, argument), bridgeCall(gate, gateDesc),
            new JumpInsnNode(IFEQ, denied)])
        exits.each { instruction ->
            InsnList leave = new InsnList()
            leave.add(new VarInsnNode(ALOAD, monitor)); leave.add(new InsnNode(MONITOREXIT))
            method.instructions.insertBefore(instruction, leave)
        }
        method.instructions.add(denied)
        method.instructions.add(new VarInsnNode(ALOAD, monitor))
        method.instructions.add(new InsnNode(MONITOREXIT))
        method.instructions.add(new InsnNode(RETURN))
        method.instructions.add(end)
        method.instructions.add(handler)
        method.instructions.add(new VarInsnNode(ASTORE, failure))
        method.instructions.add(new VarInsnNode(ALOAD, monitor))
        method.instructions.add(new InsnNode(MONITOREXIT))
        method.instructions.add(new VarInsnNode(ALOAD, failure))
        method.instructions.add(new InsnNode(ATHROW))
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null))
    }

    /** Reject a stale native request before its map, material or player side effects. SDK only. */
    private static void guardVoid(MethodNode method, String gate, String descriptor, int argument) {
        LabelNode allowed = new LabelNode()
        prepend(method, [new VarInsnNode(ALOAD, argument), bridgeCall(gate, descriptor),
            new JumpInsnNode(IFNE, allowed), new InsnNode(RETURN), allowed])
    }

    /** Native caller already handles null VideoAdInfo as its material failure path. */
    private static void guardMaterialParse(MethodNode method) {
        // The vendor reuses argument slots for other local types; retain identities
        // outside those slots so every exception edge has valid typed operands.
        int request = method.maxLocals++
        int ad = method.maxLocals++
        int failure = method.maxLocals++
        LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode()
        prepend(method, [new VarInsnNode(ALOAD, 1), new VarInsnNode(ASTORE, request),
            new VarInsnNode(ALOAD, 2), new VarInsnNode(ASTORE, ad), start])
        method.instructions.add(end)
        method.instructions.add(handler)
        method.instructions.add(new VarInsnNode(ASTORE, failure))
        method.instructions.add(new VarInsnNode(ALOAD, request))
        method.instructions.add(new VarInsnNode(ALOAD, ad))
        method.instructions.add(new VarInsnNode(ALOAD, failure))
        method.instructions.add(bridgeCall('onMaterialParseFailure', '(' + REQUEST +
            'Lcom/tcl/ff/component/media/ima/tcl/xmlmodels/Ad;Ljava/lang/RuntimeException;)V'))
        method.instructions.add(new InsnNode(ACONST_NULL))
        method.instructions.add(new InsnNode(ARETURN))
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, 'java/lang/RuntimeException'))
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
