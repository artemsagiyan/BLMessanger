package com.example.test2

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
    private lateinit var bleService: BleService

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allPermissionsGranted = permissions.values.all { it }
        if (allPermissionsGranted) {
            initializeBleService()
        } else {
            logs.add(LogEntry("Some permissions denied. BLE functionality may not work properly.", type = LogType.ERROR))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Проверяем и запрашиваем разрешения
        checkAndRequestPermissions()

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
                            if (::bleService.isInitialized) {
                                bleService.sendMessage(text)
                            } else {
                                logs.add(LogEntry("BLE service not initialized. Please grant permissions.", type = LogType.ERROR))
                            }
                        }
                    )
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val missingPermissions = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            initializeBleService()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun initializeBleService() {
        bleService = BleService(this)
        
        bleService.setMessageCallback { message ->
            messages.add(message)
        }
        
        bleService.setLogCallback { log -> 
            logs.add(log) 
        }

        // Запускаем mesh сеть
        bleService.startMeshNetwork()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::bleService.isInitialized) {
            bleService.stopMeshNetwork()
        }
    }
}

@Composable
fun MainScreen(
    messages: List<Message>,
    logs: List<LogEntry>,
    onSendMessage: (String) -> Unit
) {
    var messageText by remember { mutableStateOf("") }
    var showLogs by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        InfoBlockBle(context)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("BLE Mesh Chat", style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (showLogs) "Logs" else "Chat", 
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Switch(
                    checked = showLogs,
                    onCheckedChange = { showLogs = it }
                )
            }
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
        
        // Область ввода для длинных сообщений
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                // Статистика ввода
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Long Message Support",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${messageText.length} chars",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Многострочное поле ввода
                TextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Type any length message - will auto-fragment for BLE mesh...") },
                    minLines = 2,
                    maxLines = 8,
                    colors = TextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Информация о фрагментации и кнопка отправки
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (messageText.isNotEmpty()) {
                        val estimatedFragments = (messageText.toByteArray(Charsets.UTF_8).size + 10) / 11
                        Text(
                            text = "≈ $estimatedFragments fragments",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text("")
                    }
                    
                    Button(
                        onClick = {
                            if (messageText.isNotBlank()) {
                                onSendMessage(messageText)
                                messageText = ""
                            }
                        },
                        enabled = messageText.isNotBlank()
                    ) {
                        Text("Send Instant")
                    }
                }
            }
        }
    }
}

@Composable
fun InfoBlockBle(context: android.content.Context) {
    val deviceName = android.os.Build.MODEL ?: "Unknown"
    val androidVersion = android.os.Build.VERSION.RELEASE ?: "?"
    val sdkInt = android.os.Build.VERSION.SDK_INT
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text("Device: $deviceName", style = MaterialTheme.typography.bodySmall)
            Text("Android: $androidVersion (SDK $sdkInt)", style = MaterialTheme.typography.bodySmall)
            Text("BLE Mesh Network with Fragmentation", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Text("Max hops: 5 | Instant retransmission (50ms) | Unlimited message length", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun MessageItem(message: Message) {
    val isOwnMessage = message.senderId.startsWith("Device-") && message.hops == 0
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isOwnMessage) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Длинный текст сообщения
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Информационная строка
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = if (isOwnMessage) "You" else "From: ${message.senderId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (message.text.length > 50) {
                        Text(
                            text = "${message.text.length} chars",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Column(horizontalAlignment = Alignment.End) {
                    if (message.hops > 0) {
                        Text(
                            text = "${message.hops} hops",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = "Instant mesh",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun LogItem(log: LogEntry) {
    val backgroundColor = when (log.type) {
        LogType.ERROR -> MaterialTheme.colorScheme.errorContainer
        LogType.DEBUG -> MaterialTheme.colorScheme.surfaceVariant
        LogType.INFO -> MaterialTheme.colorScheme.surface
    }
    
    val textColor = when (log.type) {
        LogType.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        LogType.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
        LogType.INFO -> MaterialTheme.colorScheme.onSurface
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        )
    ) {
        Text(
            text = "[${log.formattedTime}] ${log.message}",
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            modifier = Modifier.padding(8.dp)
        )
    }
}