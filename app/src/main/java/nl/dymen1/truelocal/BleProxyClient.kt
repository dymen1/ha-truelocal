package nl.dymen1.truelocal

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject

class BleProxyClient(
    context: Context,
    private val webSocketUrl: String,
    private val extraHeaders: Map<String, String>,
    private val log: (String) -> Unit
) : BleCentral.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val central = BleCentral(context.applicationContext, scope, this)
    private var webSocket: WebSocket? = null
    private val handshakeComplete = CompletableDeferred<Unit>()
    private val discoveredAddresses = mutableSetOf<String>()

    fun connect(httpClient: OkHttpClient): CompletableDeferred<Unit> {
        val requestBuilder = Request.Builder().url(webSocketUrl)
        extraHeaders.forEach { (name, value) -> requestBuilder.addHeader(name, value) }
        webSocket = httpClient.newWebSocket(requestBuilder.build(), socketListener)
        return handshakeComplete
    }

    fun close() {
        webSocket?.close(1000, null)
        central.shutdown()
        scope.cancel()
    }

    private val socketListener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            log("BLE proxy socket open, sending hello")
            webSocket.send(JSONObject().put("type", "hello").put("version", 1).toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = JSONObject(text)
            when {
                json.optString("type") == "hello_response" -> {
                    if (json.has("error")) {
                        val failure = IllegalStateException(json.optString("message"))
                        log("BLE proxy handshake failed: ${json.optString("message")}")
                        handshakeComplete.completeExceptionally(failure)
                    } else {
                        log("BLE proxy handshake complete (protocol v${json.optInt("version")})")
                        handshakeComplete.complete(Unit)
                    }
                }
                json.has("command") -> {
                    val id = json.getInt("id")
                    val command = json.getString("command")
                    val args = json.optJSONObject("args") ?: JSONObject()
                    scope.launch { handleCommand(id, command, args) }
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (bytes.size < 3) return
            val opcode = bytes[0].toInt()
            if (opcode != OPCODE_WRITE_DATA) return
            val connectionHandle =
                ((bytes[1].toInt() and 0xff) shl 8) or (bytes[2].toInt() and 0xff)
            val payload = bytes.substring(3).toByteArray()
            try {
                central.connection(connectionHandle).queueBinaryWrite(payload)
            } catch (error: BleProxyException) {
                log("Binary frame for unknown handle $connectionHandle dropped")
            }
        }

        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
            log("BLE proxy socket failure: ${error.message}")
            if (!handshakeComplete.isCompleted) handshakeComplete.completeExceptionally(error)
            central.shutdown()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            log("BLE proxy socket closed ($code)")
            central.shutdown()
        }
    }

    private suspend fun handleCommand(id: Int, command: String, args: JSONObject) {
        try {
            val result = executeCommand(command, args)
            sendJson(JSONObject().put("id", id).put("success", true).put("result", result))
        } catch (error: BleProxyException) {
            log("Command $command failed: ${error.code} (${error.message})")
            sendJson(
                JSONObject()
                    .put("id", id)
                    .put("success", false)
                    .put("error", error.code)
                    .put("message", error.message)
            )
        } catch (error: Exception) {
            log("Command $command crashed: $error")
            sendJson(
                JSONObject()
                    .put("id", id)
                    .put("success", false)
                    .put("error", "internal")
                    .put("message", error.message ?: error.toString())
            )
        }
    }

    private suspend fun executeCommand(command: String, args: JSONObject): JSONObject =
        when (command) {
            "start_scan" -> {
                val serviceUuids = args.optJSONArray("service_uuids").toStringList()
                central.startScan(serviceUuids)
                log("Scan started (filter: $serviceUuids)")
                JSONObject()
            }
            "stop_scan" -> {
                central.stopScan()
                log("Scan stopped")
                JSONObject()
            }
            "connect" -> {
                val address = args.getString("address")
                log("Connecting to $address...")
                val connection = central.connect(address, args.optLong("timeout", 30_000))
                log("Connected to $address (handle ${connection.handle}, MTU ${connection.currentMtu})")
                JSONObject()
                    .put("connection_handle", connection.handle)
                    .put("mtu", connection.currentMtu)
            }
            "disconnect" -> {
                val connection = central.connection(args.getInt("connection_handle"))
                connection.disconnect()
                central.remove(connection.handle)
                log("Connection ${connection.handle} closed")
                JSONObject()
            }
            "discover_services" -> JSONObject().put(
                "services",
                central.connection(args.getInt("connection_handle")).discoverServices()
            )
            "discover_characteristics" -> JSONObject().put(
                "characteristics",
                central.connection(args.getInt("connection_handle"))
                    .characteristics(args.getString("service_uuid"))
            )
            "read_characteristic" -> {
                val value = central.connection(args.getInt("connection_handle"))
                    .read(args.getString("characteristic_uuid"))
                JSONObject().put("value", Base64.encodeToString(value, Base64.NO_WRAP))
            }
            "write_characteristic" -> {
                central.connection(args.getInt("connection_handle")).write(
                    args.getString("characteristic_uuid"),
                    Base64.decode(args.getString("value"), Base64.DEFAULT),
                    args.optBoolean("response", false)
                )
                JSONObject()
            }
            "subscribe_characteristic" -> {
                central.connection(args.getInt("connection_handle"))
                    .subscribe(args.getString("characteristic_uuid"))
                JSONObject()
            }
            "unsubscribe_characteristic" -> {
                central.connection(args.getInt("connection_handle"))
                    .unsubscribe(args.getString("characteristic_uuid"))
                JSONObject()
            }
            "write_and_subscribe" -> {
                central.connection(args.getInt("connection_handle")).writeAndSubscribe(
                    args.getString("write_uuid"),
                    Base64.decode(args.getString("write_value"), Base64.DEFAULT),
                    args.optBoolean("write_response", false),
                    args.getString("subscribe_uuid")
                )
                JSONObject()
            }
            "request_mtu" -> JSONObject().put(
                "mtu",
                central.connection(args.getInt("connection_handle"))
                    .requestMtu(args.getInt("mtu"))
            )
            else -> throw BleProxyException("internal", "Unsupported command $command")
        }

    override fun onDeviceDiscovered(data: JSONObject) {
        val address = data.optString("address")
        if (discoveredAddresses.add(address)) {
            log("Device discovered: $address (${data.optString("name", "unnamed")})")
        }
        sendJson(JSONObject().put("event", "device_discovered").put("data", data))
    }

    override fun onNotification(connectionHandle: Int, value: ByteArray) {
        val frame = ByteArray(3 + value.size)
        frame[0] = OPCODE_NOTIFICATION.toByte()
        frame[1] = (connectionHandle shr 8).toByte()
        frame[2] = connectionHandle.toByte()
        value.copyInto(frame, 3)
        webSocket?.send(frame.toByteString())
    }

    override fun onUnexpectedDisconnect(connectionHandle: Int, reason: String) {
        log("Unexpected disconnect of handle $connectionHandle: $reason")
        sendJson(
            JSONObject().put("event", "disconnected").put(
                "data",
                JSONObject().put("connection_handle", connectionHandle).put("reason", reason)
            )
        )
    }

    override fun onLog(message: String) = log(message)

    private fun sendJson(message: JSONObject) {
        webSocket?.send(message.toString())
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { getString(it) }
    }

    companion object {
        private const val OPCODE_WRITE_DATA = 0x01
        private const val OPCODE_NOTIFICATION = 0x02
    }
}
