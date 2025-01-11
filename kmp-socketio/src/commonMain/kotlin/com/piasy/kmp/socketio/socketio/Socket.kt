package com.piasy.kmp.socketio.socketio

import com.piasy.kmp.socketio.emitter.Emitter
import com.piasy.kmp.socketio.engineio.*
import com.piasy.kmp.socketio.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.hildan.socketio.EngineIOPacket
import org.hildan.socketio.SocketIOPacket

class Socket(
    val io: Manager,
    val nsp: String,
    private val auth: Map<String, String>,
    private val scope: CoroutineScope,
) : Emitter() {
    private var connected = false
    private val subs = ArrayList<On.Handle>()
    private val ack = HashMap<Int, Ack>()
    private var ackId = 0

    private val sendBuffer = ArrayList<SocketIOPacket.Event>()
    private val recvBuffer = ArrayList<ArrayList<Any>>()

    var id = ""
        private set

    /**
     * Connects the socket.
     */
    @CallerThread
    fun open() {
        scope.launch {
            Logger.info(TAG, "open: connected $connected, io reconnecting ${io.reconnecting}")
            if (connected || io.reconnecting) {
                return@launch
            }

            subEvents()
            io.open()
            if (io.state == State.OPEN) {
                onOpen()
            }
        }
    }

    /**
     * Disconnects the socket.
     */
    @CallerThread
    fun close() {
        scope.launch {
            Logger.info(TAG, "close: connected $connected")
            if (connected) {
                packet(SocketIOPacket.Disconnect(nsp))
            }
            destroy()
            if (connected) {
                onClose("io client disconnect")
            }
        }
    }

    @CallerThread
    fun send(vararg args: Any): Socket {
        return emit(EVENT_MESSAGE, *args) as Socket
    }

    @CallerThread
    override fun emit(event: String, vararg args: Any): Emitter {
        if (RESERVED_EVENTS.contains(event)) {
            val log = "emit reserved event: $event"
            Logger.error(TAG, log)
            throw RuntimeException(log)
        }
        scope.launch {
            if (args.isNotEmpty() && args.last() is Ack) {
                val arr = Array(args.size - 1) {
                    args[it]
                }
                emitWithAck(event, arr, args.last() as Ack)
            } else {
                emitWithAck(event, args, null)
            }
        }
        return this
    }

    @WorkThread
    private fun emitWithAck(event: String, args: Array<out Any>, ack: Ack?) {
        Logger.debug(TAG, "emitWithAck: $event, ${args.joinToString()}, ack $ack")
        val ackId = if (ack != null) this.ackId else null
        val packet = SocketIOPacket.Event(nsp, ackId, buildJsonArray {
            add(JsonPrimitive(event))
            args.forEach { addPrimitive(it) }
        })

        if (ack != null && ackId != null) {
            Logger.info(TAG, "emit with ack id $ackId")
            if (ack is AckWithTimeout) {
                ack.schedule(scope) {
                    // remove the ack from the map (to prevent an actual acknowledgement)
                    this.ack.remove(ackId)
                    // remove the packet from the buffer (if applicable)
                    val iterator = sendBuffer.iterator()
                    while (iterator.hasNext()) {
                        if (iterator.next().ackId == ackId) {
                            iterator.remove()
                            break
                        }
                    }
                }
            }

            this.ack[ackId] = ack
            this.ackId++
        }

        if (connected) {
            packet(packet)
        } else {
            sendBuffer.add(packet)
        }
    }

    @WorkThread
    private fun destroy() {
        for (sub in subs) {
            sub.destroy()
        }
        subs.clear()
        io.destroy()
    }

    @WorkThread
    private fun subEvents() {
        Logger.info(TAG, "subEvents: subs ${subs.size}")
        if (subs.isNotEmpty()) {
            return
        }
        subs.add(On.on(io, Manager.EVENT_OPEN, object : Listener {
            override fun call(vararg args: Any) {
                onOpen()
            }
        }))
        subs.add(On.on(io, Manager.EVENT_PACKET, object : Listener {
            override fun call(vararg args: Any) {
                if (args.isNotEmpty() && args[0] is SocketIOPacket) {
                    onPacket(args[0] as SocketIOPacket)
                }
            }
        }))
        subs.add(On.on(io, Manager.EVENT_ERROR, object : Listener {
            override fun call(vararg args: Any) {
                onManagerError(if (args.isNotEmpty() && args[0] is String) args[0] as String else "Manager error")
            }
        }))
        subs.add(On.on(io, Manager.EVENT_CLOSE, object : Listener {
            override fun call(vararg args: Any) {
                onClose(if (args.isNotEmpty() && args[0] is String) args[0] as String else "Manager close")
            }
        }))
    }

    @WorkThread
    private fun onOpen() {
        Logger.info(TAG, "onOpen")
        val auth = if (auth.isEmpty()) {
            null
        } else {
            Json.encodeToJsonElement(auth) as JsonObject
        }
        packet(SocketIOPacket.Connect(nsp, auth))
    }

    @WorkThread
    private fun packet(packet: SocketIOPacket) {
        io.packet(EngineIOPacket.Message(packet))
    }

    @WorkThread
    private fun onPacket(packet: SocketIOPacket) {
        Logger.debug(TAG, "onPacket: nsp $nsp, $packet")
        if (nsp != packet.namespace) {
            return
        }

        when (packet) {
            is SocketIOPacket.Connect -> {
                val sid = packet.payload?.get(EngineSocket.SID)
                if (sid is JsonPrimitive) {
                    onConnect(sid.content)
                }
            }

            is SocketIOPacket.Disconnect -> onDisconnect()
            is SocketIOPacket.ConnectError -> {
                destroy()
                val data = packet.errorData ?: JsonObject(emptyMap())
                super.emit(EVENT_CONNECT_ERROR, data)
            }

            is SocketIOPacket.Event -> onEvent(packet)
            is SocketIOPacket.Ack -> onAck(packet)
            is SocketIOPacket.BinaryEvent -> {}
            is SocketIOPacket.BinaryAck -> {}
        }
    }

    @WorkThread
    private fun onConnect(id: String) {
        Logger.info(TAG, "onConnect sid $id")
        connected = true
        this.id = id

        for (data in recvBuffer) {
            fireEvent(data)
        }

        for (event in sendBuffer) {
            packet(event)
        }
        sendBuffer.clear()

        super.emit(EVENT_CONNECT)
    }

    @WorkThread
    private fun onDisconnect() {
        Logger.info(TAG, "onDisconnect")
        destroy()
        onClose("io server disconnect")
    }

    @WorkThread
    private fun onEvent(event: SocketIOPacket.Event) {
        Logger.debug(TAG, "onEvent $event")
        val data = ArrayList<Any>(event.payload)
        val eventId = event.ackId
        if (eventId != null) {
            Logger.debug(TAG, "attaching ack callback to event")
            data.add(createAck(eventId))
        }
        if (data.isEmpty()) {
            return
        }
        if (connected) {
            fireEvent(data)
        } else {
            recvBuffer.add(data)
        }
    }

    @WorkThread
    private fun fireEvent(data: ArrayList<Any>) {
        val ev = when (val event = data.removeFirst()) {
            is String -> event
            is JsonPrimitive -> event.content
            else -> throw RuntimeException("bad event $event")
        }
        val args = Array(data.size) {
            if (data[it] is JsonElement) {
                (data[it] as JsonElement).getPrimitive()
            } else {
                data[it]
            }
        }
        super.emit(ev, *args)
    }

    @WorkThread
    private fun createAck(ackId: Int) = object : Ack {
        private var sent = false

        override fun call(vararg args: Any) {
            scope.launch {
                if (sent) {
                    return@launch
                }
                sent = true
                Logger.info(TAG, "sending ack: id $ackId ${args.joinToString()}")
                val packet = SocketIOPacket.Ack(nsp, ackId, buildJsonArray {
                    args.forEach { addPrimitive(it) }
                })
                packet(packet)
            }
        }
    }

    @WorkThread
    private fun onAck(ack: SocketIOPacket.Ack) {
        val fn = this.ack.remove(ack.ackId)
        if (fn != null) {
            Logger.info(TAG, "calling ack ${ack.ackId} with ${ack.payload}")
            val args = Array(ack.payload.size) {
                ack.payload[it].getPrimitive()
            }
            fn.call(*args)
        } else {
            Logger.info(TAG, "bad ack ${ack.ackId}")
        }
    }

    @WorkThread
    private fun onManagerError(error: String) {
        Logger.error(TAG, "onManagerError: `$error`")
        if (!connected) {
            super.emit(EVENT_CONNECT_ERROR, error)
        }
    }

    @WorkThread
    private fun onClose(reason: String) {
        Logger.info(TAG, "onClose: `$reason`")
        connected = false
        id = ""
        super.emit(EVENT_DISCONNECT, reason)
        clearAck()
    }

    /**f
     * Clears the acknowledgement handlers upon disconnection,
     * since the client will never receive an acknowledgement from
     * the server.
     */
    @WorkThread
    private fun clearAck() {
        for (ack in this.ack.values) {
            if (ack is AckWithTimeout) {
                ack.onTimeout()
            }
            // note: basic Ack objects have no way to report an error,
            // so they are simply ignored here
        }
        ack.clear()
    }

    @WorkThread
    internal fun active() = subs.isNotEmpty()

    companion object {
        private const val TAG = "Socket"

        /**
         * Called on a connection.
         */
        const val EVENT_CONNECT = "connect"

        /**
         * Called on a disconnection.
         */
        const val EVENT_DISCONNECT = "disconnect"

        /**
         * Called on a connection error.
         *
         *
         * Parameters:
         *
         *  * (Exception) error data.
         *
         */
        const val EVENT_CONNECT_ERROR = "connect_error"

        const val EVENT_MESSAGE = "message"

        private val RESERVED_EVENTS = setOf(
            EVENT_CONNECT,
            EVENT_CONNECT_ERROR,
            EVENT_DISCONNECT,
            // used on the server-side
            "disconnecting",
            "newListener",
            "removeListener",
        )
    }
}

private fun JsonArrayBuilder.addPrimitive(primitive: Any) {
    when (primitive) {
        is String -> {
            add(JsonPrimitive(primitive))
        }

        is Boolean -> {
            add(JsonPrimitive(primitive))
        }

        is Number -> {
            add(JsonPrimitive(primitive))
        }

        is JsonElement -> {
            add(primitive)
        }

        else -> add(primitive.toString())
    }
}

private fun JsonElement.getPrimitive(): Any {
    return when (this) {
        is JsonPrimitive -> {
            return when {
                isString -> content
                this is JsonNull -> "null"
                else -> {
                    val boolVal = jsonPrimitive.booleanOrNull
                    if (boolVal != null) {
                        return boolVal
                    }
                    val intVal = jsonPrimitive.intOrNull
                    if (intVal != null) {
                        return intVal
                    }
                    val longVal = jsonPrimitive.longOrNull
                    if (longVal != null) {
                        return longVal
                    }
                    val floatVal = jsonPrimitive.floatOrNull
                    if (floatVal != null) {
                        return floatVal
                    }
                    val doubleVal = jsonPrimitive.doubleOrNull
                    if (doubleVal != null) {
                        return doubleVal
                    }
                    Logger.error("JSON", "bad json primitive $this")
                    return 0
                }
            }
        }

        else -> this
    }
}
