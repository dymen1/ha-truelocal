package nl.dymen1.truelocal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Base64
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class BleProxyException(val code: String, override val message: String) : Exception(message)

@SuppressLint("MissingPermission")
class BleCentral(
    private val context: Context,
    private val scope: CoroutineScope,
    private val listener: Listener
) {

    interface Listener {
        fun onDeviceDiscovered(data: JSONObject)
        fun onNotification(connectionHandle: Int, value: ByteArray)
        fun onUnexpectedDisconnect(connectionHandle: Int, reason: String)
        fun onLog(message: String)
    }

    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val connections = mutableMapOf<Int, GattConnection>()
    private var nextConnectionHandle = 1
    private var activeScanCallback: ScanCallback? = null

    fun startScan(serviceUuids: List<String>) {
        val adapter = requireAdapter()
        if (activeScanCallback != null) {
            throw BleProxyException("already_scanning", "A scan is already in progress")
        }
        val filters = serviceUuids.map { uuid ->
            ScanFilter.Builder().setServiceUuid(ParcelUuid(normalizeUuid(uuid))).build()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                listener.onDeviceDiscovered(advertisementToJson(result))
            }

            override fun onScanFailed(errorCode: Int) {
                listener.onLog("BLE scan failed with code $errorCode")
            }
        }
        adapter.bluetoothLeScanner.startScan(filters, settings, callback)
        activeScanCallback = callback
    }

    fun stopScan() {
        val callback = activeScanCallback
            ?: throw BleProxyException("not_scanning", "No scan is currently active")
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(callback)
        activeScanCallback = null
    }

    suspend fun connect(address: String, timeoutMillis: Long): GattConnection {
        requireAdapter()
        if (connections.values.any { it.address.equals(address, ignoreCase = true) }) {
            throw BleProxyException("already_connected", "Already connected to $address")
        }
        val device = bluetoothAdapter!!.getRemoteDevice(address)
        val connection = GattConnection(nextConnectionHandle++, device, this)
        connection.establish(context, timeoutMillis)
        connections[connection.handle] = connection
        return connection
    }

    fun connection(handle: Int): GattConnection =
        connections[handle]
            ?: throw BleProxyException("not_connected", "No active connection with handle $handle")

    fun remove(handle: Int) {
        connections.remove(handle)
    }

    fun shutdown() {
        activeScanCallback?.let { bluetoothAdapter?.bluetoothLeScanner?.stopScan(it) }
        activeScanCallback = null
        connections.values.toList().forEach { it.close() }
        connections.clear()
    }

    internal fun forwardNotification(handle: Int, value: ByteArray) =
        listener.onNotification(handle, value)

    internal fun forwardUnexpectedDisconnect(handle: Int, reason: String) {
        connections.remove(handle)
        listener.onUnexpectedDisconnect(handle, reason)
    }

    internal fun log(message: String) = listener.onLog(message)

    internal fun launchInScope(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    private fun requireAdapter(): BluetoothAdapter {
        val adapter = bluetoothAdapter
            ?: throw BleProxyException("bluetooth_unavailable", "No Bluetooth adapter present")
        if (!adapter.isEnabled) {
            throw BleProxyException("bluetooth_unavailable", "Bluetooth is disabled")
        }
        return adapter
    }

    private fun advertisementToJson(result: ScanResult): JSONObject {
        val record = result.scanRecord
        val data = JSONObject()
        data.put("address", result.device.address)
        record?.deviceName?.let { data.put("name", it) }
        data.put("rssi", result.rssi)
        data.put("connectable", result.isConnectable)
        record?.serviceData?.takeIf { it.isNotEmpty() }?.let { serviceData ->
            val serviceDataJson = JSONObject()
            serviceData.forEach { (parcelUuid, bytes) ->
                serviceDataJson.put(
                    shortUuid(parcelUuid.uuid),
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                )
            }
            data.put("service_data", serviceDataJson)
        }
        record?.manufacturerSpecificData?.takeIf { it.size() > 0 }?.let { manufacturerData ->
            val manufacturerJson = JSONObject()
            for (index in 0 until manufacturerData.size()) {
                manufacturerJson.put(
                    manufacturerData.keyAt(index).toString(),
                    Base64.encodeToString(manufacturerData.valueAt(index), Base64.NO_WRAP)
                )
            }
            data.put("manufacturer_data", manufacturerJson)
        }
        record?.serviceUuids?.takeIf { it.isNotEmpty() }?.let { uuids ->
            data.put("service_uuids", JSONArray(uuids.map { shortUuid(it.uuid) }))
        }
        return data
    }

    companion object {
        private const val BASE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"

        fun normalizeUuid(value: String): UUID {
            val lower = value.lowercase()
            return when (lower.length) {
                4 -> UUID.fromString("0000$lower$BASE_UUID_SUFFIX")
                32 -> UUID.fromString(
                    lower.substring(0, 8) + "-" + lower.substring(8, 12) + "-" +
                        lower.substring(12, 16) + "-" + lower.substring(16, 20) + "-" +
                        lower.substring(20)
                )
                else -> UUID.fromString(lower)
            }
        }

        fun shortUuid(uuid: UUID): String {
            val text = uuid.toString().lowercase()
            return if (text.startsWith("0000") && text.endsWith(BASE_UUID_SUFFIX)) {
                text.substring(4, 8)
            } else {
                text
            }
        }
    }
}
