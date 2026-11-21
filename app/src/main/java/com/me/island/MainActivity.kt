package com.me.island

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val prefs = getSharedPreferences("island", MODE_PRIVATE)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply { textSize = 16f }
        col.addView(status)
        fun btn(label: String, intent: () -> Intent) = col.addView(Button(this).apply {
            text = label
            setOnClickListener { startActivity(intent()) }
        })
        btn("1. Allow display over other apps") {
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        }
        btn("2. Allow notification access") { Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
        btn("3. Exclude from battery optimization") { Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }
        col.addView(Switch(this).apply {
            text = "Hide in fullscreen apps (experimental)"
            isChecked = prefs.getBoolean("hideFs", false)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("hideFs", c).apply() }
        })
        col.addView(TextView(this).apply {
            text = "After granting 1 and 2, toggle notification access off and on once so the island starts.\n" +
                "If step 2 is greyed out: Settings > Apps > My Island > ⋮ > Allow restricted settings.\n\n" +
                "Long-press the island to expand. Swipe up to collapse. Tap to open the app."
            setPadding(0, pad, 0, 0)
        })
        setContentView(col)
    }

    override fun onResume() {
        super.onResume()
        val overlay = Settings.canDrawOverlays(this)
        val notif = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        status.text = "Overlay: ${if (overlay) "OK" else "missing"}\nNotification access: ${if (notif) "OK" else "missing"}"
    }
}
