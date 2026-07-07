package com.example.whisperserver.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.whisperserver.WhisperApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Starts [WhisperServerService] on boot when autostart is enabled, using the
 * last-saved configuration and last-selected model.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val config = WhisperApp.container(appContext).configRepository.config.first()
                if (config.autostart) {
                    Log.i(TAG, "Autostart enabled — starting Whisper server")
                    WhisperServerService.start(appContext)
                } else {
                    Log.i(TAG, "Autostart disabled — not starting server")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to autostart server", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
