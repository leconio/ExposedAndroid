package me.aside0.exposedadb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.aside0.exposedadb.ui.theme.ExposedAdbTheme
import me.aside0.exposedadb.services.TileServices

class MainActivity : ComponentActivity() {

    private var tileService: TileServices? = null
    private var bound = false
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val serviceState = mutableStateOf<TileServices?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as TileServices.LocalBinder
            tileService = binder.getService()
            bound = true
            serviceState.value = tileService
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            bound = false
            tileService = null
            serviceState.value = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start TileServices as foreground service
        startTileService()

        enableEdgeToEdge()
        setContent {
            ExposedAdbTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val currentService by serviceState
                    NatmapControlPanel(
                        modifier = Modifier.padding(innerPadding),
                        tileService = currentService
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Bind to TileService
        Intent(this, TileServices::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    private fun startTileService() {
        val intent = Intent(this, TileServices::class.java)
        startForegroundService(intent)
    }

}

@Composable
fun NatmapControlPanel(
    modifier: Modifier = Modifier,
    tileService: TileServices?
) {
    // Collect state from service
    val logs by (tileService?.logs?.collectAsState() ?: run {
        val defaultLogs = remember { mutableStateOf(emptyList<String>()) }
        defaultLogs
    })
    val isRunning by (tileService?.isNatmapRunning?.collectAsState() ?: run {
        val defaultRunning = remember { mutableStateOf(false) }
        defaultRunning
    })
    val connectionStatus by (tileService?.connectionStatus?.collectAsState() ?: run {
        val defaultStatus = remember { mutableStateOf("Disconnected") }
        defaultStatus
    })

    val startNatmap = {
        tileService?.callNatmapAsync(
            args = listOf("natmap", "-u", "-s", "turn.cloudflare.com", "-b", "55555")
        )
        Unit
    }

    val stopNatmap = {
        tileService?.stopNatmap()
        Unit
    }
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status display
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Status: $connectionStatus",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Running: ${if (isRunning) "Yes" else "No"}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Control buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = startNatmap,
                enabled = !isRunning && tileService != null,
                modifier = Modifier.weight(1f)
            ) {
                Text("Start Natmap")
            }

            Button(
                onClick = stopNatmap,
                enabled = isRunning && tileService != null,
                modifier = Modifier.weight(1f)
            ) {
                Text("Stop Natmap")
            }
        }

        // Log panel
        Card(
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier.padding(8.dp)
            ) {
                Text(
                    text = "Logs",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                val listState = rememberLazyListState()

                LaunchedEffect(logs.size) {
                    if (logs.isNotEmpty()) {
                        listState.animateScrollToItem(logs.size - 1)
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(logs) { log ->
                        Text(
                            text = log,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun NatmapControlPanelPreview() {
    ExposedAdbTheme {
        NatmapControlPanel(
            tileService = null
        )
    }
}