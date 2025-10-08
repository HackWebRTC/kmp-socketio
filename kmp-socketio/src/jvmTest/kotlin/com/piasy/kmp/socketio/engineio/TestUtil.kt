package com.piasy.kmp.socketio.engineio

import com.piasy.kmp.socketio.engineio.transports.DefaultHttpClientFactory
import com.piasy.kmp.socketio.engineio.transports.DefaultTransportFactory
import com.piasy.kmp.socketio.socketio.Manager
import com.piasy.kmp.socketio.socketio.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.json.JSONObject

object TestUtil {
    @JvmStatic
    fun testScope() = CoroutineScope(Dispatchers.Default.limitedParallelism(1))

    @JvmStatic
    fun transportFactory() = DefaultTransportFactory

    @JvmStatic
    fun httpFactory() = DefaultHttpClientFactory()

    @JvmStatic
    fun getOpt(socket: EngineSocket) = socket.opt

    @JvmStatic
    fun triggerTransportError(socket: EngineSocket, error: String) {
        socket.transport?.onError(error)
    }

    @JvmStatic
    fun writeBufferSize(socket: EngineSocket): Int {
        return socket.writeBuffer.size
    }

    @JvmStatic
    fun transportName(socket: EngineSocket) = socket.transport?.name

    @JvmStatic
    fun socket(manager: Manager, nsp: String): Socket {
        return manager.socket(nsp, emptyMap())
    }

    @JvmStatic
    fun closeManager(manager: Manager) {
        manager.close()
    }

    @JvmStatic
    fun closeEngineSocket(socket: Socket) {
        socket.io.engine?.scope?.launch {
            socket.io.engine?.close()
        }
    }

    @JvmStatic
    fun engineId(socket: Socket) = socket.io.engine?.id

    @JvmStatic
    fun engineSocket(socket: Socket) = socket.io.engine

    @JvmStatic
    fun jsonBool(json: JsonObject, key: String): Boolean? {
        return json[key]?.jsonPrimitive?.boolean
    }

    @JvmStatic
    fun toJSON(obj: Any): JSONObject {
        return JSONObject(Json.encodeToString<JsonObject>(obj as JsonObject))
    }
}
