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

class MatterWsClient(
    private val webSocketUrl: String,
    private val extraHeaders: Map<String, String>,
    private val log: (String) -> Unit
) {

    private var webSocket: WebSocket? = null
    private val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val messageCounter = AtomicInteger()
    private val serverInfoReceived = CompletableDeferred<Unit>()

    var serverInfo: JSONObject? = null
        private set

    fun connect(httpClient: OkHttpClient): CompletableDeferred<Unit> {
        val requestBuilder = Request.Builder().url(webSocketUrl)
        extraHeaders.forEach { (name, value) -> requestBuilder.addHeader(name, value) }
        webSocket = httpClient.newWebSocket(requestBuilder.build(), socketListener)
        return serverInfoReceived
    }

    fun close() {
        webSocket?.close(1000, null)
    }

    suspend fun commission(code: String): JSONObject =
        sendCommand("commission_with_code", JSONObject().put("code", code))

    suspend fun listNodes(): JSONArray {
        val response = sendCommand("get_nodes", JSONObject().put("only_available", false))
        return response.optJSONArray("result") ?: JSONArray()
    }

    private suspend fun sendCommand(command: String, args: JSONObject): JSONObject {
        val socket = webSocket ?: throw IllegalStateException("Matter socket not connected")
        val messageId = "poc-${messageCounter.incrementAndGet()}"
        val deferred = CompletableDeferred<JSONObject>()
        pendingResponses[messageId] = deferred
        socket.send(
            JSONObject()
                .put("message_id", messageId)
                .put("command", command)
                .put("args", args)
                .toString()
        )
        return deferred.await()
    }

    private val socketListener = object : WebSocketListener() {

        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = JSONObject(text)
            when {
                json.has("fabric_id") -> {
                    serverInfo = json
                    log(
                        "Matter server connected: schema ${json.optInt("schema_version")}, " +
                            "bluetooth=${json.optBoolean("bluetooth_enabled")}, " +
                            "thread_credentials=${json.optBoolean("thread_credentials_set")}"
                    )
                    if (!json.optBoolean("thread_credentials_set")) {
                        log("WARNING: no Thread dataset on the server; Thread commissioning will fail")
                    }
                    serverInfoReceived.complete(Unit)
                }
                json.has("message_id") ->
                    pendingResponses.remove(json.getString("message_id"))?.complete(json)
            }
        }

        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
            log("Matter socket failure: ${error.message}")
            if (!serverInfoReceived.isCompleted) serverInfoReceived.completeExceptionally(error)
            pendingResponses.values.forEach { it.completeExceptionally(error) }
            pendingResponses.clear()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            log("Matter socket closed ($code)")
        }
    }
}
