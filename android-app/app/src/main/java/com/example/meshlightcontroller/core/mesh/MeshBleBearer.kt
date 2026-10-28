package com.example.meshlightcontroller.core.mesh

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.observer.ConnectionObserver
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@SuppressLint("MissingPermission")
class MeshBleBearer(
    context: Context,
    private val received: (Int, ByteArray) -> Unit,
    private val disconnected: () -> Unit
) : BleManager(context) {
    // The Mesh SDK expects the ATT characteristic payload limit, excluding the ATT header.
    val bearerMtu: Int get() = (mtu - 3).coerceAtLeast(20)
    private var input: BluetoothGattCharacteristic? = null
    private var output: BluetoothGattCharacteristic? = null
    var provisioning = false
        private set

    init {
        setConnectionObserver(object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) = Unit
            override fun onDeviceConnected(device: BluetoothDevice) = Unit
            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) = disconnected()
            override fun onDeviceReady(device: BluetoothDevice) = Unit
            override fun onDeviceDisconnecting(device: BluetoothDevice) = Unit
            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) = disconnected()
        })
    }

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val provision = gatt.getService(uuid("1827"))
        val proxy = gatt.getService(uuid("1828"))
        provisioning = provision != null
        val service = provision ?: proxy ?: return false
        input = service.getCharacteristic(uuid(if (provisioning) "2adb" else "2add"))
        output = service.getCharacteristic(uuid(if (provisioning) "2adc" else "2ade"))
        return input != null && output != null &&
            input!!.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 &&
            output!!.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
    }

    override fun initialize() {
        requestMtu(247).enqueue()
        setNotificationCallback(output).with { _, data -> data.value?.let { received(bearerMtu, it.copyOf()) } }
        enableNotifications(output).enqueue()
    }

    override fun onServicesInvalidated() {
        input = null
        output = null
    }

    suspend fun open(device: BluetoothDevice) = suspendCancellableCoroutine<Unit> { continuation ->
        val request = connect(device).useAutoConnect(false).retry(2, 400).timeout(20_000)
            .done { if (continuation.isActive) continuation.resume(Unit) }
            .fail { _, status -> if (continuation.isActive) continuation.resumeWithException(IllegalStateException("GATT connection failed ($status)")) }
            .invalid { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("GATT connection could not start")) }
        continuation.invokeOnCancellation { request.cancel(); disconnect().enqueue() }
        request.enqueue()
    }

    suspend fun send(pdu: ByteArray, onPacketSent: (Int, ByteArray) -> Unit) = suspendCancellableCoroutine<Unit> { continuation ->
        val characteristic = input
        if (characteristic == null) {
            continuation.resumeWithException(IllegalStateException("Mesh bearer is disconnected"))
            return@suspendCancellableCoroutine
        }
        val type = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val request = writeCharacteristic(characteristic, pdu, type)
            .split(no.nordicsemi.android.ble.callback.WriteProgressCallback { _, packet, _ -> packet?.let { onPacketSent(bearerMtu, it) } })
            .done { if (continuation.isActive) continuation.resume(Unit) }
            .fail { _, status -> if (continuation.isActive) continuation.resumeWithException(IllegalStateException("GATT write failed ($status)")) }
            .invalid { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Mesh bearer is not ready")) }
        continuation.invokeOnCancellation { request.cancel() }
        request.enqueue()
    }

    suspend fun keepAlive() = suspendCancellableCoroutine<Unit> { continuation ->
        val descriptor = output?.getDescriptor(uuid("2902"))
        if (!isConnected || provisioning || descriptor == null) {
            continuation.resumeWithException(IllegalStateException("Mesh proxy is not ready"))
            return@suspendCancellableCoroutine
        }
        // An ATT read keeps the link active without changing the proxy filter or mesh state.
        val request = readDescriptor(descriptor).timeout(1500)
            .done { if (continuation.isActive) continuation.resume(Unit) }
            .fail { _, status -> if (continuation.isActive) continuation.resumeWithException(IllegalStateException("GATT keep-alive failed ($status)")) }
            .invalid { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Mesh proxy is not ready")) }
        continuation.invokeOnCancellation { request.cancel() }
        request.enqueue()
    }

    suspend fun closeLink() {
        if (!isConnected) {
            // An invalid disconnect has no success/failure callback in Nordic BLE.
            disconnect().enqueue()
            return
        }
        suspendCancellableCoroutine<Unit> { continuation ->
            disconnect().done { if (continuation.isActive) continuation.resume(Unit) }
                .fail { _, _ -> if (continuation.isActive) continuation.resume(Unit) }
                .invalid { if (continuation.isActive) continuation.resume(Unit) }.enqueue()
        }
    }

    companion object {
        fun uuid(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
    }
}
