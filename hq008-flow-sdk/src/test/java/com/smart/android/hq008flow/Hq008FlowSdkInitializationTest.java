package com.smart.android.hq008flow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Executes the shipped entry point against a deterministic Android main queue. */
public final class Hq008FlowSdkInitializationTest {
    @ClassRule public static final TemporaryFolder TEMP = new TemporaryFolder();
    private static Path classes;

    @BeforeClass public static void compileActualEntryPoint() throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "javac.exe" : "javac";
        Path compiler = Path.of(System.getProperty("java.home"), "bin", executable);
        assertTrue("These JVM tests require a JDK: " + compiler, Files.isExecutable(compiler));
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("hq008-flow-sdk/src/main"))) root = root.getParent();
        assertNotNull("Repository root", root);
        Path sources = TEMP.newFolder("sources").toPath();
        classes = TEMP.newFolder("classes").toPath();
        List<String> args = new ArrayList<>();
        args.add(compiler.toString());
        args.add("--release"); args.add("11");
        args.add("-d"); args.add(classes.toString());
        args.add("-classpath"); args.add(classes.toString());
        args.add(root.resolve("hq008-flow-sdk/src/main/java/com/smart/android/hq008flow/Hq008FlowSdk.java").toString());
        for (String name : new String[]{
                "android/content/Context.java", "android/os/Looper.java", "android/os/Handler.java",
                "com/smart/android/hq008flow/Hq008FlowConfig.java",
                "com/smart/android/hq008flow/Hq008AdCallback.java",
                "com/smart/android/hq008flow/FlowRuntime.java",
                "com/smart/android/hq008flow/InitializationScenario.java"}) {
            Path target = sources.resolve(name);
            Files.createDirectories(target.getParent());
            try (InputStream input = Hq008FlowSdkInitializationTest.class.getResourceAsStream("/initialization-stubs/" + name + ".txt")) {
                assertNotNull(name, input);
                Files.copy(input, target);
            }
            args.add(target.toString());
        }
        Path log = TEMP.newFile("javac.log").toPath();
        Process process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = process.waitFor(30L, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue("javac timed out", finished);
        assertEquals(new String(Files.readAllBytes(log), StandardCharsets.UTF_8), 0, process.exitValue());
    }

    @Test public void normalInitRegistersAndStartsItsOwnRuntime() throws Exception { run("normal"); }
    @Test public void mainThreadReentryKeepsNewerCallbackAndDoesNotStartIt() throws Exception { run("main"); }
    @Test public void initWithoutCallbackDoesNotStartReentrantInitialize() throws Exception { run("no_callback"); }
    @Test public void workerInitChecksOwnerAfterQueuedStopReenters() throws Exception { run("worker"); }
    @Test public void newerInitRemainsStartedWithItsOwnCallback() throws Exception { run("nested_init"); }
    @Test public void queuedActivationCannotStartAnAlreadyReplacedRuntime() throws Exception { run("superseded"); }

    private void run(String scenario) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, null)) {
            loader.loadClass("com.smart.android.hq008flow.InitializationScenario")
                    .getMethod("run", String.class).invoke(null, scenario);
        }
    }
}
