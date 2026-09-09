package nl.dymen1.truelocal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

class HaWebSocketClient(
    homeAssistantUrl: String,
    private val token: String,
    private val log: (String) -> Unit
) {

    private val webSocketUrl =
        homeAssistantUrl.trimEnd('/').replaceFirst("http", "ws") + "/api/websocket"

    private var webSocket: WebSocket? = null
    private val pendingResponses = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val messageCounter = AtomicInteger()
    private val authenticated = CompletableDeferred<Unit>()

    fun connect(httpClient: OkHttpClient): CompletableDeferred<Unit> {
        webSocket = httpClient.newWebSocket(
            Request.Builder().url(webSocketUrl).build(),
            socketListener
        )
        return authenticated
    }

    fun close() {
        webSocket?.close(1000, null)
    }

    suspend fun listZhaDevices(): JSONArray {
        val response = sendCommand(JSONObject().put("type", "zha/devices"))
        return response.optJSONArray("result") ?: JSONArray()
    }

    suspend fun permitZhaJoin(durationSeconds: Int) {
        sendCommand(
            JSONObject().put("type", "zha/devices/permit").put("duration", durationSeconds)
        )
    }

    suspend fun listConfigEntries(): JSONArray {
        val response = sendCommand(JSONObject().put("type", "config_entries/get"))
        return response.optJSONArray("result") ?: JSONArray()
    }

    suspend fun supervisorApi(endpoint: String, method: String = "get"): JSONObject? = try {
        sendCommand(
            JSONObject()
                .put("type", "supervisor/api")
                .put("endpoint", endpoint)
                .put("method", method)
        ).optJSONObject("result")
    } catch (error: Exception) {
        log("supervisor/api $method $endpoint failed: ${error.message}")
        null
    }

    suspend fun listThreadDatasets(): JSONArray {
        val response = sendCommand(JSONObject().put("type", "thread/list_datasets"))
        return response.optJSONObject("result")?.optJSONArray("datasets") ?: JSONArray()
    }

    suspend fun listDeviceRegistry(): JSONArray {
        val response = sendCommand(JSONObject().put("type", "config/device_registry/list"))
        return response.optJSONArray("result") ?: JSONArray()
    }

    suspend fun publishMqtt(topic: String, payload: String) {
        sendCommand(
            JSONObject()
                .put("type", "call_service")
                .put("domain", "mqtt")
                .put("service", "publish")
                .put(
                    "service_data",
                    JSONObject().put("topic", topic).put("payload", payload)
                )
        )
    }

    private suspend fun sendCommand(payload: JSONObject): JSONObject {
        val socket = webSocket ?: throw IllegalStateException("HA websocket not connected")
        val messageId = messageCounter.incrementAndGet()
        val deferred = CompletableDeferred<JSONObject>()
        pendingResponses[messageId] = deferred
        socket.send(payload.put("id", messageId).toString())
        val response = deferred.await()
        if (!response.optBoolean("success", true)) {
            throw IllegalStateException(
                response.optJSONObject("error")?.optString("message") ?: "command rejected"
            )
        }
        return response
    }

    private val socketListener = object : WebSocketListener() {

        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "auth_required" -> webSocket.send(
                    JSONObject().put("type", "auth").put("access_token", token).toString()
                )
                "auth_ok" -> {
                    log("HA websocket authenticated")
                    authenticated.complete(Unit)
                }
                "auth_invalid" -> authenticated.completeExceptionally(
                    IllegalStateException(json.optString("message", "token rejected"))
                )
                "result" -> pendingResponses.remove(json.optInt("id"))?.complete(json)
            }
        }

        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
            log("HA websocket failure: ${error.message}")
            if (!authenticated.isCompleted) authenticated.completeExceptionally(error)
            pendingResponses.values.forEach { it.completeExceptionally(error) }
            pendingResponses.clear()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            log("HA websocket closed ($code)")
        }
    }
}
