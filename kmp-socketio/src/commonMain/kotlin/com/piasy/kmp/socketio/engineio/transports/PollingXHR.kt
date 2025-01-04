package com.piasy.kmp.socketio.engineio.transports

import com.piasy.kmp.socketio.engineio.IoThread
import com.piasy.kmp.socketio.engineio.State
import com.piasy.kmp.socketio.engineio.Transport
import com.piasy.kmp.socketio.engineio.WorkThread
import com.piasy.kmp.socketio.logging.Logger
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.hildan.socketio.EngineIO
import org.hildan.socketio.EngineIOPacket
import org.hildan.socketio.SocketIO
import org.hildan.socketio.SocketIOPacket

open class PollingXHR(
    opt: Options,
    scope: CoroutineScope,
    private val ioScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val factory: HttpClientFactory = DefaultHttpClientFactory,
) : Transport(opt, scope, NAME) {
    private var polling = false

    @WorkThread
    override fun pause(onPause: () -> Unit) {
        Logger.info(TAG, "pause")
        state = State.PAUSED
        val paused = {
            Logger.info(TAG, "paused")
            state = State.PAUSED
            onPause()
        }

        if (polling || !writable) {
            var counter = 0
            val waitJob: (String, String) -> Unit = { event, job ->
                Logger.info(TAG, "pause: wait $job")
                counter++
                once(event, object : Listener {
                    override fun call(vararg args: Any) {
                        Logger.info(TAG, "pause: pre-pause $job complete")
                        counter--
                        if (counter == 0) {
                            paused()
                        }
                    }
                })
            }

            if (polling) {
                waitJob(EVENT_POLL_COMPLETE, "polling")
            }
            if (!writable) {
                waitJob(EVENT_DRAIN, "writing")
            }
        } else {
            paused()
        }
    }

    @WorkThread
    override fun doOpen() {
        poll()
    }

    @WorkThread
    private fun poll() {
        Logger.debug(TAG, "poll start")
        polling = true

        val method = HttpMethod.Get
        val headers = prepareRequestHeaders(method)
        ioScope.launch {
            doRequest(uri(), method, headers, onResponse = { onPollComplete(it) })
        }
        emit(EVENT_POLL)
    }

    @WorkThread
    private fun prepareRequestHeaders(method: HttpMethod): Map<String, List<String>> {
        val requestHeaders = HashMap<String, List<String>>()
        requestHeaders.putAll(opt.extraHeaders)
        if (method == HttpMethod.Post) {
            requestHeaders["Content-type"] = listOf("text/plain;charset=UTF-8")
        }
        requestHeaders["Accept"] = listOf("*/*")
        emit(EVENT_REQUEST_HEADERS, requestHeaders)
        return requestHeaders
    }

    @IoThread
    private suspend fun doRequest(
        uri: String,
        method: HttpMethod,
        requestHeaders: Map<String, List<String>>,
        data: String? = null,
        onResponse: (String) -> Unit = {},
        onSuccess: () -> Unit = {},
    ) {
        Logger.debug(TAG, "doRequest ${method.value} $uri, data $data, headers $requestHeaders")
        val resp = factory.httpRequest(uri) {
            this.method = method

            headers {
                putHeaders(this, requestHeaders)
            }

            if (data != null) {
                setBody(data)
            }
        }

        Logger.debug(TAG, "doRequest response: ${resp.status}")
        scope.launch {
            emit(EVENT_RESPONSE_HEADERS, resp.headers.toMap())
        }
        if (resp.status.isSuccess()) {
            val body = resp.bodyAsText()
            scope.launch {
                onResponse(body)
                onSuccess()
            }
        } else {
            scope.launch {
                onError("HTTP error: ${resp.status}")
            }
        }
    }

    @WorkThread
    private fun onPollComplete(data: String) {
        Logger.debug(TAG, "onPollComplete: state $state, `$data`")
        val packets = if (stringMessagePayloadForTesting) {
            EngineIO.decodeHttpBatch(data, deserializeTextPayload = { it })
        } else {
            EngineIO.decodeHttpBatch(data, SocketIO::decode)
        }
        for (pkt in packets) {
            if ((state == State.OPENING || state == State.CLOSING) && pkt is EngineIOPacket.Open) {
                onOpen()
            }

            if (pkt is EngineIOPacket.Close) {
                onClose()
                break
            }

            onPacket(pkt)
        }

        if (state != State.CLOSED) {
            polling = false
            emit(EVENT_POLL_COMPLETE)

            if (state == State.OPEN) {
                poll()
            } else {
                Logger.info(TAG, "onPollComplete ignore poll, state $state")
            }
        }
    }

    @WorkThread
    override fun doSend(packets: List<EngineIOPacket<*>>) {
        writable = false
        @Suppress("UNCHECKED_CAST")
        val data = if (stringMessagePayloadForTesting) {
            EngineIO.encodeHttpBatch(
                packets as List<EngineIOPacket<String>>,
                serializePayload = { it }
            )
        } else {
            EngineIO.encodeHttpBatch(
                packets as List<EngineIOPacket<SocketIOPacket>>,
                SocketIO::encode
            )
        }

        val method = HttpMethod.Post
        val headers = prepareRequestHeaders(method)
        ioScope.launch {
            doRequest(uri(), method, headers, data) {
                scope.launch {
                    writable = true
                    emit(EVENT_DRAIN, packets.size)
                }
            }
        }
    }

    @WorkThread
    override fun doClose(fromOpenState: Boolean) {
        val onClose: () -> Unit = {
            Logger.info(TAG, "doClose writing close packet")
            once(EVENT_DRAIN, object : Listener {
                override fun call(vararg args: Any) {
                    onClose()
                }
            })
            doSend(listOf(EngineIOPacket.Close))
        }

        if (fromOpenState) {
            Logger.info(TAG, "doClose on OPEN state, closing")
            onClose()
        } else {
            Logger.info(TAG, "doClose on OPENING state, deferring close")
            once(EVENT_OPEN, object : Listener {
                override fun call(vararg args: Any) {
                    onClose()
                }
            })
        }
    }

    @WorkThread
    protected open fun uri() = uri(SECURE_SCHEMA, INSECURE_SCHEMA)

    companion object {
        const val NAME = "polling"
        const val SECURE_SCHEMA = "https"
        const val INSECURE_SCHEMA = "http"

        const val EVENT_POLL = "poll"
        const val EVENT_POLL_COMPLETE = "pollComplete"

        private const val TAG = "PollingXHR"
    }
}
