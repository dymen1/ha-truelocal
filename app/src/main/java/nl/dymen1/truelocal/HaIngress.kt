package nl.dymen1.truelocal

object HaIngress {

    data class IngressAccess(val webSocketBase: String, val headers: Map<String, String>)

    fun buildAccess(
        homeAssistantUrl: String,
        ingressPath: String,
        session: String,
        token: String
    ): IngressAccess {
        val base = homeAssistantUrl.trimEnd('/')
        val webSocketBase = (base + ingressPath).replaceFirst("http", "ws")
        return IngressAccess(
            webSocketBase,
            mapOf(
                "Cookie" to "ingress_session=$session",
                "Authorization" to "Bearer $token"
            )
        )
    }
}
