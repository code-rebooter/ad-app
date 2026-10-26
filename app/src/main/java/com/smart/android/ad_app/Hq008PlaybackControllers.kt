package com.smart.android.ad_app

import java.util.Collections
import java.util.IdentityHashMap

/** TCL creates different public Controller wrappers around a reusable native player. */
internal class Hq008PlaybackControllers<C : Any>(private val identityOf: (C) -> Any) {
    class Lease<C : Any> internal constructor(internal val release: (C) -> Unit) {
        internal var controller: C? = null
        internal val released = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    }

    private var active: Lease<C>? = null

    fun begin(release: (C) -> Unit): Lease<C> {
        active?.let(::close)
        return Lease(release).also { active = it }
    }

    fun attach(lease: Lease<C>, controller: C): Boolean {
        if (active !== lease) {
            releaseOnce(lease, controller)
            return false
        }
        lease.controller?.let { previous ->
            if (identityOf(previous) !== identityOf(controller)) releaseOnce(lease, previous)
        }
        lease.controller = controller
        return true
    }

    fun close(lease: Lease<C>) {
        if (active === lease) active = null
        lease.controller?.let { releaseOnce(lease, it) }
        lease.controller = null
    }

    private fun releaseOnce(lease: Lease<C>, controller: C) {
        val identity = identityOf(controller)
        // The vendor may already have reused the old native player for a new request.
        if (active !== lease && active?.controller?.let(identityOf) === identity) return
        if (lease.released.add(identity)) lease.release(controller)
    }
}
