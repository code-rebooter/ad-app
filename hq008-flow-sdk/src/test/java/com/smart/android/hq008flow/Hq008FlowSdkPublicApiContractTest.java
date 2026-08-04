package com.smart.android.hq008flow;

import static org.junit.Assert.fail;

import org.junit.Test;

public class Hq008FlowSdkPublicApiContractTest {

    @Test
    public void flowSdkDoesNotExposeLegacySplitLifecycleEntryPoints() {
        assertNoPublicMethodNamed("initialize");
        assertNoPublicMethodNamed("start");
        assertNoPublicMethodNamed("stop");
        assertNoPublicMethodNamed("triggerNow");
        assertNoPublicMethodNamed("attachAdHost");
        assertNoPublicMethodNamed("detachAdHost");
    }

    @Test
    public void flowSdkDoesNotExposeLegacyAdHostInterface() {
        try {
            Class.forName("com.smart.android.hq008flow.Hq008AdHost");
            fail("Hq008AdHost must not remain in the public SDK surface");
        } catch (ClassNotFoundException expected) {
            // Expected: new integrations only use Hq008AdCallback.
        }
    }

    private void assertNoPublicMethodNamed(String name) {
        for (java.lang.reflect.Method method : Hq008FlowSdk.class.getMethods()) {
            if (name.equals(method.getName())) {
                fail("Hq008FlowSdk." + name + " must not remain in the public SDK surface");
            }
        }
    }
}
