package dev.khoirevanced.runtime.agent

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * First visible integration checkpoint.
 *
 * ART hooking is not enabled yet, so this deliberately uses the public
 * ActivityLifecycleCallbacks route rather than pretending to modify YouTube's
 * settings adapter. It proves the runtime can execute UI code inside the host
 * process and offers a stable diagnostics control until the real settings-row
 * hook replaces it.
 */
object SettingsProbeOverlay {
    private const val TAG = "khoirevanced_settings_probe"

    fun install(application: Application, config: RuntimeConfig) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = installIfMissing(activity, config)

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun installIfMissing(activity: Activity, config: RuntimeConfig) {
        val root = activity.window?.decorView as? ViewGroup ?: return
        if (root.findViewWithTag<View>(TAG) != null) return

        val density = activity.resources.displayMetrics.density
        val button = TextView(activity).apply {
            tag = TAG
            text = "KhoiRevanced"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
            background = GradientDrawable().apply {
                setColor(Color.rgb(103, 80, 164))
                cornerRadius = 24 * density
            }
            setOnClickListener {
                AlertDialog.Builder(activity)
                    .setTitle("KhoiRevanced runtime")
                    .setMessage(
                        "Profile: ${config.profile}\n\n" +
                            "Runtime injection is active. The native ART hook backend is the next step; " +
                            "therefore NexAlloy/Morphe feature switches are not available yet."
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
        root.addView(button, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END,
        ).apply {
            setMargins(0, 0, (20 * density).toInt(), (28 * density).toInt())
        })
    }
}
