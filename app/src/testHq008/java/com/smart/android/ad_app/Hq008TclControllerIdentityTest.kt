package com.smart.android.ad_app

import com.tcl.ff.component.vastad.Controller
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class Hq008TclControllerIdentityTest {
    @Test fun `real TCL SDK wrappers resolve to the same retained native delegate`() {
        val constructor = Controller::class.java.constructors.single()
        val delegateType = constructor.parameterTypes.single()
        val nativePlayer = Proxy.newProxyInstance(delegateType.classLoader, arrayOf(delegateType)) { _, _, _ -> null }
        val first = constructor.newInstance(nativePlayer) as Controller
        val loaded = constructor.newInstance(nativePlayer) as Controller
        assertNotSame(first, loaded)
        assertSame(nativePlayer, Hq008TclControllerIdentity.of(first))
        assertSame(nativePlayer, Hq008TclControllerIdentity.of(loaded))
    }
}
