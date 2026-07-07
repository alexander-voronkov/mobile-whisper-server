package com.example.whisperserver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.whisperserver.MainActivity
import com.example.whisperserver.R
import com.example.whisperserver.WhisperApp
import com.example.whisperserver.data.ModelRegistry
import com.example.whisperserver.native.LaunchSpec
import com.example.whisperserver.native.WhisperBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the native whisper-server process. Runs as a
 * microphone-typed foreground service with a persistent notification and holds
 * a partial wake lock while active so the server survives screen-off.
 */
class WhisperServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var bridge: WhisperBridge
    private var wakeLock: PowerManager.WakeLock? = null
    private var lowMemoryWarned = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        bridge = WhisperBridge(
            context = applicationContext,
            scope = scope,
            onFatalError = ::onFatalError,
        )
        observeState()
        startMemoryMonitor()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                restartServer()
                return START_STICKY
            }
            else -> startServer(fromBoot = intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) == true)
        }
        return START_STICKY
    }

    private fun startServer(fromBoot: Boolean = false) {
        // Must call startForeground promptly after startForegroundService().
        try {
            startAsForeground(buildNotification("Starting…", running = false), fromBoot)
        } catch (e: Exception) {
            // e.g. microphone FGS started without RECORD_AUDIO granted on API 34+.
            val msg = "Cannot start foreground service: ${e.message}. " +
                "Grant the microphone permission and try again."
            ServerController.appendLog(LogLevel.ERROR, msg)
            ServerController.setState(ServerState.Error(msg))
            stopSelf()
            return
        }
        acquireWakeLock()

        scope.launch {
            val container = WhisperApp.container(applicationContext)
            val config = container.configRepository.config.first()
            val model = ModelRegistry.byId(config.selectedModelId) ?: ModelRegistry.default
            val modelFile = container.modelDownloader.modelFile(model)

            if (!modelFile.exists()) {
                ServerController.appendLog(
                    LogLevel.ERROR,
                    "Selected model '${model.id}' is not downloaded. Open the app and download it first.",
                )
                ServerController.setState(ServerState.Error("Model not downloaded"))
                onFatalError("Model '${model.displayName}' is not downloaded")
                return@launch
            }

            val spec = LaunchSpec(
                config = config,
                apiKey = container.secureStore.apiKey,
                modelPath = modelFile.absolutePath,
            )
            bridge.start(spec)
        }
    }

    private fun stopServer() {
        bridge.stop()
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun restartServer() {
        ServerController.appendLog(LogLevel.INFO, "Restart requested")
        bridge.stop()
        // bridge.stop()/ServerProcess.stop() are asynchronous and may leave the
        // old native server holding the internal port for up to ~3s (graceful
        // wait) before it is force-killed. Wait past that window so the relaunch
        // doesn't race the old process for the same 127.0.0.1:<internal> port.
        scope.launch {
            delay(RESTART_GRACE_MS)
            startServer()
        }
    }

    private fun observeState() {
        scope.launch {
            ServerController.state.collect { state ->
                when (state) {
                    is ServerState.Running -> {
                        updateNotification(
                            buildNotification(
                                "Running on ${state.host}:${state.port}",
                                running = true,
                            ),
                        )
                    }
                    is ServerState.Restarting ->
                        updateNotification(buildNotification("Restarting…", running = false))
                    is ServerState.Starting ->
                        updateNotification(buildNotification("Starting…", running = false))
                    is ServerState.Error, ServerState.Stopped -> {
                        // Terminal states from an unexpected path: drop foreground.
                    }
                }
            }
        }
    }

    private fun startMemoryMonitor() {
        scope.launch {
            val checker = WhisperApp.container(applicationContext).memoryChecker
            while (isActive) {
                if (ServerController.state.value.isActive) {
                    ServerController.updateMemoryUsage(checker.appMemoryUsageBytes())
                    if (checker.isLowMemory() && !lowMemoryWarned) {
                        lowMemoryWarned = true
                        postWarningNotification(
                            "Low memory — the server may crash. Consider a smaller model.",
                        )
                    } else if (!checker.isLowMemory()) {
                        lowMemoryWarned = false
                    }
                }
                delay(MEMORY_SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun onFatalError(message: String) {
        releaseWakeLock()
        postWarningNotification("Whisper Server stopped: $message")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- Foreground / notifications ----------------------------------------

    private fun startAsForeground(notification: Notification, fromBoot: Boolean) {
        // Choose the FGS type at runtime: "microphone" (per spec) when RECORD_AUDIO
        // is granted, otherwise "dataSync". Starting a microphone-typed FGS on
        // Android 14+ without the mic permission throws. When launched from a
        // background BOOT_COMPLETED broadcast, a microphone FGS can't be started
        // at all (even with the permission), so force dataSync there.
        val type = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> 0
            !fromBoot && hasMicPermission() -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun updateNotification(notification: Notification) {
        NotificationManagerCompat.from(this).notify(NOTIF_ID, notification)
    }

    private fun buildNotification(text: String, running: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (running) {
            builder.addAction(
                0,
                getString(R.string.action_stop),
                servicePendingIntent(ACTION_STOP, 1),
            )
            builder.addAction(
                0,
                getString(R.string.action_restart),
                servicePendingIntent(ACTION_RESTART, 2),
            )
        }
        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, WhisperServerService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun postWarningNotification(text: String) {
        val notification = NotificationCompat.Builder(this, WARN_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        // POST_NOTIFICATIONS may be denied; NotificationManagerCompat handles gracefully.
        NotificationManagerCompat.from(this).notify(WARN_NOTIF_ID, notification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Whisper Server",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Persistent server status" },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                WARN_CHANNEL_ID,
                "Whisper Server Warnings",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Crash and low-memory warnings" },
        )
    }

    // ---- Wake lock ----------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        bridge.stop()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.whisperserver.action.START"
        const val ACTION_STOP = "com.example.whisperserver.action.STOP"
        const val ACTION_RESTART = "com.example.whisperserver.action.RESTART"
        const val EXTRA_FROM_BOOT = "com.example.whisperserver.extra.FROM_BOOT"

        private const val CHANNEL_ID = "whisper_server"
        private const val WARN_CHANNEL_ID = "whisper_server_warn"
        private const val NOTIF_ID = 1001
        private const val WARN_NOTIF_ID = 1002
        private const val WAKE_LOCK_TAG = "WhisperServer::ServerWakeLock"
        private const val MEMORY_SAMPLE_INTERVAL_MS = 10_000L
        // Slightly longer than ServerProcess's 3s graceful-stop window.
        private const val RESTART_GRACE_MS = 3_500L

        fun start(context: Context) = start(context, fromBoot = false)

        /**
         * Start from a BOOT_COMPLETED broadcast. Signals the service to use the
         * dataSync FGS type, since a microphone-typed FGS can't be started from a
         * background broadcast on Android 14+.
         */
        fun startFromBoot(context: Context) = start(context, fromBoot = true)

        private fun start(context: Context, fromBoot: Boolean) {
            val intent = Intent(context, WhisperServerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_FROM_BOOT, fromBoot)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, WhisperServerService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
