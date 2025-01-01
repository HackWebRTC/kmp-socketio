package com.piasy.kmp.socketio.engineio.transports

import com.piasy.kmp.socketio.engineio.State
import com.piasy.kmp.socketio.engineio.Transport
import com.piasy.kmp.socketio.engineio.WorkThread
import com.piasy.kmp.socketio.logging.Logger
import io.ktor.client.request.*
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
    private var ws: WebSocketSession? = null

    @WorkThread
    override suspend fun doOpen() {
        val uri = uri()
        Logger.info(TAG, "doOpen $uri")

        ws = factory.createWs(uri) {
            headers {
                append("Accept", "*/*")
                opt.extraHeaders.forEach { append(it.key, it.value.toString()) }
            }
        }
        onOpen()

        listen()
    }

    @WorkThread
    protected open fun uri() = uri(SECURE_SCHEMA, INSECURE_SCHEMA)

    private fun listen() {
        ioScope.launch {
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
    }

    @WorkThread
    override suspend fun doSend(packets: List<EngineIOPacket<*>>) {
        Logger.debug(TAG, "doSend ${packets.size} packets start")
        writable = false

        ioScope.launch {
            for (pkt in packets) {
                if (state != State.OPENING && state != State.OPEN) {
                    // Ensure we don't try to send anymore packets
                    // if the socket ends up being closed due to an exception
                    break
                }
                try {
                    // TODO: binary
                    @Suppress("UNCHECKED_CAST")
                    val msg = EngineIO.encodeSocketIO(
                        pkt as EngineIOPacket<SocketIOPacket>
                    )
                    Logger.debug(TAG, "doSend: `$msg`")
                    ws?.send(msg)
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
    override suspend fun doClose() {
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
