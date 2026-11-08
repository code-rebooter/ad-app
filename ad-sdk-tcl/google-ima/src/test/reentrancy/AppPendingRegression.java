import android.os.Handler;
import android.view.ViewGroup;
import java.lang.reflect.*;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import sun.misc.Unsafe;

/** Exercises the app's compiled singleton without initializing business transports. */
public final class AppPendingRegression {
    private static Unsafe unsafe;
    private static Class<?> manager, sessionType;
    private static Object instance;
    private static Handler queue;
    private static Object get(Object owner, Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static void set(Object owner, Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
    private static Object reset(boolean completion) throws Exception {
        queue.pending.clear();
        set(null, manager, "pendingShow", null); set(null, manager, "requestGeneration", 0L);
        Object session = unsafe.allocateInstance(sessionType);
        set(session, sessionType, "id", "old");
        Class<?> gateType = Class.forName("com.smart.android.ad_app.GoogleImaTerminalGate");
        Constructor<?> gateConstructor = gateType.getDeclaredConstructor(); gateConstructor.setAccessible(true);
        Object gate = gateConstructor.newInstance();
        Method begin = gateType.getDeclaredMethod("begin"); begin.setAccessible(true); begin.invoke(gate);
        set(session, sessionType, "terminalGate", gate);
        Class<?> settleType = Class.forName("com.smart.android.ad_app.GoogleImaCompletionSettlement");
        Constructor<?> settleConstructor = settleType.getDeclaredConstructor(); settleConstructor.setAccessible(true);
        Object settlement = settleConstructor.newInstance();
        if (completion) {
            Method mark = settleType.getDeclaredMethod("begin", String.class, long.class, long.class);
            mark.setAccessible(true); mark.invoke(settlement, "ALL_ADS_COMPLETED", 0L, 1500L);
        }
        set(session, sessionType, "completionSettlement", settlement);
        set(null, manager, "current", session);
        return session;
    }
    private static void show(String id) throws Exception {
        Method show = manager.getMethod("showAd", ViewGroup.class, String.class, boolean.class, Long.class,
                com.google.gson.JsonElement.class, String.class, Function0.class, Function0.class, Function0.class);
        show.invoke(instance, new ViewGroup(), id, false, Long.valueOf(180000L), null, "bound-channel",
                null, null, (Function0<Unit>) () -> Unit.INSTANCE);
    }
    private static void cancel(String id) throws Exception {
        manager.getMethod("destroyAd", String.class, String.class).invoke(instance, id, "fixture_cancel");
    }
    public static void main(String[] args) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
        unsafe = (Unsafe) field.get(null);
        manager = Class.forName("com.smart.android.ad_app.GoogleImaAdManager");
        sessionType = Class.forName(manager.getName() + "$Session");
        instance = manager.getField("INSTANCE").get(null); queue = (Handler) get(null, manager, "main");
        Object old = reset(true); show("next"); Runnable stale = queue.pending.get(0); cancel("next"); stale.run();
        check(queue.pending.isEmpty() && get(null, manager, "current") == old,
                "targeted pending cancellation changed the old session or retained its retry");
        System.out.println("PASS app targeted pending cancellation");
        reset(true); show("next"); cancel("old");
        check(queue.pending.size() == 1, "old request release cancelled the new pending request");
        System.out.println("PASS app old request release preserves new pending request");
        reset(true); show("first"); stale = queue.pending.get(0); show("latest");
        Runnable latest = queue.pending.get(0); stale.run(); cancel("first");
        check(queue.pending.size() == 1 && queue.pending.get(0) == latest,
                "superseded retry or old request release changed the latest pending request");
        cancel("latest");
        check(queue.pending.isEmpty(), "latest pending request could not be cancelled");
        System.out.println("PASS app latest pending request ownership");
        reset(true); show("next"); cancel(null);
        check(queue.pending.isEmpty(), "window cancellation retained a pending app request");
        System.out.println("PASS app window cancellation removes pending request");
        reset(false); show("next");
        check(queue.pending.size() == 1, "app began the new request before old failure teardown finished");
        cancel("next");
        check(queue.pending.isEmpty(), "pending request behind failure teardown could not be cancelled");
        System.out.println("PASS app failure teardown defers new requests");
        System.out.println("Passed 5 compiled app pending-request regressions");
    }
}
