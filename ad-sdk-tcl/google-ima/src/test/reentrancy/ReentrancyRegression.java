import android.os.Handler;
import android.view.ViewGroup;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import sun.misc.Unsafe;

/** Uses the SDK classes supplied on the classpath, not a second state-machine implementation. */
public final class ReentrancyRegression {
    private static final Unsafe UNSAFE;
    private static final Class<?> MANAGER, CONFIG, LISTENER, SESSION, INFO;
    private static int passed;
    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            UNSAFE = (Unsafe) field.get(null);
            MANAGER = Class.forName("com.smart.android.tclad.ima.GoogleImaAdManager");
            CONFIG = Class.forName("com.smart.android.tclad.ima.GoogleImaExecutionConfig");
            LISTENER = Class.forName("com.smart.android.tclad.ima.GoogleImaSessionListener");
            SESSION = Class.forName(MANAGER.getName() + "$Session");
            INFO = Class.forName("com.smart.android.googleima.GoogleImaAdInfo");
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }
    private static Object get(Object owner, Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void set(Object owner, Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
    private static void reset() throws Exception {
        set(null, MANAGER, "owner", null);
        set(null, MANAGER, "pendingOwner", null);
        set(null, MANAGER, "requestGeneration", 0L);
    }
    private static final class Fixture implements InvocationHandler {
        final String id;
        final Object manager;
        final Handler queue;
        final List<String> events = new ArrayList<>();
        String cancelOnReport, cancelOnTrace;
        boolean cancelOnDeadline, cancelOnPermission;
        Runnable whenCancelled, whenAudited;
        Fixture(String id) throws Exception {
            this.id = id;
            Object config = UNSAFE.allocateInstance(CONFIG);
            set(config, CONFIG, "requestId", id);
            set(config, CONFIG, "channelId", "bound-channel");
            set(config, CONFIG, "patchParameters", Collections.emptyMap());
            set(config, CONFIG, "callbackTimeoutMs", 180_000L);
            set(config, CONFIG, "auditEnabled", true);
            Object listener = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{LISTENER}, this);
            manager = MANAGER.getConstructor(CONFIG, LISTENER).newInstance(config, listener);
            queue = (Handler) get(manager, MANAGER, "main");
        }
        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Exception {
            switch (method.getName()) {
                case "isPlaybackAllowed":
                    if (cancelOnPermission) release();
                    return true;
                case "playbackDeniedReason": return "fixture";
                case "onAdReport":
                    events.add("report:" + args[1]);
                    if (args[1].equals(cancelOnReport)) release();
                    break;
                case "onTrace":
                    events.add("trace:" + args[1]);
                    if (args[1].equals(cancelOnTrace)) release();
                    break;
                case "onPlaybackDeadline": if (cancelOnDeadline) release(); break;
                case "onAdLoaded": events.add("callback:loaded"); break;
                case "onAdAudit": if (whenAudited != null) whenAudited.run(); break;
                case "onAdCancelled":
                    events.add("callback:cancelled");
                    if (whenCancelled != null) whenCancelled.run();
                    break;
                default: break;
            }
            return null;
        }
        void show() throws Exception { show(new ViewGroup()); }
        void show(ViewGroup container) throws Exception {
            MANAGER.getMethod("showAd", ViewGroup.class, com.google.gson.JsonElement.class, boolean.class)
                    .invoke(manager, container, null, false);
        }
        void release() throws Exception { MANAGER.getMethod("release").invoke(manager); }
        Object session(boolean finishing) throws Exception {
            Constructor<?> make = SESSION.getDeclaredConstructors()[0]; make.setAccessible(true);
            Function0<Unit> started = () -> { events.add("callback:started"); return Unit.INSTANCE; };
            Object session = make.newInstance(id, null, "bound-channel", false, true, 180_000L,
                    new WeakReference<>(new ViewGroup()), started, null,
                    (Function0<Unit>) () -> Unit.INSTANCE, get(manager, MANAGER, "host"));
            if (finishing) {
                Object gate = get(session, SESSION, "terminalGate");
                Method mark = gate.getClass().getDeclaredMethod("begin");
                mark.setAccessible(true); mark.invoke(gate);
                Object settlement = get(session, SESSION, "completionSettlement");
                Method begin = settlement.getClass().getDeclaredMethod("begin", String.class, long.class, long.class);
                begin.setAccessible(true); begin.invoke(settlement, "ALL_ADS_COMPLETED", 0L, 1_500L);
            }
            set(manager, MANAGER, "current", session); set(null, MANAGER, "owner", manager);
            return session;
        }
        void start(Object session) throws Exception {
            Constructor<?> make = INFO.getDeclaredConstructors()[0]; make.setAccessible(true);
            Object info = make.newInstance("one", "ad", "creative", "creative", "registry", "value",
                    20_000L, 20_000L, 1, 1, 0);
            Method start = MANAGER.getDeclaredMethod("handleAdStarted", SESSION, String.class, INFO);
            start.setAccessible(true); start.invoke(manager, session, "ima", info);
        }
        void loaded(Object session) throws Exception {
            Class<?> observer = Class.forName(MANAGER.getName() + "$startPlayback$1");
            Constructor<?> make = observer.getDeclaredConstructor(SESSION, MANAGER); make.setAccessible(true);
            observer.getMethod("onAdLoaded").invoke(make.newInstance(session, manager));
        }
        int count(String event) { return Collections.frequency(events, event); }
    }
    private static void pendingRelease() throws Exception {
        reset(); new Fixture("old").session(true);
        Fixture next = new Fixture("next"); next.show();
        Runnable stale = next.queue.pending.get(0);
        next.release(); next.release(); stale.run();
        check(next.queue.pending.isEmpty(), "release retained a pending retry");
        check(get(next.manager, MANAGER, "current") == null, "released request became current");
        check(next.count("callback:cancelled") == 1, "pending cancellation was not exactly once");
    }
    private static void latestOwner() throws Exception {
        reset(); Fixture old = new Fixture("old"); old.session(true);
        Fixture first = new Fixture("first"), latest = new Fixture("latest");
        first.show(); Runnable stale = first.queue.pending.get(0); latest.show();
        check(first.queue.pending.isEmpty(), "superseded request retained its retry");
        first.release(); old.release(); stale.run();
        check(latest.queue.pending.size() == 1, "old release cancelled the latest owner's retry");
        check(first.count("report:AD_REQUESTED") == 0, "superseded request emitted REQUESTED");
        set(null, MANAGER, "owner", null);
        latest.queue.pending.remove(0).run();
        check(latest.count("report:AD_REQUESTED") == 1, "latest pending request did not begin exactly once");
        first.release();
        check(get(latest.manager, MANAGER, "current") != null, "old manager released the latest session");
    }
    private static void staleRetryAfterReuse() throws Exception {
        reset(); new Fixture("old").session(true);
        Fixture next = new Fixture("next"); next.show(); Runnable stale = next.queue.pending.get(0);
        next.release(); next.show(); Runnable replacement = next.queue.pending.get(0);
        stale.run();
        check(next.queue.pending.size() == 1 && next.queue.pending.get(0) == replacement,
                "stale retry changed the replacement request");
    }
    private static void cancellationReentry() throws Exception {
        reset(); new Fixture("old").session(true);
        Fixture first = new Fixture("first"), outer = new Fixture("outer"), reentrant = new Fixture("reentrant");
        first.show(); first.whenCancelled = () -> {
            try { reentrant.show(); } catch (Exception error) { throw new RuntimeException(error); }
        };
        outer.show();
        check(outer.queue.pending.isEmpty(), "outer request overwrote a newer cancellation-callback request");
        check(reentrant.queue.pending.size() == 1, "reentrant request lost its queue ownership");
    }
    private static void cancelFromStartedReport() throws Exception {
        reset(); Fixture fixture = new Fixture("started"); Object session = fixture.session(false);
        fixture.cancelOnReport = "AD_STARTED"; fixture.start(session);
        check(fixture.count("callback:cancelled") == 1, "missing cancellation from AD_STARTED report");
        check(fixture.count("callback:started") == 0, "onAdStarted arrived after cancellation");
        check(fixture.queue.pending.isEmpty(), "cancelled STARTED left playback timers armed");
    }
    private static void cancelFromStartedTrace() throws Exception {
        reset(); Fixture fixture = new Fixture("started-trace"); Object session = fixture.session(false);
        fixture.cancelOnTrace = "AD_STARTED"; fixture.start(session);
        check(fixture.count("callback:cancelled") == 1 && fixture.count("callback:started") == 0,
                "onAdStarted arrived after trace callback cancellation");
    }
    private static void cancelFromFilled() throws Exception {
        reset(); Fixture fixture = new Fixture("filled"); Object session = fixture.session(false);
        fixture.cancelOnReport = "AD_FILLED"; fixture.loaded(session);
        check(fixture.count("callback:cancelled") == 1, "missing cancellation from FILLED");
        check(fixture.count("report:AD_LOADED") == 0 && fixture.count("callback:loaded") == 0,
                "LOADED arrived after FILLED callback cancellation");
    }
    private static void cancelFromLoadedReport() throws Exception {
        reset(); Fixture fixture = new Fixture("loaded"); Object session = fixture.session(false);
        fixture.cancelOnReport = "AD_LOADED"; fixture.loaded(session);
        check(fixture.count("callback:cancelled") == 1 && fixture.count("callback:loaded") == 0,
                "onAdLoaded arrived after cancellation in the LOADED report");
    }
    private static void cancelFromDeadline() throws Exception {
        reset(); Fixture fixture = new Fixture("deadline"); Object session = fixture.session(false);
        fixture.cancelOnDeadline = true; fixture.start(session);
        check(fixture.count("callback:cancelled") == 1 && fixture.count("report:AD_STARTED") == 0,
                "STARTED continued after playback-deadline callback cancellation");
        check(fixture.count("callback:started") == 0 && fixture.queue.pending.isEmpty(),
                "deadline cancellation left a callback or timer alive");
    }
    private static void terminalAuditReentry() throws Exception {
        reset(); Fixture old = new Fixture("old-audit"), next = new Fixture("next-audit");
        Object session = old.session(false);
        ViewGroup container = ((WeakReference<ViewGroup>) get(session, SESSION, "container")).get();
        old.whenAudited = () -> {
            try { next.show(container); } catch (Exception error) { throw new RuntimeException(error); }
        };
        Method fail = MANAGER.getDeclaredMethod("fail", SESSION, String.class, String.class,
                boolean.class, Boolean.class, Map.class);
        fail.setAccessible(true);
        fail.invoke(old.manager, session, "fixture_failure", null, false, null, Collections.emptyMap());
        check(next.queue.pending.size() == 1 && next.count("report:AD_REQUESTED") == 0,
                "new request began inside the previous audit/teardown callback");
        check(get(old.manager, MANAGER, "current") == null, "old terminal session was not closed");
        next.queue.pending.remove(0).run();
        check(next.count("report:AD_REQUESTED") == 1, "next request was lost after old teardown");
        old.release();
        check(get(next.manager, MANAGER, "current") != null, "old terminal owner released the new session");
    }
    private static void cancelFromPermission() throws Exception {
        reset(); Fixture fixture = new Fixture("permission"); Object session = fixture.session(false);
        fixture.cancelOnPermission = true; fixture.start(session);
        check(fixture.count("callback:cancelled") == 1 && fixture.count("report:AD_STARTED") == 0,
                "playback continued after permission callback cancellation");
        check(fixture.count("callback:started") == 0 && fixture.queue.pending.isEmpty(),
                "permission cancellation left a callback or timer alive");
    }
    private static void cancelFromLoadedStage() throws Exception {
        reset(); Fixture fixture = new Fixture("loaded-stage"); Object session = fixture.session(false);
        fixture.cancelOnTrace = "IMA_ACTIVE_STAGE"; fixture.loaded(session);
        check(fixture.count("callback:cancelled") == 1 && fixture.count("report:AD_LOADED") == 0,
                "LOADED continued after stage callback cancellation");
        check(fixture.count("callback:loaded") == 0, "onAdLoaded followed a stage cancellation");
    }
    private static void test(String name, ThrowingRunnable test) throws Exception {
        test.run(); passed++; System.out.println("PASS " + name);
    }
    private interface ThrowingRunnable { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        test("pending release", ReentrancyRegression::pendingRelease);
        test("latest pending owner and old release", ReentrancyRegression::latestOwner);
        test("stale retry after manager reuse", ReentrancyRegression::staleRetryAfterReuse);
        test("cancellation callback reentry", ReentrancyRegression::cancellationReentry);
        test("cancel from STARTED report", ReentrancyRegression::cancelFromStartedReport);
        test("cancel from STARTED trace", ReentrancyRegression::cancelFromStartedTrace);
        test("cancel from FILLED report", ReentrancyRegression::cancelFromFilled);
        test("cancel from LOADED report", ReentrancyRegression::cancelFromLoadedReport);
        test("cancel from playback deadline", ReentrancyRegression::cancelFromDeadline);
        test("terminal audit callback reentry", ReentrancyRegression::terminalAuditReentry);
        test("cancel from permission callback", ReentrancyRegression::cancelFromPermission);
        test("cancel from LOADED stage callback", ReentrancyRegression::cancelFromLoadedStage);
        System.out.println("Passed " + passed + " compiled SDK lifecycle regressions");
    }
}
