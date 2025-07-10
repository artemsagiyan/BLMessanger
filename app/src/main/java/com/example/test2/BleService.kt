package com.example.test2

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log

import java.util.*
import java.util.concurrent.ConcurrentHashMap

class BleService(private val context: Context) {
    private val TAG = "BleService"
    private val SERVICE_UUID = UUID.fromString("00001234-0000-1000-8000-00805f9b34fb")
    private val PARCEL_UUID = ParcelUuid(SERVICE_UUID)
    
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private val handler = Handler(Looper.getMainLooper())
    
    private var isAdvertising = false
    private var isScanning = false
    private var isCurrentlyAdvertising = false
    private var messageCallback: ((Message) -> Unit)? = null
    private var logCallback: ((LogEntry) -> Unit)? = null
    
    // Mesh networking с фрагментацией
    private val deviceId = "Device-${UUID.randomUUID().toString().take(8)}"
    private val processedFragments = ConcurrentHashMap<String, Long>() // fragmentId -> timestamp
    private val retransmissionQueue = mutableListOf<MessageFragment>()
    private val ownFragmentQueue = mutableListOf<MessageFragment>() // очередь для собственных фрагментов
    private val fragmentAssembler = FragmentAssembler()
    private val MESSAGE_EXPIRY_TIME = 5 * 60 * 1000L // 5 минут
    private val INSTANT_RETRANSMISSION_DELAY = 50L // 50ms для мгновенной ретрансляции
    private val FRAGMENT_SEND_DELAY = 1200L // 1.2 секунды между фрагментами одного сообщения
    
    init {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        
        if (bluetoothAdapter == null) {
            addLog("Bluetooth not supported on this device", LogType.ERROR)
        } else {
            if (!bluetoothAdapter!!.isEnabled) {
                addLog("Bluetooth is disabled. Please enable Bluetooth", LogType.ERROR)
            }
            
            advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
            scanner = bluetoothAdapter?.bluetoothLeScanner
            
            if (advertiser == null) {
                addLog("BLE advertising not supported on this device", LogType.ERROR)
            }
            
            if (scanner == null) {
                addLog("BLE scanning not supported on this device", LogType.ERROR)
            }
        }
        
        addLog("BLE Mesh Service with Fragmentation initialized. Device ID: $deviceId", LogType.INFO)
        addLog("Capabilities - Adapter: ${bluetoothAdapter != null && bluetoothAdapter!!.isEnabled}, " +
               "Advertiser: ${advertiser != null}, Scanner: ${scanner != null}", LogType.DEBUG)
        
        // Очистка старых данных каждые 30 секунд
        startCleanup()
    }
    
    fun setMessageCallback(callback: (Message) -> Unit) {
        messageCallback = callback
    }
    
    fun setLogCallback(callback: (LogEntry) -> Unit) {
        logCallback = callback
    }
    
    private fun addLog(message: String, type: LogType = LogType.INFO) {
        val entry = LogEntry(message, type = type)
        logCallback?.invoke(entry)
        when (type) {
            LogType.ERROR -> Log.e(TAG, message)
            LogType.DEBUG -> Log.d(TAG, message)
            LogType.INFO -> Log.i(TAG, message)
        }
    }
    
    fun sendMessage(text: String) {
        addLog("Sending message: '$text' (${text.length} chars)", LogType.INFO)
        
        // Создаем фрагменты для сообщения
        val fragments = MessageFragment.fragmentMessage(text, deviceId)
        
        addLog("Message fragmented into ${fragments.size} fragments", LogType.DEBUG)
        
        // Добавляем фрагменты в обработанные чтобы не ретранслировать свои же
        fragments.forEach { fragment ->
            val fragmentId = "${fragment.messageId}_${fragment.fragmentIndex}"
            processedFragments[fragmentId] = System.currentTimeMillis()
        }
        
        // Показываем собранное сообщение в UI сразу
        val completeMessage = Message(
            id = fragments.first().messageId,
            text = text,
            timestamp = fragments.first().timestamp,
            senderId = deviceId,
            hops = 0
        )
        messageCallback?.invoke(completeMessage)
        
        // Добавляем фрагменты в очередь для последовательной отправки
        ownFragmentQueue.addAll(fragments)
        
        // Запускаем отправку если очередь не была активна
        if (ownFragmentQueue.size == fragments.size) {
            processOwnFragmentQueue()
        }
    }
    
    private fun advertiseFragment(fragment: MessageFragment) {
        if (advertiser == null) {
            addLog("BLE advertising not supported", LogType.ERROR)
            return
        }
        
        val dataBytes = fragment.toCompactBytes()
        
        if (dataBytes.size > 20) {
            addLog("Fragment too large: ${dataBytes.size} bytes", LogType.ERROR)
            return
        }
        
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY) // Максимальная скорость
            .setConnectable(false)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH) // Максимальная мощность
            .setTimeout(1000) // Короткий timeout для быстрого переключения
            .build()
            
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(PARCEL_UUID)
            .addServiceData(PARCEL_UUID, dataBytes)
            .build()
            
        addLog("Advertising fragment ${fragment.fragmentIndex}/${fragment.totalFragments} of message", LogType.DEBUG)
        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }
    
    private fun processOwnFragmentQueue() {
        if (ownFragmentQueue.isNotEmpty() && !isCurrentlyAdvertising) {
            val fragment = ownFragmentQueue.removeAt(0)
            addLog("Sending own fragment ${fragment.fragmentIndex}/${fragment.totalFragments}", LogType.DEBUG)
            advertiseFragment(fragment)
            
            // Планируем отправку следующего фрагмента
            handler.postDelayed({
                processOwnFragmentQueue()
            }, FRAGMENT_SEND_DELAY)
        }
    }

    fun startMeshNetwork() {
        addLog("Starting BLE Mesh Network with Instant Retransmission", LogType.INFO)
        startScanning()
        isAdvertising = true
        processRetransmissionQueue()
    }
    
    fun stopMeshNetwork() {
        addLog("Stopping BLE Mesh Network", LogType.INFO)
        stopScanning()
        stopCurrentAdvertising()
        isAdvertising = false
        retransmissionQueue.clear()
        ownFragmentQueue.clear()
        handler.removeCallbacksAndMessages(null)
    }
    
    private fun startScanning() {
        if (scanner == null) {
            addLog("BLE scanning not supported", LogType.ERROR)
            return
        }
        
        if (isScanning) return
        
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // Максимальная скорость сканирования
            .setReportDelay(0) // Мгновенные отчеты
            .build()
        
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(PARCEL_UUID)
                .build()
        )
        
        addLog("Starting high-speed BLE scanning for fragments", LogType.DEBUG)
        scanner?.startScan(filters, settings, scanCallback)
        isScanning = true
    }
    
    private fun stopScanning() {
        if (isScanning) {
            addLog("Stopping BLE scanning", LogType.DEBUG)
            scanner?.stopScan(scanCallback)
            isScanning = false
        }
    }
    
    private fun stopCurrentAdvertising() {
        if (isCurrentlyAdvertising) {
            addLog("Stopping current advertising", LogType.DEBUG)
            advertiser?.stopAdvertising(advertiseCallback)
            isCurrentlyAdvertising = false
        }
    }
    
    private fun processRetransmissionQueue() {
        handler.postDelayed({
            // Приоритет у собственных фрагментов
            if (isAdvertising && ownFragmentQueue.isEmpty() && retransmissionQueue.isNotEmpty() && !isCurrentlyAdvertising) {
                val fragment = retransmissionQueue.removeAt(0)
                if (!fragment.isExpired()) {
                    advertiseFragment(fragment)
                } else {
                    addLog("Removing expired fragment from queue", LogType.DEBUG)
                }
            }
            if (isAdvertising) {
                processRetransmissionQueue()
            }
        }, INSTANT_RETRANSMISSION_DELAY) // Мгновенная ретрансляция!
    }
    
    private fun startCleanup() {
        handler.postDelayed({
            val currentTime = System.currentTimeMillis()
            
            // Очистка старых фрагментов
            val fragmentIterator = processedFragments.entries.iterator()
            while (fragmentIterator.hasNext()) {
                val entry = fragmentIterator.next()
                if (currentTime - entry.value > MESSAGE_EXPIRY_TIME) {
                    fragmentIterator.remove()
                }
            }
            
            // Очистка неполных сообщений
            fragmentAssembler.cleanup()
            
            startCleanup()
        }, 30000) // каждые 30 секунд
    }
    
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            addLog("Fragment advertising started", LogType.DEBUG)
            isCurrentlyAdvertising = true
            // Автоматически сбрасываем флаг через timeout
            handler.postDelayed({
                isCurrentlyAdvertising = false
                addLog("Fragment advertising completed", LogType.DEBUG)
                
                // Обрабатываем очереди после завершения advertising
                if (ownFragmentQueue.isNotEmpty()) {
                    processOwnFragmentQueue()
                } else if (retransmissionQueue.isNotEmpty()) {
                    processRetransmissionQueue()
                }
            }, settingsInEffect.timeout.toLong())
        }
        
        override fun onStartFailure(errorCode: Int) {
            isCurrentlyAdvertising = false
            val errorMsg = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data too large"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                ADVERTISE_FAILED_ALREADY_STARTED -> "Already started"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                else -> "Unknown error ($errorCode)"
            }
            addLog("Fragment advertising failed: $errorMsg", LogType.ERROR)
            
            // Повторная попытка с приоритетом для собственных фрагментов
            handler.postDelayed({
                if (ownFragmentQueue.isNotEmpty()) {
                    processOwnFragmentQueue()
                } else if (retransmissionQueue.isNotEmpty()) {
                    processRetransmissionQueue()
                }
            }, 500) // 500ms задержка при ошибке
        }
    }
    
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val scanRecord = result.scanRecord
            val deviceAddress = result.device?.address ?: "unknown"
            
            val serviceData = scanRecord?.getServiceData(PARCEL_UUID)
            if (serviceData != null) {
                val fragment = MessageFragment.fromCompactBytes(serviceData)
                if (fragment != null) {
                    handleReceivedFragment(fragment, deviceAddress)
                } else {
                    addLog("Failed to parse fragment from $deviceAddress", LogType.ERROR)
                }
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            addLog("BLE scan failed: $errorCode", LogType.ERROR)
            // Быстрый перезапуск сканирования
            handler.postDelayed({
                if (isAdvertising) {
                    startScanning()
                }
            }, 500) // Всего 500ms задержка при ошибке сканирования
        }
    }
    
    private fun handleReceivedFragment(fragment: MessageFragment, fromDevice: String) {
        val fragmentId = "${fragment.messageId}_${fragment.fragmentIndex}"
        
        // Проверяем, не обрабатывали ли мы уже этот фрагмент
        if (processedFragments.containsKey(fragmentId)) {
            addLog("Duplicate fragment ignored: $fragmentId", LogType.DEBUG)
            return
        }
        
        // Проверяем, не от себя ли фрагмент
        if (fragment.originalSenderId == deviceId) {
            addLog("Ignoring own fragment", LogType.DEBUG)
            return
        }
        
        // Проверяем TTL
        if (fragment.isExpired()) {
            addLog("Fragment expired (TTL=0): $fragmentId", LogType.DEBUG)
            return
        }
        
        addLog("Received fragment ${fragment.fragmentIndex}/${fragment.totalFragments} from $fromDevice (TTL: ${fragment.ttl}, Hops: ${fragment.hops})", LogType.DEBUG)
        
        // Отмечаем фрагмент как обработанный
        processedFragments[fragmentId] = System.currentTimeMillis()
        
        // Пытаемся собрать полное сообщение
        val completeMessage = fragmentAssembler.addFragment(fragment)
        if (completeMessage != null) {
            addLog("Complete message assembled: '${completeMessage.text}' (${completeMessage.text.length} chars)", LogType.INFO)
            messageCallback?.invoke(completeMessage)
        }
        
        // МГНОВЕННАЯ ретрансляция фрагмента с уменьшенным TTL
        if (fragment.ttl > 1) {
            val retransmitFragment = fragment.decrementTtl()
            addLog("Queuing fragment for instant retransmission: $fragmentId (new TTL: ${retransmitFragment.ttl})", LogType.DEBUG)
            retransmissionQueue.add(retransmitFragment)
        } else {
            addLog("Fragment reached TTL limit, not retransmitting: $fragmentId", LogType.DEBUG)
        }
    }
} 