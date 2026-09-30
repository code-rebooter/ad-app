import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import org.objectweb.asm.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Run through verify_property_logging.py using Android SDK command-line tool libraries. */
public final class PropertyLogArtifactAudit {
    private static int classes, calls, gates;
    private static final List<String> violations = new ArrayList<>();
    private static boolean guardedSink(String owner, String name, boolean noArguments) {
        return (name.equals("printStackTrace") && noArguments)
                || ((owner.equals("Ljava/util/logging/Logger;") || owner.equals("java/util/logging/Logger"))
                && Arrays.asList("log", "logp", "logrb", "severe", "warning", "info", "config",
                "fine", "finer", "finest", "entering", "exiting", "throwing").contains(name));
    }
    private static boolean bridge(String owner) {
        return owner.contains("/logging/PropertyLog;") || owner.endsWith("/logging/PropertyLog")
                || owner.contains("/logging/PropertyLog$");
    }
    private static boolean logSink(String owner, String name) {
        return (owner.equals("Landroid/util/Log;") || owner.equals("android/util/Log"))
                && Arrays.asList("v", "d", "i", "w", "e", "wtf", "println").contains(name);
    }
    private static void call(String caller, String owner, String name) {
        if (bridge(owner)) gates++;
        if (logSink(owner, name)) {
            calls++;
            if (!bridge(caller)) violations.add(caller + " -> " + owner + "." + name);
        }
    }
    private static void field(String caller, String owner, String name) {
        if ((owner.equals("Ljava/lang/System;") || owner.equals("java/lang/System"))
                && (name.equals("out") || name.equals("err")) && !bridge(caller)) {
            violations.add(caller + " -> System." + name);
        }
    }
    private static void jar(InputStream input) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(input)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (!entry.getName().endsWith(".class")) continue;
                classes++;
                new ClassReader(zip.readAllBytes()).accept(new ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    String caller;
                    @Override public void visit(int v, int a, String n, String s, String p, String[] i) { caller = n; }
                    @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                        return new MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                            boolean hasGate, needsGate;
                            @Override public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
                                call(caller, owner, name);
                                hasGate |= bridge(owner) && name.equals("isEnabled");
                                needsGate |= guardedSink(owner, name, desc.equals("()V"));
                            }
                            @Override public void visitFieldInsn(int op, String owner, String name, String desc) {
                                if (op == org.objectweb.asm.Opcodes.GETSTATIC) field(caller, owner, name);
                            }
                            @Override public void visitEnd() {
                                if (needsGate && !hasGate && !bridge(caller)) violations.add(caller + "." + n + " unguarded JUL/stack trace");
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
    }
    public static void main(String[] args) throws Exception {
        for (String path : args) {
            classes = calls = gates = 0; violations.clear();
            if (path.endsWith(".apk")) {
                MultiDexContainer<? extends DexFile> dex = DexFileFactory.loadDexContainer(new File(path), Opcodes.getDefault());
                for (String entry : dex.getDexEntryNames()) {
                    for (ClassDef cls : dex.getEntry(entry).getDexFile().getClasses()) {
                        classes++;
                        for (Method method : cls.getMethods()) {
                            if (method.getImplementation() == null) continue;
                            boolean hasGate = false, needsGate = false;
                            for (Instruction insn : method.getImplementation().getInstructions()) {
                                if (!(insn instanceof ReferenceInstruction)) continue;
                                Reference ref = ((ReferenceInstruction) insn).getReference();
                                if (ref instanceof MethodReference) {
                                    MethodReference m = (MethodReference) ref;
                                    call(cls.getType(), m.getDefiningClass(), m.getName());
                                    hasGate |= bridge(m.getDefiningClass()) && m.getName().equals("isEnabled");
                                    needsGate |= guardedSink(m.getDefiningClass(), m.getName(), m.getParameterTypes().isEmpty());
                                } else if (ref instanceof FieldReference && insn.getOpcode().name().startsWith("SGET")) {
                                    FieldReference f = (FieldReference) ref;
                                    field(cls.getType(), f.getDefiningClass(), f.getName());
                                }
                            }
                            if (needsGate && !hasGate && !bridge(cls.getType())) violations.add(cls.getType() + "." + method.getName() + " unguarded JUL/stack trace");
                        }
                    }
                }
            } else {
                try (ZipFile aar = new ZipFile(path)) {
                    Enumeration<? extends ZipEntry> entries = aar.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        if (entry.getName().endsWith(".jar")) jar(aar.getInputStream(entry));
                    }
                }
            }
            System.out.println(path + ": classes=" + classes + ", Android Log calls=" + calls
                    + ", property bridge references=" + gates + ", bypasses=" + violations.size());
            if (classes == 0 || gates == 0) throw new AssertionError("No instrumented code found");
            if (!violations.isEmpty()) {
                violations.stream().limit(40).forEach(System.out::println);
                throw new AssertionError("Uncontrolled diagnostic sinks");
            }
        }
    }
}
