package com.piasy.kmp.socketio.engineio.transports

import com.piasy.kmp.socketio.engineio.Transport
import com.piasy.kmp.socketio.logging.Logger
import io.ktor.client.HttpClientConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope

expect fun httpClient(config: HttpClientConfig<*>.() -> Unit = {}): HttpClient

internal fun putHeaders(
    builder: HeadersBuilder,
    headers: Map<String, List<String>>
) {
    headers.forEach {
        it.value.forEach { v ->
            builder.append(it.key, v)
        }
    }
}

interface TransportFactory {
    fun create(
        name: String,
        opt: Transport.Options,
        scope: CoroutineScope
    ): Transport
}

object DefaultTransportFactory : TransportFactory {
    override fun create(
        name: String,
        opt: Transport.Options,
        scope: CoroutineScope
    ) = when (name) {
        WebSocket.NAME -> WebSocket(opt, scope)
        PollingXHR.NAME -> PollingXHR(opt, scope)
        else -> throw RuntimeException("Bad transport name: $name")
    }
}

interface HttpClientFactory {
    suspend fun createWs(
        url: String,
        block: HttpRequestBuilder.() -> Unit
    ): DefaultClientWebSocketSession

    suspend fun httpRequest(
        url: String,
        block: HttpRequestBuilder.() -> Unit
    ): HttpResponse
}

object DefaultHttpClientFactory : HttpClientFactory {
    private val client = httpClient {
        install(Logging) {
            logger = object : io.ktor.client.plugins.logging.Logger {
                override fun log(message: String) {
                    Logger.info("Net", message)
                }
            }
            level = LogLevel.ALL
        }
        install(WebSockets) {
            pingIntervalMillis = 20_000
        }
    }

    override suspend fun createWs(
        url: String,
        block: HttpRequestBuilder.() -> Unit
    ) = client.webSocketSession(url, block)

    override suspend fun httpRequest(
        url: String,
        block: HttpRequestBuilder.() -> Unit
    ) = client.request(url, block)
}
