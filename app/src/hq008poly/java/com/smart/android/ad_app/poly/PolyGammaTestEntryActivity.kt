package com.smart.android.ad_app.poly

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.widget.TextView
import com.smart.android.ad_app.BuildConfig
import com.smart.android.ad_app.R
import java.io.File

class PolyGammaTestEntryActivity : Activity() {

    private lateinit var summaryView: TextView
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_poly_gamma_test_entry)

        summaryView = findViewById(R.id.summary_text)
        logView = findViewById(R.id.log_text)

        findViewById<TextView>(R.id.reinit_button).setOnClickListener {
            runInitializationCheck()
        }

        renderSummary()
        runInitializationCheck()
    }

    private fun renderSummary() {
        summaryView.text = buildString {
            append("flavor=")
            append(BuildConfig.FLAVOR)
            append('\n')
            append("package=")
            append(packageName)
            append('\n')
            append("process=")
            append(currentProcessName())
            append('\n')
            append("isMainProcess=")
            append(currentProcessName() == packageName)
            append('\n')
            append("channel=")
            append(BuildConfig.CHANNEL)
            append('\n')
            append("model=")
            append(BuildConfig.MODEL)
        }
    }

    private fun runInitializationCheck() {
        val result = runCatching {
            PolyGammaOriginInitializer.initialize(application as Application)
            "Poly Origin initialize invoked"
        }.getOrElse { error ->
            "Poly Origin init failed: ${error.javaClass.simpleName}: ${error.message}"
        }

        logView.text = buildString {
            append(result)
            append('\n')
            append("manufacturer=")
            append(Build.MANUFACTURER.orEmpty())
            append('\n')
            append("androidIdAvailable=")
            append(!android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID).isNullOrBlank())
        }
    }

    private fun currentProcessName(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName()
        }
        return runCatching {
            File("/proc/${Process.myPid()}/cmdline").readText().trim { it <= ' ' }
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() }
                ?.processName
    }
}
