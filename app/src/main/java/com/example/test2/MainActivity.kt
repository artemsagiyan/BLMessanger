package com.example.test2

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.*

class MainActivity : ComponentActivity() {
    private val messages = mutableStateListOf<Message>()
    private val logs = mutableStateListOf<LogEntry>()
    private lateinit var nearbyService: NearbyService

    private val requiredPermissions = arrayOf(
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.NEARBY_WIFI_DEVICES
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nearbyService = NearbyService(
            context = this,
            onMessageReceived = { msg, from ->
                messages.add(Message(text = msg, senderId = from))
            },
            onLog = { log -> logs.add(LogEntry(log)) }
        )

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        messages = messages,
                        logs = logs,
                        onSendMessage = { text ->
                            messages.add(Message(text = text, senderId = "me"))
                            nearbyService.sendMessage(text)
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        nearbyService.stopAll()
    }
}

@Composable
fun MainScreen(
    messages: List<Message>,
    logs: List<LogEntry>,
    onSendMessage: (String) -> Unit
) {
    var messageText by remember { mutableStateOf("") }
    var showLogs by remember { mutableStateOf(true) }
    val context = LocalContext.current
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        InfoBlockNearby(context)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Nearby Chat")
            Switch(
                checked = showLogs,
                onCheckedChange = { showLogs = it },
                thumbContent = {
                    Text(if (showLogs) "Logs" else "Chat", style = MaterialTheme.typography.bodySmall)
                }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (showLogs) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                items(logs) { log ->
                    LogItem(log)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                items(messages) { message ->
                    MessageItem(message)
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = messageText,
                onValueChange = { messageText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message") }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (messageText.isNotBlank()) {
                        onSendMessage(messageText)
                        messageText = ""
                    }
                }
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
fun InfoBlockNearby(context: android.content.Context) {
    val deviceName = android.os.Build.MODEL ?: "Unknown"
    val androidVersion = android.os.Build.VERSION.RELEASE ?: "?"
    val sdkInt = android.os.Build.VERSION.SDK_INT
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text("Device: $deviceName", style = MaterialTheme.typography.bodySmall)
        Text("Android: $androidVersion (SDK $sdkInt)", style = MaterialTheme.typography.bodySmall)
        Text("Nearby Connections API", style = MaterialTheme.typography.bodySmall)
        Text("Strategy: P2P_CLUSTER", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun MessageItem(message: Message) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(8.dp)
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "From: ${message.senderId}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun LogItem(log: LogEntry) {
    val backgroundColor = when (log.type) {
        LogType.ERROR -> Color.Red.copy(alpha = 0.1f)
        LogType.DEBUG -> Color.Blue.copy(alpha = 0.1f)
        LogType.INFO -> Color.Gray.copy(alpha = 0.1f)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        )
    ) {
        Column(
            modifier = Modifier.padding(8.dp)
        ) {
            Text(
                text = "[${log.formattedTime}] ${log.message}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}