package com.smart.android.adsdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.ViewGroup;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.Test;

public class AdSdkTest {

    @Test
    public void facadeExposesJavaStaticInitializationMethod() throws Exception {
        Method method = AdSdk.class.getDeclaredMethod(
            "initialize",
            Context.class,
            SdkConfig.class,
            InitializationListener.class
        );

        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertTrue(Modifier.isStatic(method.getModifiers()));
        assertEquals(Void.TYPE, method.getReturnType());
    }

    @Test
    public void facadeExposesJavaStaticInitializationMethodWithoutConfig() throws Exception {
        Method method = AdSdk.class.getDeclaredMethod(
            "initialize",
            Context.class,
            InitializationListener.class
        );

        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertTrue(Modifier.isStatic(method.getModifiers()));
        assertEquals(Void.TYPE, method.getReturnType());
    }

    @Test
    public void facadeExposesJavaStaticPlayMethod() throws Exception {
        Method method = AdSdk.class.getDeclaredMethod(
            "play",
            ViewGroup.class,
            AdRequest.class,
            AdListener.class
        );

        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertTrue(Modifier.isStatic(method.getModifiers()));
        assertEquals(AdSession.class, method.getReturnType());
    }

    @Test
    public void initializeRejectsNullArgumentsBeforeCreatingRuntime() {
        SdkConfig config = new SdkConfig.Builder().build();
        InitializationListener listener = new NoOpInitializationListener();

        assertEquals(
            "listener must not be null",
            assertThrows(
                NullPointerException.class,
                () -> AdSdk.initialize(null, config, null)
            ).getMessage()
        );
        assertEquals(
            "config must not be null",
            assertThrows(
                NullPointerException.class,
                () -> AdSdk.initialize(null, null, listener)
            ).getMessage()
        );
        assertEquals(
            "context must not be null",
            assertThrows(
                NullPointerException.class,
                () -> AdSdk.initialize(null, config, listener)
            ).getMessage()
        );
    }

    @Test
    public void playRejectsNullArgumentsBeforeCreatingRuntime() {
        AdRequest request = new AdRequest.Builder().build();
        AdListener listener = new NoOpAdListener();

        assertEquals(
            "listener must not be null",
            assertThrows(
                NullPointerException.class,
                () -> AdSdk.play(null, request, null)
            ).getMessage()
        );
        assertEquals(
            "request must not be null",
            assertThrows(
                NullPointerException.class,
                () -> AdSdk.play(null, null, listener)
            ).getMessage()
        );
        assertEquals(
            "container must not be null",
            assertThrows(
                NullPointerException.class,
                () -> AdSdk.play(null, request, listener)
            ).getMessage()
        );
    }

    private static final class NoOpInitializationListener implements InitializationListener {
        @Override
        public void onInitialized() {
        }

        @Override
        public void onError(AdError error) {
        }
    }

    private static final class NoOpAdListener implements AdListener {
        @Override
        public void onLoaded(AdSession session) {
        }

        @Override
        public void onStarted(AdSession session) {
        }

        @Override
        public void onFinished(AdSession session, AdResult result) {
        }
    }
}
