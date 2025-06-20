package com.example.test2

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.nio.charset.StandardCharsets
import java.util.*

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
    private var messageCallback: ((String) -> Unit)? = null
    private var logCallback: ((LogEntry) -> Unit)? = null
    
    init {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        scanner = bluetoothAdapter?.bluetoothLeScanner
        
        addLog("BleService initialized. Adapter: ${bluetoothAdapter != null}, " +
               "Advertiser: ${advertiser != null}, Scanner: ${scanner != null}", LogType.DEBUG)
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
    
    fun startAdvertising(message: String) {
        if (advertiser == null) {
            addLog("BLE advertising not supported", LogType.ERROR)
            return
        }
        
        stopAdvertising()
        
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(false)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setTimeout(0)
            .build()
            
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(PARCEL_UUID)
            .addServiceData(PARCEL_UUID, message.toByteArray(StandardCharsets.UTF_8))
            .build()
            
        addLog("Starting advertising with message: $message", LogType.DEBUG)
        advertiser?.startAdvertising(settings, data, advertiseCallback)
        isAdvertising = true
        
        handler.postDelayed({
            if (isAdvertising) {
                startAdvertising(message)
            }
        }, 5000)
    }
    
    fun stopAdvertising() {
        if (isAdvertising) {
            addLog("Stopping advertising", LogType.DEBUG)
            advertiser?.stopAdvertising(advertiseCallback)
            isAdvertising = false
            handler.removeCallbacksAndMessages(null)
        }
    }
    
    fun startScanning(onMessageReceived: (String) -> Unit) {
        if (scanner == null) {
            addLog("BLE scanning not supported", LogType.ERROR)
            return
        }
        
        messageCallback = onMessageReceived
        stopScanning()
        
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        
        val filters = emptyList<ScanFilter>()
        
        addLog("Starting scanning", LogType.DEBUG)
        scanner?.startScan(filters, settings, scanCallback)
        isScanning = true
    }
    
    fun stopScanning() {
        if (isScanning) {
            addLog("Stopping scanning", LogType.DEBUG)
            scanner?.stopScan(scanCallback)
            isScanning = false
            messageCallback = null
            handler.removeCallbacksAndMessages(null)
        }
    }
    
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            addLog("Advertising started successfully")
        }
        
        override fun onStartFailure(errorCode: Int) {
            addLog("Advertising failed to start: $errorCode", LogType.ERROR)
            handler.postDelayed({
                if (!isAdvertising) {
                    startAdvertising("")
                }
            }, 1000)
        }
    }
    
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val scanRecord = result.scanRecord
            val deviceAddress = result.device?.address ?: "unknown"
            addLog("BLE packet from $deviceAddress, RSSI: ${result.rssi}, raw: ${scanRecord?.bytes?.joinToString { String.format("%02X", it) }}", LogType.DEBUG)

            scanRecord?.serviceUuids?.forEach { uuid ->
                addLog("Service UUID: $uuid", LogType.DEBUG)
            }
            scanRecord?.serviceData?.forEach { (uuid, data) ->
                addLog("ServiceData for $uuid: ${data.joinToString { String.format("%02X", it) }}", LogType.DEBUG)
            }
            scanRecord?.manufacturerSpecificData?.let { msd ->
                for (i in 0 until msd.size()) {
                    val id = msd.keyAt(i)
                    val data = msd.valueAt(i)
                    addLog("ManufacturerData id=$id: ${data.joinToString { String.format("%02X", it) }}", LogType.DEBUG)
                }
            }

            val serviceData = scanRecord?.getServiceData(PARCEL_UUID)
            if (serviceData != null) {
                val message = String(serviceData, StandardCharsets.UTF_8)
                addLog("Received message: $message from device: $deviceAddress")
                messageCallback?.invoke(message)
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            addLog("Scan failed with error: $errorCode", LogType.ERROR)
            handler.postDelayed({
                if (isScanning) {
                    startScanning(messageCallback ?: return@postDelayed)
                }
            }, 1000)
        }
    }
} 