package com.smart.android.ad_app;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;

/** Prevents new source-level routes around the generated property-controlled sink. */
public class PropertyLoggingSourceContractTest {
    @Test
    public void productionSourcesDoNotCallUncontrolledLogcatOrProcessStreams() throws Exception {
        Pattern bypass = Pattern.compile(
                "android\\.util\\.Log|System\\.(?:out|err)\\b|\\.printStackTrace\\s*\\(\\s*\\)");
        assertNoMatches(bypass, productionSources());
    }

    @Test
    public void loggingWrappersDoNotUseBuildFlagsOrJavaPropertyFallbacks() throws Exception {
        List<Path> wrappers = new ArrayList<>();
        for (Path source : productionSources()) {
            String name = source.getFileName().toString();
            if (name.equals("AdLocalLog.kt") || name.equals("SdkLog.java")
                    || name.equals("TclCmpLocalLog.kt")) {
                wrappers.add(source);
            }
        }
        assertTrue("Expected all five logging wrappers", wrappers.size() == 5);
        assertNoMatches(Pattern.compile("BuildConfig\\.DEBUG|System\\.getProperty|debugLogging|bridge\\?"), wrappers);
    }

    @Test
    public void vendorLoggingAndWebConsoleDoNotBypassTheProperty() throws Exception {
        assertNoMatches(Pattern.compile(
                "(?:setEnableLog|isDebug|setDebugModeEnabled)\\s*\\(\\s*(?:true|BuildConfig\\.DEBUG)"
                        + "|super\\.onConsoleMessage\\s*\\("), productionSources());
    }

    @Test
    public void diagnosticInitializationDoesNotHardcodeLoggingFlags() throws Exception {
        assertNoMatches(Pattern.compile(
                "(?:isDebugMode|isPrintAutoRunInfo|isPrintNetRequestInfo)\\s*=\\s*(?:true|false|BuildConfig\\.DEBUG)"),
                productionSources());
    }

    private static void assertNoMatches(Pattern bypass, List<Path> sources) throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : sources) {
            List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                if (bypass.matcher(lines.get(index)).find()) {
                    violations.add(source + ":" + (index + 1) + ": " + lines.get(index).trim());
                }
            }
        }
        assertTrue("Uncontrolled logging routes:\n" + String.join("\n", violations), violations.isEmpty());
    }

    private static List<Path> productionSources() throws IOException {
        Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("app/src/main"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IOException("Cannot locate Android project root");
        }
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : (Iterable<Path>) modules::iterator) {
                String name = module.getFileName().toString();
                if (!(name.equals("app") || name.startsWith("ad-sdk") || name.startsWith("hq008-flow"))) {
                    continue;
                }
                Path sourceRoot = module.resolve("src");
                if (!Files.isDirectory(sourceRoot)) {
                    continue;
                }
                try (Stream<Path> paths = Files.walk(sourceRoot)) {
                    paths.filter(Files::isRegularFile)
                            .filter(path -> path.toString().endsWith(".java") || path.toString().endsWith(".kt"))
                            .filter(path -> !sourceRoot.relativize(path).getName(0).toString().toLowerCase().contains("test"))
                            .forEach(sources::add);
                }
            }
        }
        return sources;
    }
}
