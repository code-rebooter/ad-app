import android.content.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.smart.android.tclad.ExecutionConfig;
import com.smart.android.tclad.ima.GoogleImaExecutionConfig;
import com.smart.android.ad_app.Hq008ApiRequestFields;
import com.smart.android.ad_app.google.ima.local.ImaSharedAdvertisingIdentity;

/** Current-source four-entry matrix over real JDK FileLock; Android edges only are fixtures. */
public final class IdentityLockRegression {
    private static int assertions, failures;
    private static final List<String> matrix = new ArrayList<>(), failureCases = new ArrayList<>();
    private static void check(boolean condition, String message) {
        assertions++;
        System.out.println((condition ? "PASS " : "FAIL ") + message);
        if (!condition) failures++;
    }
    private static final class Prefs implements SharedPreferences {
        volatile String saved = "", durable = "", failedGenerated = "";
        final boolean pauseFirst;
        volatile boolean commitResult;
        final CountDownLatch firstRead = new CountDownLatch(1), releaseFirst = new CountDownLatch(1), secondRead = new CountDownLatch(1);
        final AtomicInteger reads = new AtomicInteger(), edits = new AtomicInteger(), commits = new AtomicInteger(), applies = new AtomicInteger();
        final ThreadLocal<Integer> localReads = ThreadLocal.withInitial(() -> 0);
        Prefs(boolean pauseFirst, boolean commitResult) { this.pauseFirst = pauseFirst; this.commitResult = commitResult; }
        public String getString(String key, String fallback) {
            reads.incrementAndGet();
            int read = localReads.get() + 1; localReads.set(read);
            String captured = saved;
            if (read == 1 && Thread.currentThread().getName().equals("first")) {
                firstRead.countDown();
                if (pauseFirst) try {
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) throw new AssertionError("Bounded first reader release timed out");
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
            }
            if (read == 1 && Thread.currentThread().getName().equals("second")) secondRead.countDown();
            return captured;
        }
        public Editor edit() {
            edits.incrementAndGet();
            return new Editor() {
                String value; boolean remove;
                public Editor putString(String key, String value) { this.value = value; this.remove = false; return this; }
                public Editor remove(String key) { this.remove = true; return this; }
                public void apply() { applies.incrementAndGet(); saved = remove ? "" : value; durable = saved; }
                public boolean commit() {
                    commits.incrementAndGet();
                    // Android commit updates the in-process memory map before disk persistence;
                    // false does NOT imply getString still returns the old value.
                    saved = remove ? "" : value;
                    if (commitResult) durable = saved;
                    else if (!remove && failedGenerated.isEmpty()) failedGenerated = value;
                    return commitResult;
                }
            };
        }
    }
    private static Context context(Prefs prefs, File directory) {
        return new Context() {
            public SharedPreferences getSharedPreferences(String name, int mode) {
                if (!name.equals("google_tcl_ad")) throw new AssertionError("Unexpected identity preferences: " + name);
                return prefs;
            }
            public File getFilesDir() { return directory; }
        };
    }
    private static String call(String entry, Context supplied) {
        return switch (entry) {
            case "tcl" -> (String) ExecutionConfig.builder(supplied, "tcl", "channel").build().getPatchParameters().get("rdid");
            case "ima" -> (String) GoogleImaExecutionConfig.builder(supplied, "ima", "channel").build().getPatchParameters().get("rdid");
            case "app" -> Hq008ApiRequestFields.INSTANCE.readOrCreateRdid(supplied);
            case "shared" -> ImaSharedAdvertisingIdentity.readOrCreateTclId(supplied);
            default -> throw new IllegalArgumentException(entry);
        };
    }
    private static void pair(String first, String second, File base) throws Exception {
        String label = first + "-" + second;
        File dir = new File(base, label); if (!dir.mkdirs()) throw new IOException("Could not create " + dir);
        if (!new File(dir, "alias").mkdir()) throw new IOException("Could not create alias");
        Prefs prefs = new Prefs(true, true);
        Context aContext = context(prefs, dir);
        Context bContext = context(prefs, new File(dir, "alias/.."));
        check(aContext.getFilesDir().getCanonicalPath().equals(bContext.getFilesDir().getCanonicalPath()), label + " contexts address same canonical file");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<String> a = workers.submit(() -> { Thread.currentThread().setName("first"); return call(first, aContext); });
            if (!prefs.firstRead.await(3, TimeUnit.SECONDS)) throw new AssertionError(label + " first reader did not reach prefs");
            boolean held = false;
            try (RandomAccessFile handle = new RandomAccessFile(new File(dir, "tcl_aid.lock"), "rw"); FileChannel channel = handle.getChannel()) {
                try (FileLock ignored = channel.tryLock()) { held = false; }
                catch (OverlappingFileLockException expected) { held = true; }
            }
            check(held, label + " first reader holds real FileLock");
            CountDownLatch secondStarted = new CountDownLatch(1);
            Future<String> b = workers.submit(() -> { Thread.currentThread().setName("second"); secondStarted.countDown(); return call(second, bContext); });
            if (!secondStarted.await(2, TimeUnit.SECONDS)) throw new AssertionError(label + " second worker did not start");
            boolean enteredEarly = prefs.secondRead.await(200, TimeUnit.MILLISECONDS);
            boolean completedEarly = b.isDone();
            check(!enteredEarly, label + " second reader waits outside prefs while first holds lock");
            check(!completedEarly, label + " second invocation cannot return or fail before release");
            prefs.releaseFirst.countDown(); // Always release after a bounded observation; no two-reader barrier.
            String x = a.get(5, TimeUnit.SECONDS), y = b.get(5, TimeUnit.SECONDS);
            check(x != null && !x.isBlank() && x.equals(y), label + " both entries return same nonblank identity");
            check(x.equals(prefs.saved), label + " returned identity matches persisted value");
            check(prefs.commits.get() == 1 && prefs.applies.get() == 0, label + " first identity committed exactly once");
            matrix.add("{\"first\":\"" + first + "\",\"second\":\"" + second + "\",\"realFileLockHeld\":" + held +
                ",\"secondReadBeforeRelease\":" + enteredEarly + ",\"secondCompletedBeforeRelease\":" + completedEarly +
                ",\"sameIdentity\":" + x.equals(y) + ",\"commits\":" + prefs.commits.get() + ",\"persisted\":\"" + prefs.saved + "\"}");
        } finally { prefs.releaseFirst.countDown(); workers.shutdownNow(); }
    }
    private static void deniedFile(String entry, File base) throws Exception {
        File notDirectory = new File(base, "unopenable-" + entry);
        Files.writeString(notDirectory.toPath(), "a regular file cannot contain tcl_aid.lock");
        Prefs prefs = new Prefs(false, true);
        String returned = null; Throwable thrown = null;
        try { returned = call(entry, context(prefs, notDirectory)); } catch (Throwable error) { thrown = error; }
        check(thrown instanceof IllegalStateException, entry + " unopenable lock fails as IllegalStateException");
        check(returned == null, entry + " unopenable lock never returns generated identity");
        check(prefs.edits.get() == 0 && prefs.commits.get() == 0 && prefs.applies.get() == 0, entry + " unopenable lock never writes preferences");
        check(prefs.reads.get() == 0, entry + " unopenable lock has no unlocked preference fallback");
        failureCases.add("{\"entry\":\"" + entry + "\",\"case\":\"unopenable_lock\",\"exception\":\"" + (thrown == null ? "NONE" : thrown.getClass().getName()) +
            "\",\"reads\":" + prefs.reads.get() + ",\"writes\":" + prefs.commits.get() + "}");
    }
    private static void failedCommit(String entry, File base) throws Exception {
        File dir = new File(base, "commit-failure-" + entry); if (!dir.mkdir()) throw new IOException("mkdir " + dir);
        Prefs prefs = new Prefs(false, false);
        String returned = null; Throwable thrown = null;
        try { returned = call(entry, context(prefs, dir)); } catch (Throwable error) { thrown = error; }
        check(thrown instanceof IllegalStateException, entry + " commit=false fails as IllegalStateException");
        check(returned == null && prefs.saved.isEmpty() && prefs.durable.isEmpty(), entry + " commit=false fails and clears Android-style dirty memory identity");
        check(prefs.reads.get() == 1 && prefs.commits.get() == 2 && prefs.applies.get() == 0, entry + " failed write is followed only by synchronous remove cleanup");
        String failedId = prefs.failedGenerated;
        check(failedId != null && !failedId.isBlank(), entry + " fixture actually exposed generated ID in memory before failed commit");
        int commitsBeforeRetry = prefs.commits.get();
        prefs.commitResult = true;
        String retry = call(entry, context(prefs, dir));
        check(retry != null && !retry.isBlank() && !retry.equals(failedId), entry + " retry cannot reuse failed generated dirty identity");
        check(retry.equals(prefs.saved) && retry.equals(prefs.durable), entry + " retry identity is successfully persisted");
        check(prefs.commits.get() == commitsBeforeRetry + 1 && prefs.applies.get() == 0, entry + " retry performs successful commit rather than reading stale memory");
        failureCases.add("{\"entry\":\"" + entry + "\",\"case\":\"commit_false\",\"exception\":\"" + (thrown == null ? "NONE" : thrown.getClass().getName()) +
            "\",\"reads\":" + prefs.reads.get() + ",\"commits\":" + prefs.commits.get() + "}");
    }
    private static void existingIdentity(String entry, File base) throws Exception {
        File dir = new File(base, "existing-" + entry); if (!dir.mkdir()) throw new IOException("mkdir " + dir);
        Prefs prefs = new Prefs(false, false); prefs.saved = "existing-persisted-id"; prefs.durable = prefs.saved;
        String returned = call(entry, context(prefs, dir));
        check(returned.equals(prefs.saved), entry + " existing identity preserved");
        check(prefs.edits.get() == 0 && prefs.commits.get() == 0 && prefs.applies.get() == 0, entry + " existing identity does not need a write");
    }
    public static void main(String[] args) throws Exception {
        File base = new File(args[0]); if (!base.mkdirs()) throw new IOException("mkdir " + base);
        String[] entries = {"tcl", "ima", "app", "shared"};
        for (int i = 0; i < entries.length; i++) for (int j = i; j < entries.length; j++) pair(entries[i], entries[j], base);
        for (String entry : entries) { deniedFile(entry, base); failedCommit(entry, base); existingIdentity(entry, base); }
        Path output = base.getParentFile().toPath();
        Files.writeString(output.resolve("matrix.json"), "[\n" + String.join(",\n", matrix) + "\n]\n");
        Files.writeString(output.resolve("failure-cases.json"), "[\n" + String.join(",\n", failureCases) + "\n]\n");
        Files.writeString(output.resolve("summary.json"), "{\"combinations\":" + matrix.size() + ",\"assertions\":" + assertions + ",\"failures\":" + failures + "}\n");
        System.out.println("TOTAL_ASSERTIONS=" + assertions + ", TOTAL_FAILURES=" + failures);
        if (failures > 0) System.exit(1);
    }
}
