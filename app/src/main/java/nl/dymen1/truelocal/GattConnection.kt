package nl.dymen1.truelocal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

@SuppressLint("MissingPermission")
class GattConnection(
    val handle: Int,
    private val device: BluetoothDevice,
    private val central: BleCentral
) {

    val address: String get() = device.address
    val currentMtu: Int get() = mtu

    private var gatt: BluetoothGatt? = null
    private var mtu = 23
    private var expectedDisconnect = false

    private val operationMutex = Mutex()
    private var pendingConnect: CompletableDeferred<Unit>? = null
    private var pendingMtu: CompletableDeferred<Int>? = null
    private var pendingServiceDiscovery: CompletableDeferred<Unit>? = null
    private var pendingWrite: CompletableDeferred<Unit>? = null
    private var pendingDescriptorWrite: CompletableDeferred<Unit>? = null
    private var pendingRead: CompletableDeferred<ByteArray>? = null

    private var lastWriteTarget: BluetoothGattCharacteristic? = null
    private val binaryWriteQueue = Channel<ByteArray>(Channel.UNLIMITED)

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                pendingConnect?.complete(Unit)
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gatt.close()
                val reason = "GATT status " + status
                pendingConnect?.completeExceptionally(BleProxyException("connection_failed", reason))
                failAllPendingOperations(reason)
                binaryWriteQueue.close()
                if (!expectedDisconnect) {
                    central.forwardUnexpectedDisconnect(handle, reason)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, newMtu: Int, status: Int) {
            completeOrFail(pendingMtu, newMtu, status, "mtu_request_failed")
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            completeOrFail(pendingServiceDiscovery, Unit, status, "discovery_failed")
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            completeOrFail(pendingWrite, Unit, status, "write_failed")
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            completeOrFail(pendingDescriptorWrite, Unit, status, "subscribe_failed")
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            completeOrFail(pendingRead, value, status, "read_failed")
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            completeOrFail(pendingRead, characteristic.value ?: ByteArray(0), status, "read_failed")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            central.forwardNotification(handle, value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            central.forwardNotification(handle, characteristic.value ?: ByteArray(0))
        }

        private fun <T> completeOrFail(
            deferred: CompletableDeferred<T>?,
            value: T,
            status: Int,
            errorCode: String
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                deferred?.complete(value)
            } else {
                deferred?.completeExceptionally(BleProxyException(errorCode, "GATT status " + status))
            }
        }
    }

    suspend fun establish(context: Context, timeoutMillis: Long) {
        val connectDeferred = CompletableDeferred<Unit>()
        pendingConnect = connectDeferred
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        try {
            withTimeout(timeoutMillis) { connectDeferred.await() }
        } catch (timeout: TimeoutCancellationException) {
            close()
            throw BleProxyException("timeout", "Connection to $address timed out")
        } finally {
            pendingConnect = null
        }
        mtu = runCatching { requestMtu(247) }.getOrDefault(23)
        startBinaryWriteWorker()
    }

    suspend fun requestMtu(requestedMtu: Int): Int = operationMutex.withLock {
        val deferred = CompletableDeferred<Int>()
        pendingMtu = deferred
        if (!requireGatt().requestMtu(requestedMtu)) {
            pendingMtu = null
            throw BleProxyException("mtu_request_failed", "requestMtu was rejected")
        }
        try {
            withTimeout(OPERATION_TIMEOUT_MILLIS) { deferred.await() }.also { mtu = it }
        } catch (timeout: TimeoutCancellationException) {
            throw BleProxyException("mtu_request_failed", "MTU negotiation timed out")
        } finally {
            pendingMtu = null
        }
    }

    suspend fun discoverServices(): JSONArray {
        operationMutex.withLock {
            val deferred = CompletableDeferred<Unit>()
            pendingServiceDiscovery = deferred
            if (!requireGatt().discoverServices()) {
                pendingServiceDiscovery = null
                throw BleProxyException("discovery_failed", "discoverServices was rejected")
            }
            try {
                withTimeout(DISCOVERY_TIMEOUT_MILLIS) { deferred.await() }
            } catch (timeout: TimeoutCancellationException) {
                throw BleProxyException("discovery_failed", "Service discovery timed out")
            } finally {
                pendingServiceDiscovery = null
            }
        }
        val services = JSONArray()
        requireGatt().services.forEach { service ->
            services.put(JSONObject().put("uuid", BleCentral.shortUuid(service.uuid)))
        }
        return services
    }

    fun characteristics(serviceUuid: String): JSONArray {
        val targetUuid = BleCentral.normalizeUuid(serviceUuid)
        val service = requireGatt().services.firstOrNull { it.uuid == targetUuid }
            ?: throw BleProxyException("service_not_found", "Service $serviceUuid not found")
        val characteristics = JSONArray()
        service.characteristics.forEach { characteristic ->
            characteristics.put(
                JSONObject()
                    .put("uuid", BleCentral.shortUuid(characteristic.uuid))
                    .put("properties", JSONArray(propertyNames(characteristic.properties)))
            )
        }
        return characteristics
    }

    suspend fun read(characteristicUuid: String): ByteArray = operationMutex.withLock {
        val characteristic = findCharacteristic(characteristicUuid)
        val deferred = CompletableDeferred<ByteArray>()
        pendingRead = deferred
        if (!requireGatt().readCharacteristic(characteristic)) {
            pendingRead = null
            throw BleProxyException("read_failed", "readCharacteristic was rejected")
        }
        try {
            withTimeout(OPERATION_TIMEOUT_MILLIS) { deferred.await() }
        } catch (timeout: TimeoutCancellationException) {
            throw BleProxyException("read_failed", "Read timed out")
        } finally {
            pendingRead = null
        }
    }

    suspend fun write(characteristicUuid: String, value: ByteArray, withResponse: Boolean) {
        operationMutex.withLock {
            val characteristic = findCharacteristic(characteristicUuid)
            performWrite(characteristic, value, withResponse)
            lastWriteTarget = characteristic
        }
    }

    suspend fun subscribe(characteristicUuid: String) {
        operationMutex.withLock {
            enableNotifications(findCharacteristic(characteristicUuid))
        }
    }

    suspend fun unsubscribe(characteristicUuid: String) {
        operationMutex.withLock {
            val characteristic = findCharacteristic(characteristicUuid)
            requireGatt().setCharacteristicNotification(characteristic, false)
            val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
                ?: throw BleProxyException("not_subscribed", "CCCD descriptor missing")
            writeDescriptorValue(descriptor, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
        }
    }

    suspend fun writeAndSubscribe(
        writeUuid: String,
        writeValue: ByteArray,
        writeWithResponse: Boolean,
        subscribeUuid: String
    ) {
        operationMutex.withLock {
            val writeCharacteristic = findCharacteristic(writeUuid)
            val subscribeCharacteristic = findCharacteristic(subscribeUuid)
            performWrite(writeCharacteristic, writeValue, writeWithResponse)
            lastWriteTarget = writeCharacteristic
            enableNotifications(subscribeCharacteristic)
        }
    }

    fun queueBinaryWrite(payload: ByteArray) {
        binaryWriteQueue.trySend(payload)
    }

    fun disconnect() {
        expectedDisconnect = true
        binaryWriteQueue.close()
        gatt?.disconnect()
        close()
    }

    fun close() {
        gatt?.close()
        gatt = null
    }

    private fun startBinaryWriteWorker() {
        central.launchInScope {
            for (payload in binaryWriteQueue) {
                try {
                    operationMutex.withLock {
                        val target = lastWriteTarget
                            ?: throw BleProxyException("write_failed", "No write characteristic established")
                        performWrite(target, payload, true)
                    }
                } catch (error: Exception) {
                    central.log("Binary write failed on handle $handle: ${error.message}")
                }
            }
        }
    }

    private suspend fun performWrite(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        withResponse: Boolean
    ) {
        val currentGatt = requireGatt()
        val writeType = if (withResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val deferred = CompletableDeferred<Unit>()
        pendingWrite = deferred
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            currentGatt.writeCharacteristic(characteristic, value, writeType) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.writeType = writeType
                characteristic.value = value
                currentGatt.writeCharacteristic(characteristic)
            }
        }
        if (!accepted) {
            pendingWrite = null
            throw BleProxyException("write_failed", "writeCharacteristic was rejected")
        }
        try {
            withTimeout(OPERATION_TIMEOUT_MILLIS) { deferred.await() }
        } catch (timeout: TimeoutCancellationException) {
            throw BleProxyException("write_failed", "Write timed out")
        } finally {
            pendingWrite = null
        }
    }

    private suspend fun enableNotifications(characteristic: BluetoothGattCharacteristic) {
        val supportsNotifications = characteristic.properties and
            (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)
        if (supportsNotifications == 0) {
            throw BleProxyException("notify_not_supported", "Characteristic does not support notifications")
        }
        if (!requireGatt().setCharacteristicNotification(characteristic, true)) {
            throw BleProxyException("subscribe_failed", "setCharacteristicNotification was rejected")
        }
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
            ?: throw BleProxyException("subscribe_failed", "CCCD descriptor missing")
        val enableValue =
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
        writeDescriptorValue(descriptor, enableValue)
    }

    private suspend fun writeDescriptorValue(
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ) {
        val currentGatt = requireGatt()
        val deferred = CompletableDeferred<Unit>()
        pendingDescriptorWrite = deferred
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            currentGatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = value
                currentGatt.writeDescriptor(descriptor)
            }
        }
        if (!accepted) {
            pendingDescriptorWrite = null
            throw BleProxyException("subscribe_failed", "writeDescriptor was rejected")
        }
        try {
            withTimeout(OPERATION_TIMEOUT_MILLIS) { deferred.await() }
        } catch (timeout: TimeoutCancellationException) {
            throw BleProxyException("subscribe_failed", "Descriptor write timed out")
        } finally {
            pendingDescriptorWrite = null
        }
    }

    private fun findCharacteristic(characteristicUuid: String): BluetoothGattCharacteristic {
        val targetUuid = BleCentral.normalizeUuid(characteristicUuid)
        requireGatt().services.forEach { service ->
            service.characteristics.firstOrNull { it.uuid == targetUuid }?.let { return it }
        }
        throw BleProxyException("characteristic_not_found", "Characteristic $characteristicUuid not found")
    }

    private fun requireGatt(): BluetoothGatt =
        gatt ?: throw BleProxyException("not_connected", "Connection is closed")

    private fun failAllPendingOperations(reason: String) {
        val error = BleProxyException("not_connected", reason)
        pendingMtu?.completeExceptionally(error)
        pendingServiceDiscovery?.completeExceptionally(error)
        pendingWrite?.completeExceptionally(error)
        pendingDescriptorWrite?.completeExceptionally(error)
        pendingRead?.completeExceptionally(error)
    }

    private fun propertyNames(properties: Int): List<String> {
        val names = mutableListOf<String>()
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) names.add("read")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) names.add("write")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            names.add("write-without-response")
        }
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) names.add("notify")
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) names.add("indicate")
        return names
    }

    companion object {
        private val CLIENT_CHARACTERISTIC_CONFIG: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val OPERATION_TIMEOUT_MILLIS = 10_000L
        private const val DISCOVERY_TIMEOUT_MILLIS = 20_000L
    }
}
