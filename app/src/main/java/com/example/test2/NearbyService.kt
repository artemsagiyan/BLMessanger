package com.example.test2

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.nio.charset.StandardCharsets
import java.util.*

class NearbyService(
    private val context: Context,
    private val onMessageReceived: (String, String) -> Unit,
    private val onLog: (String) -> Unit
) {
    private val STRATEGY = Strategy.P2P_CLUSTER
    private val SERVICE_ID = "com.example.test2.NEARBY_CHAT"
    private val deviceName = "Device-" + UUID.randomUUID().toString().take(4)
    private val handler = Handler(Looper.getMainLooper())

    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val endpointIds = mutableSetOf<String>()

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            onLog("Connection initiated with $endpointId (${info.endpointName})")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                onLog("Connected to $endpointId")
                endpointIds.add(endpointId)
            } else {
                onLog("Connection failed: $endpointId")
            }
        }

        override fun onDisconnected(endpointId: String) {
            onLog("Disconnected: $endpointId")
            endpointIds.remove(endpointId)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let {
                val msg = String(it, StandardCharsets.UTF_8)
                onLog("Received from $endpointId: $msg")
                onMessageReceived(msg, endpointId)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    fun startAdvertising() {
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(
            deviceName,
            SERVICE_ID,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener { onLog("Started advertising as $deviceName") }
         .addOnFailureListener { onLog("Failed to start advertising: $it") }
    }

    fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            discoveryOptions
        ).addOnSuccessListener { onLog("Started discovery") }
         .addOnFailureListener { onLog("Failed to start discovery: $it") }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            onLog("Found endpoint: $endpointId (${info.endpointName})")
            connectionsClient.requestConnection(deviceName, endpointId, connectionLifecycleCallback)
        }

        override fun onEndpointLost(endpointId: String) {
            onLog("Endpoint lost: $endpointId")
        }
    }

    fun sendMessage(message: String) {
        val payload = Payload.fromBytes(message.toByteArray(StandardCharsets.UTF_8))
        for (id in endpointIds) {
            connectionsClient.sendPayload(id, payload)
        }
        onLog("Sent: $message")
    }

    fun stopAll() {
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        endpointIds.clear()
        onLog("Stopped all Nearby activities")
    }
} 