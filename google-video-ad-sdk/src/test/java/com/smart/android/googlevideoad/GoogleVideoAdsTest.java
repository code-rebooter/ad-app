package com.smart.android.googlevideoad;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.ViewGroup;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.Test;

public class GoogleVideoAdsTest {

    @Test
    public void facadeExposesJavaStaticInitializationMethod() throws Exception {
        Method method = GoogleVideoAds.class.getDeclaredMethod(
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
    public void facadeExposesJavaStaticPlayMethod() throws Exception {
        Method method = GoogleVideoAds.class.getDeclaredMethod(
            "play",
            ViewGroup.class,
            AdRequest.class,
            AdListener.class
        );

        assertTrue(Modifier.isPublic(method.getModifiers()));
        assertTrue(Modifier.isStatic(method.getModifiers()));
        assertEquals(AdSession.class, method.getReturnType());
    }
}
