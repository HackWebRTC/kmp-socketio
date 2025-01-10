package com.piasy.kmp.socketio.engineio.transports

import com.piasy.kmp.socketio.engineio.IoThread
import com.piasy.kmp.socketio.engineio.State
import com.piasy.kmp.socketio.engineio.Transport
import com.piasy.kmp.socketio.engineio.WorkThread
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
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
    override fun pause(onPause: () -> Unit) {
        // ws don't need to pause
    }

    @WorkThread
    override fun doOpen() {
        val uri = uri()
        logI("doOpen $uri")

        val requestHeaders = HashMap<String, List<String>>()
        requestHeaders.putAll(opt.extraHeaders)
        emit(EVENT_REQUEST_HEADERS, requestHeaders)

        ioScope.launch {
            try {
                factory.createWs(uri, {
                    headers {
                        putHeaders(this, requestHeaders)
                    }
                }) {
                    ws = this
                    val respHeaders = call.response.headers.toMap()
                    scope.launch {
                        emit(EVENT_RESPONSE_HEADERS, respHeaders)
                        onOpen()
                    }

                    listen()
                }
            } catch (e: Exception) {
                scope.launch { onError("ws exception: ${e.message}") }
            }
        }
    }

    @WorkThread
    protected open fun uri() = uri(SECURE_SCHEMA, INSECURE_SCHEMA)

    @IoThread
    private suspend fun listen() {
        while (true) {
            try {
                val frame = ws?.incoming?.receive() ?: break
                logD("Receive frame: $frame")
                when (frame) {
                    is Frame.Text -> {
                        scope.launch { onWsData(frame.readText()) }
                    }

                    is Frame.Binary -> {
                        scope.launch { onWsData(frame.readBytes()) }
                    }

                    is Frame.Close -> {
                        logI("Received Close frame")
                        break
                    }

                    else -> {
                        logI("Received unknown frame")
                    }
                }
            } catch (e: Exception) {
                logE("Receive error while reading websocket frame: `${e.message}`")
                break
            }
        }
        scope.launch { onClose() }
    }

    @WorkThread
    override fun doSend(packets: List<EngineIOPacket<*>>) {
        logD("doSend ${packets.size} packets start")
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
                    val data = if (stringMessagePayloadForTesting) {
                        EngineIO.encodeWsFrame(pkt as EngineIOPacket<String>, serializePayload = { it })
                    } else {
                        EngineIO.encodeSocketIO(
                            pkt as EngineIOPacket<SocketIOPacket>
                        )
                    }
                    logD("doSend: $pkt, `$data`")
                    ws?.send(data)
                } catch (e: Exception) {
                    logE("doSend error: `${e.message}`")
                    //break
                }
            }

            scope.launch {
                logD("doSend ${packets.size} packets finish")
                writable = true
                emit(EVENT_DRAIN, packets.size)
            }
        }
    }

    @WorkThread
    override fun doClose(fromOpenState: Boolean) {
        logI("doClose")
        ioScope.launch {
            ws?.close()
        }
    }

    companion object {
        const val NAME = "websocket"
        const val SECURE_SCHEMA = "wss"
        const val INSECURE_SCHEMA = "ws"
    }
}
