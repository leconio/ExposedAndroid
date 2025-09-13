package me.aside0.exposedadb.natmap

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object NatmapPlugin {
    @Volatile
    private var context: Context? = null
    private val isInitialized = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)
    @Volatile
    private var currentJob: Job? = null
    private val logListeners = mutableListOf<(String) -> Unit>()

    init {
        System.loadLibrary("natmap-jni")
    }

    fun initialize(context: Context) {
        this.context = context
        isInitialized.set(true)
    }

    fun isInitialized(): Boolean = isInitialized.get()

    fun isRunning(): Boolean = isRunning.get()

    fun addLogListener(listener: (String) -> Unit) {
        synchronized(logListeners) {
            logListeners.add(listener)
        }
    }

    fun removeLogListener(listener: (String) -> Unit) {
        synchronized(logListeners) {
            logListeners.remove(listener)
        }
    }

    fun clearLogListeners() {
        synchronized(logListeners) {
            logListeners.clear()
        }
    }

    private fun notifyLogListeners(log: String) {
        synchronized(logListeners) {
            logListeners.forEach { it(log) }
        }
    }

    suspend fun callNatmap(args: List<String>): NatmapResult = withContext(Dispatchers.IO) {
        if (!isInitialized.get()) {
            return@withContext NatmapResult(false, null, "Natmap not initialized")
        }

        if (!isRunning.compareAndSet(false, true)) {
            return@withContext NatmapResult(false, null, "Natmap is already running")
        }

        try {
            suspendCancellableCoroutine { continuation ->
                val nativeArgs = mutableListOf<String>().apply {
                    add("natmap")
                    addAll(args)
                }

                val argsArray = nativeArgs.toTypedArray()
                Log.i("NatmapJNI", "KOTLIN Input args: ${argsArray.contentToString()}")

                val logBuffer = StringBuilder()
                var hasReturned = false

                val callback = object : NatmapCallback {
                    override fun onResult(success: Boolean, output: String) {
                        val safeOutput = output
                        
                        synchronized(this) {
                            if (!hasReturned) {
                                hasReturned = true
                                val result = if (success) {
                                    NatmapResult(true, safeOutput, null, logBuffer.toString())
                                } else {
                                    NatmapResult(false, null, safeOutput, logBuffer.toString())
                                }
                                
                                if (continuation.isActive) {
                                    continuation.resume(result)
                                }
                            }
                        }
                    }

                    override fun onLogUpdate(log: String) {
                        synchronized(logBuffer) {
                            logBuffer.append(log).append("\n")
                        }
                        
                        notifyLogListeners(log)
                    }
                }

                continuation.invokeOnCancellation {
                    nativeStopExecution()
                }

                nativeExecuteNatmap(argsArray, callback)
            }
        } finally {
            isRunning.set(false)
            currentJob = null
        }
    }

    @Suppress("DEPRECATION")
    fun callNatmapAsync(
        args: List<String>,
        scope: CoroutineScope = GlobalScope,
        onResult: (NatmapResult) -> Unit = {},
        onLog: (String) -> Unit = {}
    ): Job {
        return scope.launch {
            val logListener = onLog
            addLogListener(logListener)
            
            try {
                val result = callNatmap(args)
                onResult(result)
            } catch (e: Exception) {
                onResult(NatmapResult(false, null, e.message ?: "Unknown error"))
            } finally {
                removeLogListener(logListener)
            }
        }.also { job ->
            currentJob = job
        }
    }

    fun stopNatmap(): Boolean {
        if (isRunning.get()) {
            nativeStopExecution()
            currentJob?.cancel()
            isRunning.set(false)
            
            notifyLogListeners("Execution stopped by user")
            return true
        }
        return false
    }

    external fun nativeExecuteNatmap(args: Array<String>, callback: NatmapCallback)
    external fun nativeStopExecution()
}

data class NatmapResult(
    val success: Boolean,
    val output: String? = null,
    val error: String? = null,
    val logs: String = ""
)