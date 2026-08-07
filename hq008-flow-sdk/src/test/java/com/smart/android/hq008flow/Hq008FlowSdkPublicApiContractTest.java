package com.smart.android.hq008flow;

import static org.junit.Assert.fail;

import org.junit.Test;

public class Hq008FlowSdkPublicApiContractTest {

    @Test
    public void flowSdkExposesSplitLifecycleEntryPoints() {
        assertPublicMethodNamed("initialize");
        assertPublicMethodNamed("start");
        assertPublicMethodNamed("stop");
        assertPublicMethodNamed("triggerNow");
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

    private void assertPublicMethodNamed(String name) {
        for (java.lang.reflect.Method method : Hq008FlowSdk.class.getMethods()) {
            if (name.equals(method.getName())) {
                return;
            }
        }
        fail("Hq008FlowSdk." + name + " must remain in the public SDK surface");
    }

}
