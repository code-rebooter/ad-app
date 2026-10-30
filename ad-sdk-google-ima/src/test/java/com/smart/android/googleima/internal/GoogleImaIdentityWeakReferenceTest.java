package com.smart.android.googleima.internal;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class GoogleImaIdentityWeakReferenceTest {
    @Test
    public void lookupUsesObjectIdentityWithoutCallingSdkHashCode() {
        Object request = new Object() {
            @Override
            public int hashCode() {
                throw new AssertionError("SDK hashCode must not be called");
            }
        };
        Map<GoogleImaRuntimeBridge.IdentityWeakReference, String> owners = new HashMap<>();
        owners.put(new GoogleImaRuntimeBridge.IdentityWeakReference(request, null), "session-a");

        assertEquals(
                "session-a",
                owners.get(new GoogleImaRuntimeBridge.IdentityWeakReference(request, null)));
        assertFalse(owners.containsKey(
                new GoogleImaRuntimeBridge.IdentityWeakReference(new Object(), null)));
    }
}
