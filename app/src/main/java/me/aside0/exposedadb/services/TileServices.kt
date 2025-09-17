package me.aside0.exposedadb.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.aside0.exposedadb.natmap.NatmapCallback
import me.aside0.exposedadb.natmap.NatmapPlugin
import me.aside0.exposedadb.natmap.NatmapResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class TileServices : Service() {

    companion object {
        private const val CHANNEL_ID = "TileServicesChannel"
        private const val NOTIFICATION_ID = 1
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Natmap state management
    private val isInitialized = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)

    @Volatile
    private var currentJob: Job? = null

    // State flows for UI updates
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _isNatmapRunning = MutableStateFlow(false)
    val isNatmapRunning: StateFlow<Boolean> = _isNatmapRunning.asStateFlow()

    private val _connectionStatus = MutableStateFlow("Disconnected")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    // Keep the log flow for backward compatibility
    private val _logFlow = MutableSharedFlow<String>(replay = 100)
    val logFlow: Flow<String> = _logFlow

    inner class LocalBinder : Binder() {
        fun getService(): TileServices = this@TileServices
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initializeNatmap()
        _connectionStatus.value = "Service Started"
        emitLog("TileServices created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Natmap Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Natmap background service"
            }

            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Natmap Service")
            .setContentText("Natmap service is running")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
    }

    private fun initializeNatmap() {
        try {
            System.loadLibrary("natmap-jni")
            isInitialized.set(true)
            _connectionStatus.value = "Natmap Ready"
            emitLog("Natmap initialized successfully")
            Log.i("TileServices", "Natmap initialized successfully")
        } catch (e: Exception) {
            _connectionStatus.value = "Initialization Failed"
            emitLog("Failed to initialize natmap: ${e.message}")
            Log.e("TileServices", "Failed to initialize natmap", e)
        }
    }

    private fun cleanup() {
        stopNatmap()
        isInitialized.set(false)
    }

    private fun emitLog(log: String) {
        serviceScope.launch(Dispatchers.Main) {
            val currentLogs = _logs.value.toMutableList()
            currentLogs.add(log)
            _logs.value = currentLogs
            _logFlow.tryEmit(log)
        }
    }

    data class NatmapUpdate(
        val log: String? = null,
        val result: NatmapResult? = null
    )

    fun callNatmapWithFlow(args: List<String>): Flow<NatmapUpdate> = callbackFlow {
        if (!isInitialized.get()) {
            send(NatmapUpdate(result = NatmapResult(false, null, "Natmap not initialized")))
            close()
            return@callbackFlow
        }

        if (!isRunning.compareAndSet(false, true)) {
            send(NatmapUpdate(result = NatmapResult(false, null, "Natmap is already running")))
            close()
            return@callbackFlow
        }

        // Update running state
        launch(Dispatchers.Main) {
            _isNatmapRunning.value = true
            _connectionStatus.value = "Running"
        }

        // Move native call to IO dispatcher to prevent stack overflow
        launch(Dispatchers.IO) {
            try {
                val nativeArgs = mutableListOf<String>().apply {
//                add("natmap")
                    addAll(args)
                }

                val argsArray = nativeArgs.toTypedArray()
                Log.i("TileServices", "Input args: ${argsArray.contentToString()}")

                val callback = object : NatmapCallback {
                    override fun onResult(success: Boolean, output: String) {
                        launch(Dispatchers.Main) {
                            _isNatmapRunning.value = false
                            _connectionStatus.value = if (success) "Completed" else "Failed"
                        }
                        trySend(NatmapUpdate(result = NatmapResult(success, output)))
                        close()
                    }

                    override fun onLogUpdate(log: String) {
                        val newLog = log.trim()
                        if (newLog.isNotEmpty()) {
                            trySend(NatmapUpdate(log = newLog))
                            emitLog(newLog)
                        }
                    }
                }

                NatmapPlugin.nativeExecuteNatmap(argsArray, callback)
            } catch (e: Exception) {
                trySend(NatmapUpdate(result = NatmapResult(false, null, e.message ?: "Unknown error")))
                close()
            } finally {
                isRunning.set(false)
                currentJob = null
                launch(Dispatchers.Main) {
                    _isNatmapRunning.value = false
                    if (_connectionStatus.value == "Running") {
                        _connectionStatus.value = "Stopped"
                    }
                }
            }
        }

        awaitClose {
            NatmapPlugin.nativeStopExecution()
            isRunning.set(false)
            launch(Dispatchers.Main) {
                _isNatmapRunning.value = false
                _connectionStatus.value = "Stopped"
            }
        }
    }

    fun callNatmapAsync(
        args: List<String>,
        onResult: (NatmapResult) -> Unit = {},
        onLog: (String) -> Unit = {}
    ): Job {
        return serviceScope.launch {
            try {
                callNatmapWithFlow(args).collect { update ->
                    update.log?.let { log ->
                        onLog(log)
                    }
                    update.result?.let { result ->
                        onResult(result)
                    }
                }
            } catch (e: Exception) {
                onResult(NatmapResult(false, null, e.message ?: "Unknown error"))
            }
        }.also { job ->
            currentJob = job
        }
    }

    fun stopNatmap(): Boolean {
        return if (isRunning.get()) {
            NatmapPlugin.nativeStopExecution()
            currentJob?.cancel()
            isRunning.set(false)
            serviceScope.launch(Dispatchers.Main) {
                _isNatmapRunning.value = false
                _connectionStatus.value = "Stopped"
            }
            emitLog("Execution stopped by user")
            true
        } else {
            emitLog("Natmap was not running")
            false
        }
    }
}