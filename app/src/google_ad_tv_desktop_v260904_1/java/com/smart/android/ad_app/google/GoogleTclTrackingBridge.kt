package com.smart.android.ad_app.google

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep
import com.google.gson.JsonParser
import com.smart.android.tclad.ResolvedAd
import com.tcl.ff.component.oversea.uniplayer.player.IPlayer
import com.tcl.ff.component.overseahttp.http.HttpRequester
import com.tcl.ff.component.overseabase.base.thread.EventReportExecutor
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Format TCL-selected tracking URLs; events, execution and HTTP stay in TCL. */
@Keep
object GoogleTclTrackingBridge {
    private val main = Handler(Looper.getMainLooper())
    private class Session(val controller: WeakReference<Any>, val hints: String?) {
        @Volatile var lastPosition = -1L
    }
    @Volatile private var current: Session? = null
    private val reporters = WeakHashMap<Any, Session>()
    private val engines = WeakHashMap<Any, Session>()

    @JvmStatic
    fun bindController(controller: Any, input: ResolvedAd) {
        val url = JsonParser.parseString(input.toJson()).asJsonObject
            .getAsJsonObject("data").get("adsUrl").asString
        val session = Session(WeakReference(controller), Uri.parse(url).getQueryParameter("uach"))
        val engine = controller.javaClass.getDeclaredField("a").apply { isAccessible = true }.get(controller)
        synchronized(engines) { engines[engine] = session }
        current = session
    }

    // Called immediately after TCL itself reads its real player's position,
    // before it decides whether to emit quartile/completion events.
    @JvmStatic
    fun recordPosition(positionMs: Long, engine: Any) {
        if (positionMs < 0L) return
        synchronized(engines) { engines[engine] }?.lastPosition = positionMs
    }

    @JvmStatic
    fun bindReporter(reporter: Any) {
        current?.let { session -> synchronized(reporters) { reporters[reporter] = session } }
    }

    @JvmStatic
    fun dispatchIfNeeded(reporter: Any, url: String?, requester: HttpRequester): Boolean {
        if (url.isNullOrBlank()) return false
        val decoded = Uri.decode(url)
        if (!decoded.contains("[AD_MT]") && !decoded.contains("[UACH]")) return false
        val parsed = Uri.parse(url)
        val host = parsed.host.orEmpty().lowercase()
        // These two Google macros are documented by the actual Core handlers.
        if (!(host == "doubleclick.net" || host.endsWith(".doubleclick.net")) ||
            parsed.path !in setOf("/pagead/interaction/", "/pcs/view")) return false
        val session = synchronized(reporters) { reporters[reporter] } ?: current
        val position = if (decoded.contains("[AD_MT]")) position(session) else -1L
        val hints = session?.hints?.takeIf { it.isNotBlank() } ?: "-1"
        var expanded = replace(url, "AD_MT", position.toString())
        expanded = replace(expanded, "UACH", hints)
        // Snapshot values before enqueuing so a later ad cannot change this event.
        EventReportExecutor.execute { requester.getJsonSync(expanded.trim()) }
        return true
    }

    private fun replace(url: String, macro: String, value: String): String = url
        .replace("[$macro]", Uri.encode(value))
        .replace("%5B$macro%5D", Uri.encode(value), ignoreCase = true)

    private fun position(session: Session?): Long {
        if (session == null) return -1L
        if (session.lastPosition >= 0L) return session.lastPosition
        val read = {
            try {
                val controller = session.controller.get()
                val native = controller?.javaClass?.getDeclaredField("a")?.apply { isAccessible = true }?.get(controller)
                val base = Class.forName("com.tcl.ff.component.vastad.core.d.a")
                val player = native?.let { base.getDeclaredField("f").apply { isAccessible = true }.get(it) } as? IPlayer
                player?.getCurrentPosition()?.takeIf { it >= 0L } ?: -1L
            } catch (_: ReflectiveOperationException) { -1L }
              catch (_: RuntimeException) { -1L }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) return read()
        val task = FutureTask<Long> { read() }
        main.post(task)
        return try { task.get(250, TimeUnit.MILLISECONDS) }
        catch (_: Exception) { task.cancel(false); -1L }
    }
}
