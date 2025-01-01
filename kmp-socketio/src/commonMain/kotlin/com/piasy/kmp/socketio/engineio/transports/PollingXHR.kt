package com.piasy.kmp.socketio.engineio.transports

import com.piasy.kmp.socketio.engineio.Transport
import com.piasy.kmp.socketio.engineio.WorkThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.hildan.socketio.EngineIOPacket

open class PollingXHR(
    opt: Options,
    scope: CoroutineScope,
    private val ioScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val factory: HttpClientFactory = DefaultHttpClientFactory,
) : Transport(opt, scope, NAME) {

    @WorkThread
    override suspend fun doOpen() {
        TODO("Not yet implemented")
    }

    @WorkThread
    override suspend fun doSend(packets: List<EngineIOPacket<*>>) {
        TODO("Not yet implemented")
    }

    @WorkThread
    override suspend fun doClose() {
        TODO("Not yet implemented")
    }

    @WorkThread
    protected open fun uri() = uri(SECURE_SCHEMA, INSECURE_SCHEMA)

    companion object {
        const val NAME = "polling"
        const val SECURE_SCHEMA = "https"
        const val INSECURE_SCHEMA = "http"

        private const val TAG = "PollingXHR"
    }
}
