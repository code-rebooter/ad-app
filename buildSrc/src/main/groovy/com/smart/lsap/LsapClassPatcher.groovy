package com.smart.lsap

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*

final class LsapClassPatcher implements Opcodes {
    private static final String BRIDGE = 'com/smart/android/ad_app/HaierAarRuntimeBridge'
    private static final Set<String> URL_CONNECTION_OWNERS = [
        'java/net/URLConnection',
        'java/net/HttpURLConnection',
        'javax/net/ssl/HttpsURLConnection'
    ] as Set
    private static final Set<String> SCHEDULED_EXECUTOR_OWNERS = [
        'java/util/concurrent/ScheduledExecutorService',
        'java/util/concurrent/ScheduledThreadPoolExecutor'
    ] as Set

    static byte[] patch(String entryName, byte[] bytes) {
        ClassNode node = new ClassNode()
        ClassReader reader = new ClassReader(bytes)
        reader.accept(node, ClassReader.EXPAND_FRAMES)
        boolean changed = false

        node.methods.each { MethodNode method ->
            for (AbstractInsnNode instruction = method.instructions.first;
                 instruction != null;) {
                AbstractInsnNode nextInstruction = instruction.next
                if (instruction instanceof FieldInsnNode) {
                    FieldInsnNode field = (FieldInsnNode) instruction
                    if (field.opcode == GETSTATIC && field.owner == 'android/os/Build$VERSION' &&
                        field.name == 'RELEASE' && field.desc == 'Ljava/lang/String;') {
                        method.instructions.set(
                            field,
                            new MethodInsnNode(
                                INVOKESTATIC,
                                BRIDGE,
                                'getAndroidVersionRelease',
                                '()Ljava/lang/String;',
                                false
                            )
                        )
                        changed = true
                    }
                    if (field.opcode == GETSTATIC && field.owner == 'android/os/Build$VERSION' &&
                        field.name == 'SDK_INT' && field.desc == 'I' &&
                        entryName == 'd/a/a/a.class') {
                        method.instructions.set(
                            field,
                            new MethodInsnNode(
                                INVOKESTATIC,
                                BRIDGE,
                                'getAndroidSdkInt',
                                '()I',
                                false
                            )
                        )
                        changed = true
                    }
                    if (field.opcode == GETSTATIC && field.owner == 'android/os/Build' &&
                        field.name == 'MODEL' && field.desc == 'Ljava/lang/String;') {
                        method.instructions.set(
                            field,
                            new MethodInsnNode(
                                INVOKESTATIC,
                                BRIDGE,
                                'getAndroidDeviceModel',
                                '()Ljava/lang/String;',
                                false
                            )
                        )
                        changed = true
                    }
                    if (field.opcode == GETSTATIC && field.owner == 'android/os/Build' &&
                        field.name == 'ID' && field.desc == 'Ljava/lang/String;') {
                        method.instructions.set(
                            field,
                            new MethodInsnNode(
                                INVOKESTATIC,
                                BRIDGE,
                                'getAndroidBuildId',
                                '()Ljava/lang/String;',
                                false
                            )
                        )
                        changed = true
                    }
                    if (field.opcode == GETSTATIC && field.owner == 'android/os/Build' &&
                        field.name == 'BRAND' && field.desc == 'Ljava/lang/String;') {
                        method.instructions.set(
                            field,
                            new MethodInsnNode(
                                INVOKESTATIC,
                                BRIDGE,
                                'getAndroidBrand',
                                '()Ljava/lang/String;',
                                false
                            )
                        )
                        changed = true
                    }
                    if (field.opcode == GETSTATIC && field.owner == 'android/os/Build' &&
                        field.name == 'DEVICE' && field.desc == 'Ljava/lang/String;') {
                        method.instructions.set(
                            field,
                            new MethodInsnNode(
                                INVOKESTATIC,
                                BRIDGE,
                                'getAndroidDevice',
                                '()Ljava/lang/String;',
                                false
                            )
                        )
                        changed = true
                    }
                    if (field.opcode == GETSTATIC && field.owner == 'android/os/Build' &&
                        field.name == 'MANUFACTURER' && field.desc == 'Ljava/lang/String;') {
                        method.instructions.set(
                            field,
                            new MethodInsnNode(
                                INVOKESTATIC,
                                BRIDGE,
                                'getAndroidManufacturer',
                                '()Ljava/lang/String;',
                                false
                            )
                        )
                        changed = true
                    }
                    if (field.opcode == GETSTATIC && field.owner == 'android/os/Build' &&
                        field.name == 'PRODUCT' && field.desc == 'Ljava/lang/String;') {
                        method.instructions.set(
                            field,
                            new MethodInsnNode(
                                INVOKESTATIC,
                                BRIDGE,
                                'getAndroidProduct',
                                '()Ljava/lang/String;',
                                false
                            )
                        )
                        changed = true
                    }
                }
                if (!(instruction instanceof MethodInsnNode)) {
                    instruction = nextInstruction
                    continue
                }
                MethodInsnNode call = (MethodInsnNode) instruction

                if (call.opcode == INVOKESTATIC && call.owner == 'java/lang/System' &&
                    call.name == 'getProperty' && call.desc == '(Ljava/lang/String;)Ljava/lang/String;') {
                    call.owner = BRIDGE
                    call.name = 'getSystemProperty'
                    changed = true
                }
                if (call.opcode == INVOKESTATIC && call.owner == 'java/lang/System' &&
                    call.name == 'load' && call.desc == '(Ljava/lang/String;)V') {
                    call.owner = BRIDGE
                    call.name = 'systemLoad'
                    changed = true
                }
                if (call.opcode == INVOKESTATIC && call.owner == 'java/lang/System' &&
                    call.name == 'loadLibrary' && call.desc == '(Ljava/lang/String;)V') {
                    call.owner = BRIDGE
                    call.name = 'systemLoadLibrary'
                    changed = true
                }
                if (call.opcode == INVOKESTATIC && call.owner == 'titan/sdk/android/TitanSDK' &&
                    call.name == 'nativeStart' && call.desc == '(Ljava/lang/String;Ljava/lang/String;)I') {
                    call.owner = BRIDGE
                    call.name = 'nativeStart'
                    changed = true
                }
                if (call.opcode == INVOKESTATIC && call.owner == 'titan/sdk/android/TitanSDK' &&
                    call.name == 'setNetwork' && call.desc == '(I)I') {
                    call.owner = BRIDGE
                    call.name = 'setTitanNetwork'
                    changed = true
                }
                if (call.opcode == INVOKESTATIC && call.owner == 'titan/sdk/android/TitanSDK' &&
                    call.name == 'nativeStop' && call.desc == '()V') {
                    call.owner = BRIDGE
                    call.name = 'nativeStop'
                    changed = true
                }
                if (entryName == 'd/b/d/a.class' &&
                    call.opcode == INVOKEVIRTUAL && call.owner == 'java/lang/reflect/Method' &&
                    call.name == 'invoke' && call.desc == '(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;') {
                    call.opcode = INVOKESTATIC
                    call.owner = BRIDGE
                    call.name = 'invokeDynamicMethod'
                    call.desc = '(Ljava/lang/reflect/Method;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;'
                    call.itf = false
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL && call.owner == 'android/webkit/WebSettings' &&
                    call.name == 'setUserAgentString' && call.desc == '(Ljava/lang/String;)V') {
                    call.opcode = INVOKESTATIC
                    call.owner = BRIDGE
                    call.name = 'setWebViewUserAgent'
                    call.desc = '(Landroid/webkit/WebSettings;Ljava/lang/String;)V'
                    call.itf = false
                    changed = true
                }
                if (call.opcode == INVOKESTATIC && call.owner == 'android/webkit/WebSettings' &&
                    call.name == 'getDefaultUserAgent' &&
                    call.desc == '(Landroid/content/Context;)Ljava/lang/String;') {
                    call.owner = BRIDGE
                    call.name = 'getDefaultWebViewUserAgent'
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL && call.owner == 'android/webkit/WebSettings' &&
                    call.name == 'getUserAgentString' && call.desc == '()Ljava/lang/String;') {
                    replaceWithStatic(call, 'getWebViewUserAgent',
                        '(Landroid/webkit/WebSettings;)Ljava/lang/String;')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL && call.owner == 'android/webkit/WebView' &&
                    call.name == 'setWebViewClient' &&
                    call.desc == '(Landroid/webkit/WebViewClient;)V') {
                    replaceWithStatic(call, 'setAuditedWebViewClient',
                        '(Landroid/webkit/WebView;Landroid/webkit/WebViewClient;)V')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL && call.owner == 'android/webkit/WebView' &&
                    call.name == 'loadUrl' && call.desc == '(Ljava/lang/String;)V') {
                    replaceWithStatic(call, 'loadWebViewUrl',
                        '(Landroid/webkit/WebView;Ljava/lang/String;)V')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL && call.owner == 'android/webkit/WebView' &&
                    call.name == 'loadUrl' && call.desc == '(Ljava/lang/String;Ljava/util/Map;)V') {
                    replaceWithStatic(call, 'loadWebViewUrlWithHeaders',
                        '(Landroid/webkit/WebView;Ljava/lang/String;Ljava/util/Map;)V')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL && call.owner == 'android/webkit/WebView' &&
                    call.name == 'postUrl' && call.desc == '(Ljava/lang/String;[B)V') {
                    replaceWithStatic(call, 'postWebViewUrl',
                        '(Landroid/webkit/WebView;Ljava/lang/String;[B)V')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL && call.owner == 'java/net/URL' &&
                    call.name == 'openConnection' && call.desc == '()Ljava/net/URLConnection;') {
                    replaceWithStatic(call, 'openUrlConnection',
                        '(Ljava/net/URL;)Ljava/net/URLConnection;')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL && call.owner == 'java/net/URL' &&
                    call.name == 'openConnection' &&
                    call.desc == '(Ljava/net/Proxy;)Ljava/net/URLConnection;') {
                    replaceWithStatic(call, 'openUrlConnectionWithProxy',
                        '(Ljava/net/URL;Ljava/net/Proxy;)Ljava/net/URLConnection;')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL &&
                    URL_CONNECTION_OWNERS.contains(call.owner) &&
                    call.name == 'setRequestProperty' && call.desc == '(Ljava/lang/String;Ljava/lang/String;)V') {
                    replaceWithStatic(call, 'setUrlConnectionRequestProperty',
                        '(Ljava/net/URLConnection;Ljava/lang/String;Ljava/lang/String;)V')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL &&
                    URL_CONNECTION_OWNERS.contains(call.owner) &&
                    call.name == 'addRequestProperty' && call.desc == '(Ljava/lang/String;Ljava/lang/String;)V') {
                    replaceWithStatic(call, 'addUrlConnectionRequestProperty',
                        '(Ljava/net/URLConnection;Ljava/lang/String;Ljava/lang/String;)V')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL &&
                    (call.owner == 'java/net/HttpURLConnection' ||
                        call.owner == 'javax/net/ssl/HttpsURLConnection') &&
                    call.name == 'setRequestMethod' && call.desc == '(Ljava/lang/String;)V') {
                    replaceWithStatic(call, 'setUrlConnectionRequestMethod',
                        '(Ljava/net/HttpURLConnection;Ljava/lang/String;)V')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL && URL_CONNECTION_OWNERS.contains(call.owner) &&
                    call.name == 'connect' && call.desc == '()V') {
                    replaceWithStatic(call, 'connectUrlConnection', '(Ljava/net/URLConnection;)V')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL && URL_CONNECTION_OWNERS.contains(call.owner) &&
                    call.name == 'getOutputStream' && call.desc == '()Ljava/io/OutputStream;') {
                    replaceWithStatic(call, 'getUrlConnectionOutputStream',
                        '(Ljava/net/URLConnection;)Ljava/io/OutputStream;')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL && URL_CONNECTION_OWNERS.contains(call.owner) &&
                    call.name == 'getInputStream' && call.desc == '()Ljava/io/InputStream;') {
                    replaceWithStatic(call, 'getUrlConnectionInputStream',
                        '(Ljava/net/URLConnection;)Ljava/io/InputStream;')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL &&
                    (call.owner == 'java/net/HttpURLConnection' ||
                        call.owner == 'javax/net/ssl/HttpsURLConnection') &&
                    call.name == 'getResponseCode' && call.desc == '()I') {
                    replaceWithStatic(call, 'getUrlConnectionResponseCode',
                        '(Ljava/net/HttpURLConnection;)I')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL &&
                    (call.owner == 'java/net/HttpURLConnection' ||
                        call.owner == 'javax/net/ssl/HttpsURLConnection') &&
                    call.name == 'getErrorStream' && call.desc == '()Ljava/io/InputStream;') {
                    replaceWithStatic(call, 'getUrlConnectionErrorStream',
                        '(Ljava/net/HttpURLConnection;)Ljava/io/InputStream;')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL &&
                    (call.owner == 'java/net/HttpURLConnection' ||
                        call.owner == 'javax/net/ssl/HttpsURLConnection') &&
                    call.name == 'disconnect' && call.desc == '()V') {
                    replaceWithStatic(call, 'disconnectUrlConnection',
                        '(Ljava/net/HttpURLConnection;)V')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL && call.owner == 'okhttp3/OkHttpClient' &&
                    call.name == 'newCall' &&
                    call.desc == '(Lokhttp3/Request;)Lokhttp3/Call;') {
                    replaceWithStatic(call, 'newOkHttpCall',
                        '(Lokhttp3/OkHttpClient;Lokhttp3/Request;)Lokhttp3/Call;')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL && call.owner == 'java/net/DatagramSocket' &&
                    call.name == 'send' && call.desc == '(Ljava/net/DatagramPacket;)V') {
                    replaceWithStatic(call, 'sendDatagram',
                        '(Ljava/net/DatagramSocket;Ljava/net/DatagramPacket;)V')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL && call.owner == 'android/os/Handler' &&
                    call.name == 'post' && call.desc == '(Ljava/lang/Runnable;)Z') {
                    replaceWithStatic(call, 'postHandler',
                        '(Landroid/os/Handler;Ljava/lang/Runnable;)Z')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL && call.owner == 'android/os/Handler' &&
                    call.name == 'postDelayed' && call.desc == '(Ljava/lang/Runnable;J)Z') {
                    replaceWithStatic(call, 'postDelayedHandler',
                        '(Landroid/os/Handler;Ljava/lang/Runnable;J)Z')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL && call.owner == 'java/util/Timer' &&
                    call.name == 'schedule' &&
                    call.desc == '(Ljava/util/TimerTask;J)V') {
                    replaceWithStatic(call, 'scheduleTimer',
                        '(Ljava/util/Timer;Ljava/util/TimerTask;J)V')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL && call.owner == 'java/util/Timer' &&
                    call.name == 'schedule' &&
                    call.desc == '(Ljava/util/TimerTask;Ljava/util/Date;)V') {
                    replaceWithStatic(call, 'scheduleTimerAtDate',
                        '(Ljava/util/Timer;Ljava/util/TimerTask;Ljava/util/Date;)V')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL && call.owner == 'java/util/Timer' &&
                    call.name == 'schedule' &&
                    call.desc == '(Ljava/util/TimerTask;JJ)V') {
                    replaceWithStatic(call, 'scheduleTimerPeriod',
                        '(Ljava/util/Timer;Ljava/util/TimerTask;JJ)V')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL && call.owner == 'java/util/Timer' &&
                    call.name == 'schedule' &&
                    call.desc == '(Ljava/util/TimerTask;Ljava/util/Date;J)V') {
                    replaceWithStatic(call, 'scheduleTimerDatePeriod',
                        '(Ljava/util/Timer;Ljava/util/TimerTask;Ljava/util/Date;J)V')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL && call.owner == 'java/util/Timer' &&
                    call.name == 'scheduleAtFixedRate' &&
                    call.desc == '(Ljava/util/TimerTask;JJ)V') {
                    replaceWithStatic(call, 'scheduleTimerAtFixedRate',
                        '(Ljava/util/Timer;Ljava/util/TimerTask;JJ)V')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL && call.owner == 'java/util/Timer' &&
                    call.name == 'scheduleAtFixedRate' &&
                    call.desc == '(Ljava/util/TimerTask;Ljava/util/Date;J)V') {
                    replaceWithStatic(call, 'scheduleTimerAtFixedRateDate',
                        '(Ljava/util/Timer;Ljava/util/TimerTask;Ljava/util/Date;J)V')
                    changed = true
                }
                if ((call.opcode == INVOKEINTERFACE || call.opcode == INVOKEVIRTUAL) &&
                    SCHEDULED_EXECUTOR_OWNERS.contains(call.owner) &&
                    call.name == 'schedule' &&
                    call.desc == '(Ljava/lang/Runnable;JLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/ScheduledFuture;') {
                    replaceWithStatic(call, 'scheduleExecutorRunnable',
                        '(Ljava/util/concurrent/ScheduledExecutorService;Ljava/lang/Runnable;JLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/ScheduledFuture;')
                    changed = true
                }
                if ((call.opcode == INVOKEINTERFACE || call.opcode == INVOKEVIRTUAL) &&
                    SCHEDULED_EXECUTOR_OWNERS.contains(call.owner) &&
                    call.name == 'schedule' &&
                    call.desc == '(Ljava/util/concurrent/Callable;JLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/ScheduledFuture;') {
                    replaceWithStatic(call, 'scheduleExecutorCallable',
                        '(Ljava/util/concurrent/ScheduledExecutorService;Ljava/util/concurrent/Callable;JLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/ScheduledFuture;')
                    changed = true
                }
                if ((call.opcode == INVOKEINTERFACE || call.opcode == INVOKEVIRTUAL) &&
                    SCHEDULED_EXECUTOR_OWNERS.contains(call.owner) &&
                    call.name == 'scheduleAtFixedRate' &&
                    call.desc == '(Ljava/lang/Runnable;JJLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/ScheduledFuture;') {
                    replaceWithStatic(call, 'scheduleExecutorAtFixedRate',
                        '(Ljava/util/concurrent/ScheduledExecutorService;Ljava/lang/Runnable;JJLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/ScheduledFuture;')
                    changed = true
                }
                if ((call.opcode == INVOKEINTERFACE || call.opcode == INVOKEVIRTUAL) &&
                    SCHEDULED_EXECUTOR_OWNERS.contains(call.owner) &&
                    call.name == 'scheduleWithFixedDelay' &&
                    call.desc == '(Ljava/lang/Runnable;JJLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/ScheduledFuture;') {
                    replaceWithStatic(call, 'scheduleExecutorWithFixedDelay',
                        '(Ljava/util/concurrent/ScheduledExecutorService;Ljava/lang/Runnable;JJLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/ScheduledFuture;')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL && call.owner == 'android/app/AlarmManager' &&
                    call.name == 'set' &&
                    call.desc == '(IJLandroid/app/PendingIntent;)V') {
                    replaceWithStatic(call, 'setAlarm',
                        '(Landroid/app/AlarmManager;IJLandroid/app/PendingIntent;)V')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL && call.owner == 'android/app/AlarmManager' &&
                    call.name == 'setExact' &&
                    call.desc == '(IJLandroid/app/PendingIntent;)V') {
                    replaceWithStatic(call, 'setExactAlarm',
                        '(Landroid/app/AlarmManager;IJLandroid/app/PendingIntent;)V')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL && call.owner == 'android/app/AlarmManager' &&
                    call.name == 'setAndAllowWhileIdle' &&
                    call.desc == '(IJLandroid/app/PendingIntent;)V') {
                    replaceWithStatic(call, 'setAndAllowWhileIdleAlarm',
                        '(Landroid/app/AlarmManager;IJLandroid/app/PendingIntent;)V')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL && call.owner == 'android/app/AlarmManager' &&
                    call.name == 'setExactAndAllowWhileIdle' &&
                    call.desc == '(IJLandroid/app/PendingIntent;)V') {
                    replaceWithStatic(call, 'setExactAndAllowWhileIdleAlarm',
                        '(Landroid/app/AlarmManager;IJLandroid/app/PendingIntent;)V')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL && call.owner == 'android/app/AlarmManager' &&
                    call.name == 'setRepeating' &&
                    call.desc == '(IJJLandroid/app/PendingIntent;)V') {
                    replaceWithStatic(call, 'setRepeatingAlarm',
                        '(Landroid/app/AlarmManager;IJJLandroid/app/PendingIntent;)V')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL && call.owner == 'android/app/AlarmManager' &&
                    call.name == 'setInexactRepeating' &&
                    call.desc == '(IJJLandroid/app/PendingIntent;)V') {
                    replaceWithStatic(call, 'setInexactRepeatingAlarm',
                        '(Landroid/app/AlarmManager;IJJLandroid/app/PendingIntent;)V')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL && call.owner == 'android/app/AlarmManager' &&
                    call.name == 'setWindow' &&
                    call.desc == '(IJJLandroid/app/PendingIntent;)V') {
                    replaceWithStatic(call, 'setWindowAlarm',
                        '(Landroid/app/AlarmManager;IJJLandroid/app/PendingIntent;)V')
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL && call.owner == 'android/app/job/JobScheduler' &&
                    call.name == 'schedule' &&
                    call.desc == '(Landroid/app/job/JobInfo;)I') {
                    replaceWithStatic(call, 'scheduleJob',
                        '(Landroid/app/job/JobScheduler;Landroid/app/job/JobInfo;)I')
                    changed = true
                }
                if (call.opcode == INVOKESTATIC && call.owner == 'd/b/e/n' &&
                    call.name == 'b' && call.desc == '(Ljava/lang/String;Ljava/lang/String;)V') {
                    InsnList before = new InsnList()
                    before.add(new InsnNode(DUP2))
                    before.add(new MethodInsnNode(INVOKESTATIC, BRIDGE, 'normalizeStoredValue',
                        '(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;', false))
                    before.add(new InsnNode(SWAP))
                    before.add(new InsnNode(POP))
                    method.instructions.insertBefore(call, before)
                    changed = true
                }
                if (call.opcode == INVOKEVIRTUAL && call.owner == 'com/spctv/utils/okhttp3/w\$a' &&
                    call.name == 'b' && call.desc == '(Ljava/lang/String;Ljava/lang/String;)Lcom/spctv/utils/okhttp3/w\$a;') {
                    InsnList before = new InsnList()
                    before.add(new InsnNode(DUP2))
                    before.add(new MethodInsnNode(INVOKESTATIC, BRIDGE, 'normalizeHeaderValue',
                        '(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;', false))
                    before.add(new InsnNode(SWAP))
                    before.add(new InsnNode(POP))
                    method.instructions.insertBefore(call, before)
                    changed = true
                }
                if (entryName == 'com/spctv/utils/okhttp3/b0/e/a.class' &&
                    call.opcode == INVOKEINTERFACE && call.owner == 'com/spctv/utils/okhttp3/s\$a' &&
                    call.name == 'a' && call.desc == '(Lcom/spctv/utils/okhttp3/w;)Lcom/spctv/utils/okhttp3/y;') {
                    replaceWithStatic(call, 'executeShadedRequest',
                        '(Lcom/spctv/utils/okhttp3/s\$a;Lcom/spctv/utils/okhttp3/w;)Lcom/spctv/utils/okhttp3/y;')
                    changed = true
                }
                instruction = nextInstruction
            }

            if (entryName == 'd/b/e/b.class' && method.name == 'a' &&
                method.desc == '(Landroid/content/Context;)Ljava/lang/String;') {
                method.instructions.toArray().findAll { it.opcode == ARETURN }.each { returnInsn ->
                    InsnList guard = new InsnList()
                    guard.add(new InsnNode(ACONST_NULL))
                    guard.add(new MethodInsnNode(INVOKESTATIC, BRIDGE, 'enforceResolvedUa',
                        '(Ljava/lang/String;Landroid/content/Context;)Ljava/lang/String;', false))
                    method.instructions.insertBefore(returnInsn, guard)
                }
                changed = true
            }

            if (entryName == 'd/b/d/a.class' && method.name == 'a' &&
                method.desc == '()V') {
                replaceMethodBodyWithBridgeCall(
                    method,
                    'blockSdkUpdateEntry',
                    '()V'
                )
                changed = true
            }

            if (entryName == 'd/b/d/a.class' && method.name == 'a' &&
                method.desc == '(Ljava/lang/String;Ljava/lang/String;ILcom/spctv/data/SDKUpdateInfo;)V') {
                replaceMethodBodyWithBridgeCall(
                    method,
                    'blockSdkUpdateDownload',
                    '(Ljava/lang/String;Ljava/lang/String;ILjava/lang/Object;)V'
                )
                changed = true
            }

            if (entryName == 'd/b/d/a.class' && method.name == 'a' &&
                method.desc == '(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V') {
                replaceMethodBodyWithBridgeCall(
                    method,
                    'blockSdkUpdateExecute',
                    '(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V'
                )
                changed = true
            }

            if (entryName == 'd/b/d/a.class' && method.name == 'b' &&
                method.desc == '()V') {
                replaceMethodBodyWithBridgeCall(
                    method,
                    'blockSdkUpdateStoredConfig',
                    '()V'
                )
                changed = true
            }

            if (entryName == 'd/b/c/a.class' && method.name == 'a' &&
                method.desc == '(Ljava/lang/String;Ljava/lang/String;)V') {
                replaceMethodBodyWithBridgeCall(
                    method,
                    'blockTitanDownload',
                    '(Ljava/lang/String;Ljava/lang/String;)V'
                )
                changed = true
            }

            if (entryName == 'd/b/c/a.class' && method.name == 'a' &&
                method.desc == '()V') {
                replaceMethodBodyWithBridgeCall(
                    method,
                    'blockTitanEntry',
                    '()V'
                )
                changed = true
            }

            if (entryName == 'd/b/c/a.class' && method.name == 'b' &&
                method.desc == '()V') {
                replaceMethodBodyWithBridgeCall(
                    method,
                    'blockTitanEntry',
                    '()V'
                )
                changed = true
            }

            if (entryName == 'titan/sdk/android/d.class' && method.name == 'a' &&
                method.desc == '(Landroid/content/Context;)V') {
                replaceMethodBodyWithBridgeCall(
                    method,
                    'blockTitanCoreStart',
                    '(Landroid/content/Context;)V'
                )
                changed = true
            }

            if (entryName == 'd/b/b/b.class' && method.name == 'a' &&
                method.desc == '(Landroid/content/Context;Lcom/spctv/data/LSAPAdRequest;)Ljava/lang/String;') {
                method.instructions.toArray().findAll { it.opcode == ARETURN }.each { returnInsn ->
                    method.instructions.insertBefore(returnInsn,
                        new MethodInsnNode(INVOKESTATIC, BRIDGE, 'rewriteRtbBody',
                            '(Ljava/lang/String;)Ljava/lang/String;', false))
                }
                changed = true
            }

            if (entryName == 'd/a/a/a.class' && method.name == 'run' && method.desc == '()V') {
                method.instructions.toArray().findAll { instruction ->
                    instruction instanceof MethodInsnNode &&
                        instruction.opcode == INVOKESTATIC &&
                        instruction.owner == 'd/a/a/c' &&
                        instruction.name == 'a' &&
                        instruction.desc == '(Ljava/lang/String;)Ljava/lang/String;'
                }.each { call ->
                    method.instructions.insertBefore(call,
                        new MethodInsnNode(INVOKESTATIC, BRIDGE, 'captureHeziEncryptionInput',
                            '(Ljava/lang/String;)Ljava/lang/String;', false))
                }
                changed = true
            }

            if ((entryName == 'd/b/e/b\$b.class' || entryName == 'd/b/d/a\$a\$a.class') &&
                method.name == 'onResponse' && method.desc == '(Ljava/lang/String;I)V') {
                InsnList audit = new InsnList()
                audit.add(new LdcInsnNode(entryName.replace('.class', '')))
                audit.add(new VarInsnNode(ALOAD, 1))
                audit.add(new VarInsnNode(ILOAD, 2))
                audit.add(new MethodInsnNode(INVOKESTATIC, BRIDGE, 'captureAarCallbackResponse',
                    '(Ljava/lang/String;Ljava/lang/String;I)V', false))
                method.instructions.insert(audit)
                changed = true
            }

            if ((entryName == 'd/b/e/l.class' || entryName == 'd/b/e/p.class' ||
                entryName == 'com/mofeng/ff/component/open/l.class' ||
                entryName == 'com/mofeng/ff/component/open/u.class') &&
                method.name == 'a' &&
                method.desc == '(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;') {
                replaceMethodBodyWithBridgeCall(
                    method,
                    'getAndroidSystemProperty',
                    '(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;'
                )
                changed = true
            }

            if (entryName == 'a/a/a/a/a/h/b.class' && method.name == 'b' &&
                method.desc == '(Ljava/lang/String;)Ljava/lang/String;') {
                replaceMethodBodyWithBridgeCall(
                    method,
                    'getAndroidSystemPropertyOrEmpty',
                    '(Ljava/lang/String;)Ljava/lang/String;'
                )
                changed = true
            }

            if (entryName == 'a/a/a/a/a/h/b.class' && method.name == 'c' &&
                method.desc == '()Ljava/lang/String;') {
                replaceMethodBodyWithBridgeCall(
                    method,
                    'getAndroidProduct',
                    '()Ljava/lang/String;'
                )
                changed = true
            }

            if (entryName == 'a/a/a/a/a/h/b.class' && method.name == 'd' &&
                method.desc == '()Ljava/lang/String;') {
                replaceMethodBodyWithBridgeCall(
                    method,
                    'getAndroidBuildId',
                    '()Ljava/lang/String;'
                )
                changed = true
            }
        }

        if (!changed) return bytes
        ClassWriter writer = new SafeClassWriter(
            reader,
            ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS
        )
        node.accept(writer)
        return writer.toByteArray()
    }

    static byte[] patchHq008Parameters(String entryName, byte[] bytes) {
        ClassNode node = new ClassNode()
        ClassReader reader = new ClassReader(bytes)
        reader.accept(node, ClassReader.EXPAND_FRAMES)
        boolean changed = false
        String bridge = 'com/smart/android/ad_app/Hq008XhsxAarRuntimeBridge'

        node.methods.each { MethodNode method ->
            if (patchHq008RenderSurfacePolicy(entryName, method, bridge)) {
                changed = true
            }
            if (patchHq008AudioFocusPolicy(entryName, method, bridge)) {
                changed = true
            }
            if (patchHq008ExoAudioTrackPolicy(entryName, method, bridge)) {
                changed = true
            }
            if (patchHq008VastViewSizeChecker(entryName, method)) {
                changed = true
            }
            if (patchHq008VastLifecycleGate(entryName, method)) {
                changed = true
            }
            if (patchHq008VideoOutputFrameRate(entryName, method, bridge)) {
                changed = true
            }
            for (AbstractInsnNode instruction = method.instructions.first;
                 instruction != null;) {
                AbstractInsnNode nextInstruction = instruction.next
                if (instruction instanceof FieldInsnNode) {
                    FieldInsnNode field = (FieldInsnNode) instruction
                    if (field.opcode == GETSTATIC &&
                        field.owner == 'android/os/Build$VERSION' &&
                        field.name == 'RELEASE' &&
                        field.desc == 'Ljava/lang/String;') {
                        method.instructions.set(
                            field,
                            new MethodInsnNode(INVOKESTATIC, bridge, 'getAndroidVersionRelease',
                                '()Ljava/lang/String;', false)
                        )
                        changed = true
                    } else if (field.opcode == GETSTATIC &&
                        field.owner == 'android/os/Build$VERSION' &&
                        field.name == 'SDK_INT' &&
                        field.desc == 'I') {
                        method.instructions.set(
                            field,
                            new MethodInsnNode(INVOKESTATIC, bridge, 'getAndroidSdkInt',
                                '()I', false)
                        )
                        changed = true
                    } else if (field.opcode == GETSTATIC &&
                        field.owner == 'android/os/Build' &&
                        ['MODEL', 'ID', 'BRAND', 'DEVICE', 'MANUFACTURER', 'PRODUCT'].contains(field.name) &&
                        field.desc == 'Ljava/lang/String;') {
                        String bridgeMethod = [
                            MODEL        : 'getAndroidDeviceModel',
                            ID           : 'getAndroidBuildId',
                            BRAND        : 'getAndroidBrand',
                            DEVICE       : 'getAndroidDevice',
                            MANUFACTURER : 'getAndroidManufacturer',
                            PRODUCT      : 'getAndroidProduct'
                        ][field.name]
                        method.instructions.set(
                            field,
                            new MethodInsnNode(INVOKESTATIC, bridge, bridgeMethod,
                                '()Ljava/lang/String;', false)
                        )
                        changed = true
                    }
                }

                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction
                    if (call.opcode == INVOKESTATIC &&
                        call.owner == 'java/lang/System' &&
                        call.name == 'getProperty' &&
                        call.desc == '(Ljava/lang/String;)Ljava/lang/String;') {
                        call.owner = bridge
                        call.name = 'getSystemProperty'
                        changed = true
                    }
                    if (call.opcode == INVOKEVIRTUAL &&
                        call.owner == 'android/webkit/WebSettings' &&
                        call.name == 'setUserAgentString' &&
                        call.desc == '(Ljava/lang/String;)V') {
                        replaceWithStatic(call, 'setWebViewUserAgent',
                            '(Landroid/webkit/WebSettings;Ljava/lang/String;)V', bridge)
                        changed = true
                    }
                    if (call.opcode == INVOKESTATIC &&
                        call.owner == 'android/webkit/WebSettings' &&
                        call.name == 'getDefaultUserAgent' &&
                        call.desc == '(Landroid/content/Context;)Ljava/lang/String;') {
                        call.owner = bridge
                        call.name = 'getDefaultWebViewUserAgent'
                        changed = true
                    }
                    if (call.opcode == INVOKEVIRTUAL &&
                        call.owner == 'android/webkit/WebSettings' &&
                        call.name == 'getUserAgentString' &&
                        call.desc == '()Ljava/lang/String;') {
                        replaceWithStatic(call, 'getWebViewUserAgent',
                            '(Landroid/webkit/WebSettings;)Ljava/lang/String;', bridge)
                        changed = true
                    }
                    if (call.opcode == INVOKEVIRTUAL &&
                        call.owner == 'android/webkit/WebView' &&
                        call.name == 'setWebViewClient' &&
                        call.desc == '(Landroid/webkit/WebViewClient;)V') {
                        replaceWithStatic(call, 'setAuditedWebViewClient',
                            '(Landroid/webkit/WebView;Landroid/webkit/WebViewClient;)V', bridge)
                        changed = true
                    }
                    if (call.opcode == INVOKEVIRTUAL &&
                        call.owner == 'android/webkit/WebView' &&
                        call.name == 'loadUrl' &&
                        call.desc == '(Ljava/lang/String;)V') {
                        replaceWithStatic(call, 'loadWebViewUrl',
                            '(Landroid/webkit/WebView;Ljava/lang/String;)V', bridge)
                        changed = true
                    }
                    if (call.opcode == INVOKEVIRTUAL &&
                        call.owner == 'android/webkit/WebView' &&
                        call.name == 'loadUrl' &&
                        call.desc == '(Ljava/lang/String;Ljava/util/Map;)V') {
                        replaceWithStatic(call, 'loadWebViewUrlWithHeaders',
                            '(Landroid/webkit/WebView;Ljava/lang/String;Ljava/util/Map;)V', bridge)
                        changed = true
                    }
                    if (call.opcode == INVOKEVIRTUAL &&
                        call.owner == 'android/webkit/WebView' &&
                        call.name == 'postUrl' &&
                        call.desc == '(Ljava/lang/String;[B)V') {
                        replaceWithStatic(call, 'postWebViewUrl',
                            '(Landroid/webkit/WebView;Ljava/lang/String;[B)V', bridge)
                        changed = true
                    }
                    if (call.opcode == INVOKEVIRTUAL &&
                        call.owner == 'java/net/URL' &&
                        call.name == 'openConnection' &&
                        call.desc == '()Ljava/net/URLConnection;') {
                        replaceWithStatic(call, 'openUrlConnection',
                            '(Ljava/net/URL;)Ljava/net/URLConnection;', bridge)
                        changed = true
                    }
                    if (call.opcode == INVOKEVIRTUAL &&
                        call.owner == 'java/net/URL' &&
                        call.name == 'openConnection' &&
                        call.desc == '(Ljava/net/Proxy;)Ljava/net/URLConnection;') {
                        replaceWithStatic(call, 'openUrlConnectionWithProxy',
                            '(Ljava/net/URL;Ljava/net/Proxy;)Ljava/net/URLConnection;', bridge)
                        changed = true
                    }
                    if (call.opcode == INVOKEVIRTUAL &&
                        ['java/net/URLConnection', 'java/net/HttpURLConnection',
                         'javax/net/ssl/HttpsURLConnection'].contains(call.owner) &&
                        call.name == 'setRequestProperty' &&
                        call.desc == '(Ljava/lang/String;Ljava/lang/String;)V') {
                        replaceWithStatic(call, 'setUrlConnectionRequestProperty',
                            '(Ljava/net/URLConnection;Ljava/lang/String;Ljava/lang/String;)V', bridge)
                        changed = true
                    }
                    if (call.opcode == INVOKEVIRTUAL &&
                        ['java/net/URLConnection', 'java/net/HttpURLConnection',
                         'javax/net/ssl/HttpsURLConnection'].contains(call.owner) &&
                        call.name == 'addRequestProperty' &&
                        call.desc == '(Ljava/lang/String;Ljava/lang/String;)V') {
                        replaceWithStatic(call, 'addUrlConnectionRequestProperty',
                            '(Ljava/net/URLConnection;Ljava/lang/String;Ljava/lang/String;)V', bridge)
                        changed = true
                    }
                    if (call.opcode == INVOKEVIRTUAL &&
                        ['java/net/HttpURLConnection', 'javax/net/ssl/HttpsURLConnection'].contains(call.owner) &&
                        call.name == 'setRequestMethod' &&
                        call.desc == '(Ljava/lang/String;)V') {
                        replaceWithStatic(call, 'setUrlConnectionRequestMethod',
                            '(Ljava/net/HttpURLConnection;Ljava/lang/String;)V', bridge)
                        changed = true
                    }
                    if (call.opcode == INVOKEVIRTUAL &&
                        call.owner == 'okhttp3/OkHttpClient' &&
                        call.name == 'newCall' &&
                        call.desc == '(Lokhttp3/Request;)Lokhttp3/Call;') {
                        replaceWithStatic(call, 'newOkHttpCall',
                            '(Lokhttp3/OkHttpClient;Lokhttp3/Request;)Lokhttp3/Call;', bridge)
                        changed = true
                    }
                }
                instruction = nextInstruction
            }
        }

        if (!changed) return bytes
        ClassWriter writer = new SafeClassWriter(
            reader,
            ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS
        )
        node.accept(writer)
        return writer.toByteArray()
    }

    private static boolean patchHq008RenderSurfacePolicy(
        String entryName,
        MethodNode method,
        String bridge
    ) {
        if (entryName == 'com/tcl/ff/component/uniplayer/f/k.class' &&
            method.name == 'setSurfaceType' && method.desc == '(IZ)V') {
            LabelNode keep = new LabelNode()
            InsnList normalization = new InsnList()
            normalization.add(new MethodInsnNode(
                INVOKESTATIC,
                bridge,
                'shouldApplySxkPlaybackPatch',
                '()Z',
                false
            ))
            normalization.add(new JumpInsnNode(IFEQ, keep))
            normalization.add(new VarInsnNode(ILOAD, 1))
            normalization.add(new JumpInsnNode(IFNE, keep))
            normalization.add(new InsnNode(ICONST_1))
            normalization.add(new VarInsnNode(ISTORE, 1))
            normalization.add(keep)
            method.instructions.insert(normalization)
            return true
        }
        return false
    }

    private static boolean patchHq008VideoOutputFrameRate(
        String entryName,
        MethodNode method,
        String bridge
    ) {
        String renderer = 'com/google/android/exoplayer2/video/MediaCodecVideoRenderer'
        String codecAdapter = 'com/google/android/exoplayer2/mediacodec/MediaCodecAdapter'
        String processDescriptor =
            "(JJL${codecAdapter};Ljava/nio/ByteBuffer;IIIJZZLcom/google/android/exoplayer2/Format;)Z"
        if (entryName != "${renderer}.class" ||
            method.name != 'processOutputBuffer' ||
            method.desc != processDescriptor) {
            return false
        }

        MethodInsnNode elapsedRealtimeCall = null
        for (AbstractInsnNode instruction = method.instructions.first;
             instruction != null;
             instruction = instruction.next) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction
                if (call.opcode == INVOKESTATIC &&
                    call.owner == 'android/os/SystemClock' &&
                    call.name == 'elapsedRealtime' &&
                    call.desc == '()J') {
                    elapsedRealtimeCall = call
                    break
                }
            }
        }
        if (elapsedRealtimeCall == null) {
            return false
        }

        AbstractInsnNode insertionPoint = previousCodeInstruction(elapsedRealtimeCall)
        insertionPoint = previousCodeInstruction(insertionPoint)
        insertionPoint = previousCodeInstruction(insertionPoint)
        if (!(insertionPoint instanceof VarInsnNode) ||
            insertionPoint.opcode != ALOAD ||
            ((VarInsnNode) insertionPoint).var != 0) {
            return false
        }

        LabelNode renderFrame = new LabelNode()
        InsnList throttle = new InsnList()
        throttle.add(new VarInsnNode(ILOAD, 6))
        throttle.add(new JumpInsnNode(IFEQ, renderFrame))
        throttle.add(new VarInsnNode(ILOAD, 13))
        throttle.add(new JumpInsnNode(IFNE, renderFrame))
        throttle.add(new VarInsnNode(ALOAD, 0))
        throttle.add(new FieldInsnNode(
            GETFIELD,
            renderer,
            'renderedFirstFrameAfterReset',
            'Z'
        ))
        throttle.add(new JumpInsnNode(IFEQ, renderFrame))
        throttle.add(new VarInsnNode(ALOAD, 0))
        throttle.add(new VarInsnNode(LLOAD, 8))
        throttle.add(
            new MethodInsnNode(
                INVOKESTATIC,
                bridge,
                'shouldRenderSxkVideoFrame',
                '(Ljava/lang/Object;J)Z',
                false
            )
        )
        throttle.add(new JumpInsnNode(IFNE, renderFrame))
        throttle.add(new VarInsnNode(ALOAD, 0))
        throttle.add(new VarInsnNode(ALOAD, 5))
        throttle.add(new VarInsnNode(ILOAD, 7))
        throttle.add(new VarInsnNode(LLOAD, 8))
        throttle.add(
            new MethodInsnNode(
                INVOKEVIRTUAL,
                renderer,
                'skipOutputBuffer',
                "(L${codecAdapter};IJ)V",
                false
            )
        )
        throttle.add(new InsnNode(ICONST_1))
        throttle.add(new InsnNode(IRETURN))
        throttle.add(renderFrame)
        method.instructions.insertBefore(insertionPoint, throttle)
        return true
    }

    private static boolean patchHq008ExoAudioTrackPolicy(
        String entryName,
        MethodNode method,
        String bridge
    ) {
        String delegate = 'com/tcl/tuniplayer_exo/a'
        String builder =
            'com/google/android/exoplayer2/trackselection/DefaultTrackSelector$Parameters$Builder'
        if (entryName != "${delegate}.class" ||
            method.name != 'a' ||
            method.desc !=
                '(Landroid/content/Context;)Lcom/google/android/exoplayer2/ExoPlayer;') {
            return false
        }

        MethodInsnNode buildUponParametersCall = null
        MethodInsnNode clearOverridesCall = null
        for (AbstractInsnNode instruction = method.instructions.first;
             instruction != null;
             instruction = instruction.next) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction
                if (call.opcode == INVOKEVIRTUAL &&
                    call.owner ==
                        'com/google/android/exoplayer2/trackselection/DefaultTrackSelector' &&
                    call.name == 'buildUponParameters' &&
                    call.desc == "()L${builder};") {
                    buildUponParametersCall = call
                }
                if (call.opcode == INVOKEVIRTUAL &&
                    call.owner == builder &&
                    call.name == 'clearOverrides' &&
                    call.desc == "()L${builder};") {
                    clearOverridesCall = call
                    break
                }
            }
        }
        if (buildUponParametersCall == null || clearOverridesCall == null) {
            return false
        }

        VarInsnNode builderStore = null
        for (AbstractInsnNode instruction = buildUponParametersCall.next;
             instruction != null && instruction != clearOverridesCall;
             instruction = instruction.next) {
            if (instruction instanceof VarInsnNode && instruction.opcode == ASTORE) {
                builderStore = (VarInsnNode) instruction
                break
            }
        }
        AbstractInsnNode clearOverridesPop = clearOverridesCall.next
        while (clearOverridesPop instanceof LabelNode ||
            clearOverridesPop instanceof LineNumberNode ||
            clearOverridesPop instanceof FrameNode) {
            clearOverridesPop = clearOverridesPop.next
        }
        if (builderStore == null ||
            clearOverridesPop?.opcode != POP) {
            return false
        }

        LabelNode keepAudioTrack = new LabelNode()
        InsnList disableAudio = new InsnList()
        disableAudio.add(new MethodInsnNode(
            INVOKESTATIC,
            bridge,
            'shouldApplySxkPlaybackPatch',
            '()Z',
            false
        ))
        disableAudio.add(new JumpInsnNode(IFEQ, keepAudioTrack))
        disableAudio.add(new VarInsnNode(ALOAD, builderStore.var))
        disableAudio.add(new InsnNode(ICONST_1))
        disableAudio.add(new InsnNode(ICONST_1))
        disableAudio.add(new MethodInsnNode(
            INVOKEVIRTUAL,
            builder,
            'setTrackTypeDisabled',
            "(IZ)L${builder};",
            false
        ))
        disableAudio.add(new InsnNode(POP))
        disableAudio.add(keepAudioTrack)
        method.instructions.insert(clearOverridesPop, disableAudio)
        return true
    }

    private static AbstractInsnNode previousCodeInstruction(AbstractInsnNode instruction) {
        AbstractInsnNode previous = instruction?.previous
        while (previous instanceof LabelNode ||
            previous instanceof LineNumberNode ||
            previous instanceof FrameNode) {
            previous = previous.previous
        }
        return previous
    }

    private static boolean patchHq008AudioFocusPolicy(
        String entryName,
        MethodNode method,
        String bridge
    ) {
        if (entryName == 'com/tcl/uniplayer/tuniplayer/b.class' &&
            method.name == 'w' && method.desc == '()Z') {
            LabelNode keepOriginalPolicy = new LabelNode()
            InsnList policy = new InsnList()
            policy.add(new MethodInsnNode(
                INVOKESTATIC,
                bridge,
                'shouldApplySxkPlaybackPatch',
                '()Z',
                false
            ))
            policy.add(new JumpInsnNode(IFEQ, keepOriginalPolicy))
            policy.add(new InsnNode(ICONST_0))
            policy.add(new InsnNode(IRETURN))
            policy.add(keepOriginalPolicy)
            method.instructions.insert(policy)
            return true
        }
        return false
    }

    private static boolean patchHq008VastViewSizeChecker(String entryName, MethodNode method) {
        if (entryName != 'com/tcl/ff/component/vastad/core/f/c.class') return false
        if (method.name == 'a' && method.desc == '(II)Z') {
            replaceMethodBodyWithConstantBoolean(method, true)
            return true
        }
        if (method.name == 'b' && method.desc == '(Landroid/view/View;)Z') {
            replaceMethodBodyWithConstantBoolean(method, true)
            return true
        }
        if (method.name == 'a' && method.desc == '(Landroid/view/View;)V') {
            method.instructions.clear()
            method.tryCatchBlocks?.clear()
            method.localVariables?.clear()

            LabelNode viewPresentForWidth = new LabelNode()
            LabelNode widthReady = new LabelNode()
            LabelNode viewPresentForHeight = new LabelNode()
            LabelNode heightReady = new LabelNode()
            InsnList replacement = new InsnList()
            replacement.add(new VarInsnNode(ALOAD, 0))
            replacement.add(new InsnNode(ICONST_1))
            replacement.add(new VarInsnNode(ALOAD, 1))
            replacement.add(new JumpInsnNode(IFNONNULL, viewPresentForWidth))
            replacement.add(new InsnNode(ICONST_0))
            replacement.add(new JumpInsnNode(GOTO, widthReady))
            replacement.add(viewPresentForWidth)
            replacement.add(new VarInsnNode(ALOAD, 1))
            replacement.add(new MethodInsnNode(INVOKEVIRTUAL, 'android/view/View', 'getWidth', '()I', false))
            replacement.add(widthReady)
            replacement.add(new VarInsnNode(ALOAD, 1))
            replacement.add(new JumpInsnNode(IFNONNULL, viewPresentForHeight))
            replacement.add(new InsnNode(ICONST_0))
            replacement.add(new JumpInsnNode(GOTO, heightReady))
            replacement.add(viewPresentForHeight)
            replacement.add(new VarInsnNode(ALOAD, 1))
            replacement.add(new MethodInsnNode(INVOKEVIRTUAL, 'android/view/View', 'getHeight', '()I', false))
            replacement.add(heightReady)
            replacement.add(new MethodInsnNode(
                INVOKEVIRTUAL,
                'com/tcl/ff/component/vastad/core/f/c',
                'a',
                '(ZII)V',
                false
            ))
            replacement.add(new InsnNode(RETURN))
            method.instructions.add(replacement)
            return true
        }
        return false
    }

    private static void replaceMethodBodyWithConstantBoolean(MethodNode method, boolean value) {
        method.instructions.clear()
        method.tryCatchBlocks?.clear()
        method.localVariables?.clear()

        InsnList replacement = new InsnList()
        replacement.add(new InsnNode(value ? ICONST_1 : ICONST_0))
        replacement.add(new InsnNode(IRETURN))
        method.instructions.add(replacement)
    }

    private static boolean patchHq008VastLifecycleGate(String entryName, MethodNode method) {
        if (entryName == 'com/tcl/ff/component/vastad/lifecycle/LifecycleOwnerAgent.class') {
            if (method.name == 'b' && method.desc == '(Ljava/lang/Object;)V') {
                replaceVoidMethodBody(method)
                return true
            }
            if (method.name == 'a' &&
                (method.desc == '()Lcom/tcl/ff/component/vastad/lifecycle/a;' ||
                 method.desc == '(Landroid/view/View;)Lcom/tcl/ff/component/vastad/lifecycle/a;' ||
                 method.desc == '(Landroidx/lifecycle/Lifecycle;)Lcom/tcl/ff/component/vastad/lifecycle/a;')) {
                replaceMethodBodyWithEnumConstant(
                    method,
                    'com/tcl/ff/component/vastad/lifecycle/a',
                    'RESUMED',
                    'Lcom/tcl/ff/component/vastad/lifecycle/a;'
                )
                return true
            }
        }
        if ((entryName == 'com/tcl/ff/component/vastad/core/d/e.class' ||
             entryName == 'com/tcl/ff/component/vastad/core/d/c.class') &&
            method.name == 'onViewDetachedFromWindow' &&
            method.desc == '(Landroid/view/View;)V') {
            replaceVoidMethodBody(method)
            return true
        }
        if (entryName == 'com/tcl/ff/component/vastad/core/d/a.class' &&
            method.name == 'onSessionInActive' &&
            method.desc == '()V') {
            replaceBooleanGateWithTrue(method, 'android/view/ViewGroup', 'isAttachedToWindow', '()Z')
            return true
        }
        if (entryName == 'com/tcl/ff/component/vastad/core/d/c.class' &&
            method.name == 'o' &&
            method.desc == '()V') {
            replaceBooleanGateWithTrue(method, 'android/view/ViewGroup', 'isAttachedToWindow', '()Z')
            return true
        }
        return false
    }

    private static void replaceVoidMethodBody(MethodNode method) {
        method.instructions.clear()
        method.tryCatchBlocks?.clear()
        method.localVariables?.clear()
        method.instructions.add(new InsnNode(RETURN))
    }

    private static void replaceMethodBodyWithEnumConstant(
        MethodNode method,
        String owner,
        String fieldName,
        String fieldDesc
    ) {
        method.instructions.clear()
        method.tryCatchBlocks?.clear()
        method.localVariables?.clear()

        InsnList replacement = new InsnList()
        replacement.add(new FieldInsnNode(GETSTATIC, owner, fieldName, fieldDesc))
        replacement.add(new InsnNode(ARETURN))
        method.instructions.add(replacement)
    }

    private static void replaceBooleanGateWithTrue(
        MethodNode method,
        String owner,
        String name,
        String desc
    ) {
        method.instructions?.toArray()?.findAll {
            it instanceof MethodInsnNode &&
            ((MethodInsnNode) it).owner == owner &&
            ((MethodInsnNode) it).name == name &&
            ((MethodInsnNode) it).desc == desc
        }?.each { MethodInsnNode call ->
            InsnList replacement = new InsnList()
            replacement.add(new InsnNode(POP))
            replacement.add(new InsnNode(ICONST_1))
            method.instructions.insert(call, replacement)
            method.instructions.remove(call)
        }
    }

    static Map<String, Integer> scanPatchableNetworkCalls(String entryName, byte[] bytes) {
        Map<String, Integer> result = [:].withDefault { 0 }
        ClassNode node = new ClassNode()
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
        node.methods.each { MethodNode method ->
            method.instructions?.toArray()?.findAll { it instanceof MethodInsnNode }?.each { instruction ->
                String category = patchCategory(entryName, (MethodInsnNode) instruction)
                if (category != null) result[category] = result[category] + 1
            }
        }
        return result
    }

    static List<String> findResidualNetworkCalls(String entryName, byte[] bytes) {
        List<String> result = []
        ClassNode node = new ClassNode()
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
        node.methods.each { MethodNode method ->
            method.instructions?.toArray()?.findAll { it instanceof MethodInsnNode }?.each { instruction ->
                MethodInsnNode call = (MethodInsnNode) instruction
                String category = patchCategory(entryName, call)
                if (category != null) {
                    result << "${entryName}:${method.name}${method.desc}:${category}:${call.owner}.${call.name}${call.desc}"
                }
            }
        }
        return result
    }

    static List<String> findResidualBuildIdentityReads(String entryName, byte[] bytes) {
        List<String> result = []
        ClassNode node = new ClassNode()
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
        node.methods.each { MethodNode method ->
            method.instructions?.toArray()?.findAll { it instanceof FieldInsnNode }?.each { instruction ->
                FieldInsnNode field = (FieldInsnNode) instruction
                if (field.opcode == GETSTATIC &&
                    field.owner == 'android/os/Build$VERSION' &&
                    field.name == 'RELEASE' &&
                    field.desc == 'Ljava/lang/String;') {
                    result << "${entryName}:${method.name}${method.desc}:androidVersionRelease:${field.owner}.${field.name}:${field.desc}"
                }
                if (field.opcode == GETSTATIC &&
                    field.owner == 'android/os/Build$VERSION' &&
                    field.name == 'SDK_INT' &&
                    field.desc == 'I' &&
                    entryName == 'd/a/a/a.class') {
                    result << "${entryName}:${method.name}${method.desc}:androidSdkInt:${field.owner}.${field.name}:${field.desc}"
                }
                if (field.opcode == GETSTATIC &&
                    field.owner == 'android/os/Build' &&
                    ['MODEL', 'ID', 'BRAND', 'DEVICE', 'MANUFACTURER', 'PRODUCT'].contains(field.name) &&
                    field.desc == 'Ljava/lang/String;') {
                    result << "${entryName}:${method.name}${method.desc}:androidBuildIdentity:${field.owner}.${field.name}:${field.desc}"
                }
            }
        }
        return result
    }

    private static String patchCategory(String entryName, MethodInsnNode call) {
        if (call.opcode == INVOKESTATIC && call.owner == 'java/lang/System' &&
            call.name == 'getProperty' && call.desc == '(Ljava/lang/String;)Ljava/lang/String;') return 'systemProperty'
        if (call.opcode == INVOKESTATIC && call.owner == 'java/lang/System' &&
            (call.name == 'load' || call.name == 'loadLibrary')) return 'nativeLoad'
        if (call.opcode == INVOKESTATIC && call.owner == 'titan/sdk/android/TitanSDK' &&
            call.name == 'nativeStart') return 'titanStart'
        if (call.opcode == INVOKESTATIC && call.owner == 'titan/sdk/android/TitanSDK' &&
            ['setNetwork', 'nativeStop'].contains(call.name)) return 'titanNativeControl'
        if (entryName == 'd/b/d/a.class' && call.opcode == INVOKEVIRTUAL &&
            call.owner == 'java/lang/reflect/Method' && call.name == 'invoke') return 'dynamicDexInvoke'
        if (call.opcode == INVOKEVIRTUAL && call.owner == 'android/webkit/WebSettings' &&
            call.name == 'setUserAgentString') return 'webViewUa'
        if (call.owner == 'android/webkit/WebSettings' &&
            ['getDefaultUserAgent', 'getUserAgentString'].contains(call.name)) return 'webViewUa'
        if (call.opcode == INVOKEVIRTUAL && call.owner == 'android/webkit/WebView' &&
            ['setWebViewClient', 'loadUrl', 'postUrl'].contains(call.name)) return 'webViewNetwork'
        if (call.opcode == INVOKEVIRTUAL && call.owner == 'java/net/URL' &&
            call.name == 'openConnection') return 'urlOpenConnection'
        if (call.opcode == INVOKEVIRTUAL && URL_CONNECTION_OWNERS.contains(call.owner) &&
            ['setRequestProperty', 'addRequestProperty', 'setRequestMethod', 'connect',
             'getOutputStream', 'getInputStream', 'getResponseCode', 'getErrorStream',
             'disconnect'].contains(call.name)) return 'urlConnectionLifecycle'
        if (call.opcode == INVOKEVIRTUAL && call.owner == 'okhttp3/OkHttpClient' &&
            call.name == 'newCall') return 'okhttp3Call'
        if (call.opcode == INVOKEVIRTUAL && call.owner == 'java/net/DatagramSocket' &&
            call.name == 'send') return 'udpSend'
        if (call.opcode == INVOKEVIRTUAL && call.owner == 'android/os/Handler' &&
            ['post', 'postDelayed'].contains(call.name)) return 'handlerSchedule'
        if (call.opcode == INVOKEVIRTUAL && call.owner == 'java/util/Timer' &&
            ['schedule', 'scheduleAtFixedRate'].contains(call.name)) return 'timerSchedule'
        if ((call.opcode == INVOKEINTERFACE || call.opcode == INVOKEVIRTUAL) &&
            SCHEDULED_EXECUTOR_OWNERS.contains(call.owner) &&
            ['schedule', 'scheduleAtFixedRate', 'scheduleWithFixedDelay'].contains(call.name)) {
            return 'scheduledExecutor'
        }
        if (call.opcode == INVOKEVIRTUAL && call.owner == 'android/app/AlarmManager' &&
            ['set', 'setExact', 'setAndAllowWhileIdle', 'setExactAndAllowWhileIdle',
             'setRepeating', 'setInexactRepeating', 'setWindow'].contains(call.name)) {
            return 'alarmSchedule'
        }
        if (call.opcode == INVOKEVIRTUAL && call.owner == 'android/app/job/JobScheduler' &&
            call.name == 'schedule') return 'jobSchedule'
        if (entryName == 'com/spctv/utils/okhttp3/b0/e/a.class' &&
            call.opcode == INVOKEINTERFACE && call.owner == 'com/spctv/utils/okhttp3/s\$a' &&
            call.name == 'a') return 'spctvOkHttpFinal'
        return null
    }

    private static void replaceWithStatic(
        MethodInsnNode call,
        String name,
        String desc,
        String bridge = BRIDGE
    ) {
        call.opcode = INVOKESTATIC
        call.owner = bridge
        call.name = name
        call.desc = desc
        call.itf = false
    }

    private static void replaceMethodBodyWithBridgeCall(
        MethodNode method,
        String bridgeMethodName,
        String bridgeMethodDesc
    ) {
        method.instructions.clear()
        method.tryCatchBlocks?.clear()
        method.localVariables?.clear()

        InsnList replacement = new InsnList()
        int localIndex = (method.access & ACC_STATIC) != 0 ? 0 : 1
        Type.getArgumentTypes(method.desc).each { Type argumentType ->
            replacement.add(new VarInsnNode(argumentType.getOpcode(ILOAD), localIndex))
            localIndex += argumentType.size
        }
        replacement.add(new MethodInsnNode(
            INVOKESTATIC,
            BRIDGE,
            bridgeMethodName,
            bridgeMethodDesc,
            false
        ))
        replacement.add(new InsnNode(Type.getReturnType(method.desc).getOpcode(IRETURN)))
        method.instructions.add(replacement)
    }

    private static final class SafeClassWriter extends ClassWriter {
        SafeClassWriter(ClassReader reader, int flags) {
            super(reader, flags)
        }

        @Override
        protected String getCommonSuperClass(String left, String right) {
            return left == right ? left : 'java/lang/Object'
        }
    }

}
