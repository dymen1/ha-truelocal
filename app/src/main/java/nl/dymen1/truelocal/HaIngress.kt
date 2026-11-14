package nl.dymen1.truelocal

object HaIngress {

    enum class Mode { INGRESS, DIRECT }

    data class MatterAccess(
        val webSocketBase: String,
        val headers: Map<String, String>,
        val mode: Mode
    )

    fun buildAccess(
        homeAssistantUrl: String,
        ingressPath: String,
        session: String,
        token: String
    ): MatterAccess {
        val base = homeAssistantUrl.trimEnd('/')
        val webSocketBase = (base + ingressPath).replaceFirst("http", "ws")
        return MatterAccess(
            webSocketBase,
            mapOf(
                "Cookie" to "ingress_session=$session",
                "Authorization" to "Bearer $token"
            ),
            Mode.INGRESS
        )
    }

    fun directAccess(matterServerUrl: String): MatterAccess =
        MatterAccess(normalizeBaseUrl(matterServerUrl), emptyMap(), Mode.DIRECT)

    /**
     * Accepts what people actually paste: with or without a scheme, with a trailing slash, or
     * with the /ws path of the Matter server. The result is always an http(s) base URL without
     * trailing slash; the websocket URL is derived from it.
     */
    fun normalizeBaseUrl(raw: String): String {
        var base = raw.trim().trimEnd('/')
        if (base.endsWith("/ws")) base = base.removeSuffix("/ws").trimEnd('/')
        if (!base.contains("://")) base = "http://$base"
        return base
            .replaceFirst("wss://", "https://")
            .replaceFirst("ws://", "http://")
            .trimEnd('/')
    }
}
