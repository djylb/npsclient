package com.duanlab.npsclient

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.util.Log
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ShellService : LifecycleService() {
    // Process bookkeeping is confined to the main thread; the log buffer is synchronized.
    private val processThreads = mutableMapOf<NpsConfig, ShellThread>()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false
    private val _activeConfigs = MutableStateFlow<Set<NpsConfig>>(emptySet())
    val activeConfigs: StateFlow<Set<NpsConfig>> = _activeConfigs
    private val _logText = MutableStateFlow("")
    val logText: StateFlow<String> = _logText
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError
    private val logLines = ArrayDeque<String>()
    private var logCharacters = 0
    private val preferences by lazy { getSharedPreferences(PreferencesKey.PREF_NAME, Context.MODE_PRIVATE) }
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): ShellService = this@ShellService
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("shell_bg", getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.notification_channel_desc)
                }
            )
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val configs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableArrayListExtra(IntentExtraKey.NpsConfig, NpsConfig::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableArrayListExtra<NpsConfig>(IntentExtraKey.NpsConfig)
        }
        when (intent?.action) {
            ShellServiceAction.START -> {
                // Also required when invoked directly by the boot receiver.
                startForeground(1, showNotification())
                _lastError.value = null
                configs.orEmpty().forEach(::startNps)
            }
            ShellServiceAction.STOP -> configs.orEmpty().forEach(::stopNps)
        }
        updateRunningState()
        return START_NOT_STICKY
    }

    private fun startNps(config: NpsConfig) {
        Log.d("ShellService", "start config: $config")
        if (processThreads.containsKey(config)) return
        val args = try {
            NpcCommand.parse(config.cmdstr)
        } catch (_: IllegalArgumentException) {
            _lastError.value = getString(R.string.invalid_command)
            return
        }
        val binary = "${applicationInfo.nativeLibraryDir}/${BuildConfig.NpcFileName}"
        val commandList = listOf(binary) + args
        Log.d("ShellService", "Command: ${commandList.joinToString(" ")}")
        val thread = ShellThread(commandList, filesDir,
            onFinished = { finished, result ->
                mainHandler.post {
                    if (!destroyed && processThreads[config] === finished) {
                        processThreads.remove(config)
                        if (!result.stopped) {
                            val message = when {
                                result.error != null -> getString(R.string.process_start_failed,
                                    result.error.javaClass.simpleName)
                                result.exitCode != 0 -> getString(R.string.process_failed, result.exitCode)
                                else -> null
                            }
                            if (message != null) _lastError.value = message
                            appendLog(message ?: getString(R.string.process_exited, result.exitCode))
                        }
                        updateRunningState()
                    }
                }
            }
        ) { line ->
            if (!destroyed && preferences.getBoolean(PreferencesKey.LOG_OUTPUT_ENABLED, true)) {
                appendLog(line)
            }
        }
        processThreads[config] = thread
        thread.start()
    }

    private fun stopNps(config: NpsConfig) {
        processThreads.remove(config)?.stopProcess()
    }

    private fun updateRunningState() {
        _activeConfigs.value = processThreads.keys.toSet()
        val running = processThreads.isNotEmpty()
        preferences.edit().putBoolean(PreferencesKey.SHELL_RUNNING, running).apply()
        if (!running) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    @Synchronized
    private fun appendLog(line: String) {
        val bounded = line.takeLast(8192)
        logLines.addLast(bounded)
        logCharacters += bounded.length + 1
        while (logLines.size > 1000 || logCharacters > 128 * 1024) {
            logCharacters -= logLines.removeFirst().length + 1
        }
        _logText.value = logLines.joinToString("\n")
    }

    @Synchronized
    fun clearLog() {
        logLines.clear()
        logCharacters = 0
        _logText.value = ""
        _lastError.value = null
    }

    override fun onDestroy() {
        destroyed = true
        processThreads.values.forEach { it.stopProcess() }
        processThreads.clear()
        _activeConfigs.value = emptySet()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun showNotification(): Notification {
        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)
            }
        val notification = NotificationCompat.Builder(this, "shell_bg")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_channel_name))
            .setContentIntent(pendingIntent)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            notification.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        } else {
            notification.build()
        }
    }
}
