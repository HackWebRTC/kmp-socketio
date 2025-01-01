package com.piasy.kmp.socketio.engineio.transports

import com.piasy.kmp.socketio.engineio.Transport
import com.piasy.kmp.socketio.logging.Logger
import io.ktor.client.HttpClientConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope

expect fun httpClient(config: HttpClientConfig<*>.() -> Unit = {}): HttpClient

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
    ): WebSocketSession
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
}
