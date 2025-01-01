package com.piasy.kmp.socketio.engineio.transports

import com.piasy.kmp.socketio.engineio.IoThread
import com.piasy.kmp.socketio.engineio.State
import com.piasy.kmp.socketio.engineio.Transport
import com.piasy.kmp.socketio.engineio.WorkThread
import com.piasy.kmp.socketio.logging.Logger
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.util.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.hildan.socketio.EngineIO
import org.hildan.socketio.EngineIOPacket
import org.hildan.socketio.SocketIOPacket

open class WebSocket(
    opt: Options,
    scope: CoroutineScope,
    private val ioScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val factory: HttpClientFactory = DefaultHttpClientFactory,
) : Transport(opt, scope, NAME) {
    private var ws: DefaultClientWebSocketSession? = null

    @WorkThread
    override suspend fun doOpen() {
        val uri = uri()
        Logger.info(TAG, "doOpen $uri")

        val requestHeaders = HashMap<String, List<String>>()
        requestHeaders.putAll(opt.extraHeaders)
        emit(EVENT_REQUEST_HEADERS, requestHeaders)

        ioScope.launch {
            ws = factory.createWs(uri) {
                headers {
                    putHeaders(this, requestHeaders)
                }
            }

            val respHeaders = ws?.call?.response?.headers?.toMap()
            scope.launch {
                if (respHeaders != null) {
                    emit(EVENT_RESPONSE_HEADERS, respHeaders)
                }
                onOpen()
            }

            listen()
        }
    }

    @WorkThread
    protected open fun uri() = uri(SECURE_SCHEMA, INSECURE_SCHEMA)

    @IoThread
    private suspend fun listen() {
        while (true) {
            try {
                val frame = ws?.incoming?.receive() ?: break
                Logger.debug(TAG, "Receive frame: ${frame.frameType}")
                when (frame) {
                    is Frame.Text -> {
                        scope.launch { onData(frame.readText()) }
                    }

                    is Frame.Binary -> {
                        scope.launch { onData(frame.readBytes()) }
                    }

                    is Frame.Close -> {
                        Logger.info(TAG, "Received Close frame")
                        break
                    }

                    else -> {
                        Logger.info(TAG, "Received unknown frame")
                    }
                }
            } catch (e: Exception) {
                Logger.error(TAG, "Receive error while reading websocket frame: `${e.message}`")
                break
            }
        }
        scope.launch { onClose() }
    }

    @WorkThread
    override suspend fun doSend(packets: List<EngineIOPacket<*>>) {
        Logger.debug(TAG, "doSend ${packets.size} packets start")
        writable = false

        ioScope.launch {
            for (pkt in packets) {
                if (state != State.OPEN) {
                    // Ensure we don't try to send anymore packets
                    // if the socket ends up being closed due to an exception
                    break
                }
                try {
                    // TODO: binary
                    @Suppress("UNCHECKED_CAST")
                    val data = EngineIO.encodeSocketIO(
                        pkt as EngineIOPacket<SocketIOPacket>
                    )
                    Logger.debug(TAG, "doSend: `$data`")
                    ws?.send(data)
                } catch (e: Exception) {
                    Logger.error(TAG, "doSend error: `${e.message}`")
                    //break
                }
            }

            scope.launch {
                Logger.debug(TAG, "doSend ${packets.size} packets finish")
                writable = true
                emit(EVENT_DRAIN, packets.size)
            }
        }
    }

    @WorkThread
    override suspend fun doClose(fromOpenState: Boolean) {
        Logger.info(TAG, "doClose")
        ioScope.launch {
            ws?.close()
        }
    }

    companion object {
        const val NAME = "websocket"
        const val SECURE_SCHEMA = "wss"
        const val INSECURE_SCHEMA = "ws"

        private const val TAG = "WebSocket"
    }
}
