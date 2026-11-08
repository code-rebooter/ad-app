package com.smart.android.tclad.ima;

import static org.junit.Assert.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.Test;

/** Resolve every shipped runtime bridge call against the bridge actually compiled in this SDK. */
public class GoogleImaBridgeAbiTest {
    private static final String BRIDGE = "com/smart/android/tclad/ima/GoogleImaFirmwareBridge";
    @Test public void everyControlledAarLinksToTheStandaloneFirmwareBridge() throws Exception {
        Class<?> bridge = Class.forName(BRIDGE.replace('/', '.'), false, getClass().getClassLoader());
        Set<String> methods = new HashSet<>();
        for (Method method : bridge.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || !Modifier.isStatic(method.getModifiers())) continue;
            StringBuilder descriptor = new StringBuilder("(");
            for (Class<?> parameter : method.getParameterTypes()) descriptor.append(descriptor(parameter));
            descriptor.append(')').append(descriptor(method.getReturnType()));
            methods.add(method.getName() + descriptor);
        }
        int jars = 0, calls = 0;
        try (var files = Files.walk(Path.of("controlled-maven"))) {
            for (Path path : files.filter(p -> p.toString().endsWith(".aar")).toList()) {
                jars++;
                try (ZipFile aar = new ZipFile(path.toFile());
                     ZipInputStream jar = new ZipInputStream(aar.getInputStream(aar.getEntry("classes.jar")))) {
                    for (ZipEntry entry; (entry = jar.getNextEntry()) != null;) {
                        if (entry.getName().endsWith(".class")) calls += checkClass(jar.readAllBytes(), methods);
                    }
                }
            }
        }
        assertEquals(5, jars);
        assertTrue("Expected real controlled SDK bridge calls", calls > 20);
    }
    private int checkClass(byte[] bytes, Set<String> methods) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        assertEquals(0xcafebabe, in.readInt());
        in.skipNBytes(4);
        int count = in.readUnsignedShort();
        String[] text = new String[count];
        int[] tags = new int[count], first = new int[count], second = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = tags[i] = in.readUnsignedByte();
            switch (tag) {
                case 1:
                    text[i] = in.readUTF();
                    assertFalse("Leaked app bridge: " + text[i], text[i].contains("com/smart/android/ad_app/Hq008XhsxAarRuntimeBridge") ||
                        text[i].contains("com.smart.android.ad_app.Hq008XhsxAarRuntimeBridge"));
                    break;
                case 3: case 4: in.skipNBytes(4); break;
                case 5: case 6: in.skipNBytes(8); i++; break;
                case 7: case 8: case 16: case 19: case 20: first[i] = in.readUnsignedShort(); break;
                case 9: case 10: case 11: case 12: case 17: case 18:
                    first[i] = in.readUnsignedShort(); second[i] = in.readUnsignedShort(); break;
                case 15: in.skipNBytes(3); break;
                default: throw new IOException("Unexpected constant pool tag: " + tag);
            }
        }
        int calls = 0;
        for (int i = 1; i < count; i++) {
            if ((tags[i] == 10 || tags[i] == 11) && BRIDGE.equals(text[first[first[i]]])) {
                String signature = text[first[second[i]]] + text[second[second[i]]];
                assertTrue("Missing standalone bridge ABI: " + signature, methods.contains(signature));
                calls++;
            }
        }
        return calls;
    }
    private static String descriptor(Class<?> value) {
        if (value.isArray()) return value.getName().replace('.', '/');
        if (!value.isPrimitive()) return "L" + value.getName().replace('.', '/') + ";";
        if (value == void.class) return "V";
        if (value == boolean.class) return "Z";
        if (value == byte.class) return "B";
        if (value == char.class) return "C";
        if (value == short.class) return "S";
        if (value == int.class) return "I";
        if (value == long.class) return "J";
        if (value == float.class) return "F";
        return "D";
    }
}
