package com.steelsignage.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the player automatically when the TV / box powers on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try { context.startActivity(launch) } catch (_: Exception) { /* needs "Display over other apps" on Android 10+ */ }
    }
}
