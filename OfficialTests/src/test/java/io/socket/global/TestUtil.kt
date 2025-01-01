package io.socket.global

import com.piasy.kmp.socketio.engineio.transports.DefaultHttpClientFactory
import com.piasy.kmp.socketio.engineio.transports.DefaultTransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

object TestUtil {
    @JvmStatic
    fun testScope() = CoroutineScope(Dispatchers.Default.limitedParallelism(1, "test"))

    @JvmStatic
    fun transportFactory() = DefaultTransportFactory

    @JvmStatic
    fun httpFactory() = DefaultHttpClientFactory
}
