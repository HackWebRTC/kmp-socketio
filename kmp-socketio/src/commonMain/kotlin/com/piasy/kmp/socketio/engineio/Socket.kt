package com.piasy.kmp.socketio.engineio

import com.piasy.kmp.socketio.emitter.Emitter
import com.piasy.kmp.socketio.engineio.transports.DefaultTransportFactory
import com.piasy.kmp.socketio.engineio.transports.PollingXHR
import com.piasy.kmp.socketio.engineio.transports.TransportFactory
import com.piasy.kmp.socketio.engineio.transports.WebSocket
import com.piasy.kmp.socketio.logging.Logger
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.hildan.socketio.EngineIOPacket
import org.hildan.socketio.SocketIOPacket
import kotlin.jvm.JvmField

class Socket(
    uri: String,
    @JvmField val opt: Options,
    private val scope: CoroutineScope,
    private val factory: TransportFactory = DefaultTransportFactory,
) : Emitter() {
    class Options : Transport.Options() {
        /**
         * List of transport names.
         */
        @JvmField
        var transports: List<String> = emptyList()

        /**
         * Whether to upgrade the transport. Defaults to `true`.
         */
        @JvmField
        var upgrade = true

        @JvmField
        var rememberUpgrade = false

        @JvmField
        var transportOptions: Map<String, Transport.Options> = emptyMap()
    }

    // to help unit test
    internal var disablePingTimeout = false
    private var state = State.INIT
    private var id = ""
    private var upgrades = emptyList<String>()
    private var pingInterval = 0
    private var pingTimeout = 0
    private var transport: Transport? = null
    private var upgrading = false

    private val writeBuffer = ArrayDeque<EngineIOPacket<*>>()
    private var prevBufferLen = 0

    private val heartbeatListener = object : Listener {
        override fun call(vararg args: Any) {
            onHeartBeat()
        }
    }
    private var pingTimeoutJob: Job? = null

    init {
        val url = Url(uri)
        opt.secure = url.protocol == URLProtocol.HTTPS
                || url.protocol == URLProtocol.WSS

        var hostname = url.host
        if (hostname.split(':').size > 2) {
            val start = hostname.indexOf('[')
            if (start != -1) {
                hostname = hostname.substring(start + 1)
            }
            val end = hostname.lastIndexOf(']')
            if (end != -1) {
                hostname = hostname.substring(0, end)
            }
        }
        opt.hostname = hostname
        opt.port = url.port

        if (opt.timestampParam.isEmpty()) {
            opt.timestampParam = "t"
        }

        if (!url.parameters.isEmpty()) {
            val query = HashMap<String, String>()
            url.parameters.entries().forEach {
                query[it.key] = it.value[0]
            }
            opt.query = query
        }

        if (opt.transports.isEmpty()) {
            opt.transports = listOf(PollingXHR.NAME, WebSocket.NAME)
        }
    }

    /**
     * Connects the client.
     *
     * @return a reference to this object.
     */
    @CallerThread
    fun open(): Socket {
        scope.launch {
            val name = if (opt.rememberUpgrade && priorWebsocketSuccess
                && opt.transports.contains(WebSocket.NAME)
            ) {
                WebSocket.NAME
            } else {
                // transports won't be empty
                opt.transports[0]
            }

            state = State.OPENING
            val transport = createTransport(name)
            setTransport(transport)
            transport.open()
        }
        return this
    }

    /**
     * Sends a packet.
     *
     * @param pkt
     */
    @CallerThread
    fun send(pkt: EngineIOPacket<*>) {
        Logger.debug(TAG, "send $pkt")
        scope.launch {
            sendPacket(pkt)
        }
    }

    /**
     * Disconnects the client.
     *
     * @return a reference to this object.
     */
    @CallerThread
    fun close(): Socket {
        scope.launch {
            Logger.info(TAG, "close: state $state, writeBuffer.size ${writeBuffer.size}, upgrading: $upgrading")
            if (state != State.OPENING && state != State.OPEN) {
                Logger.info(TAG, "close with wrong state: $state")
                return@launch
            }
            state = State.CLOSING

            val closeAction = {
                Logger.info(TAG, "socket closing - telling transport to close")
                onClose("force close")
                transport?.close()
            }
            val cleanupAndClose = object : Listener {
                override fun call(vararg args: Any) {
                    Logger.info(TAG, "close waiting upgrade success")
                    off(EVENT_UPGRADE, this)
                    off(EVENT_UPGRADE_ERROR, this)
                    closeAction()
                }
            }
            val waitForUpgrade = {
                Logger.info(TAG, "close waiting upgrade")
                // wait for upgrade to finish since we can't
                // send packets while pausing transport.
                once(EVENT_UPGRADE, cleanupAndClose)
                once(EVENT_UPGRADE_ERROR, cleanupAndClose)
            }

            if (writeBuffer.isNotEmpty()) {
                Logger.info(TAG, "close waiting drain event")
                once(EVENT_DRAIN, object : Listener {
                    override fun call(vararg args: Any) {
                        Logger.info(TAG, "close waiting drain success")
                        if (upgrading) {
                            waitForUpgrade()
                        } else {
                            closeAction()
                        }
                    }
                })
            } else if (upgrading) {
                waitForUpgrade()
            } else {
                closeAction()
            }
        }
        return this
    }

    @WorkThread
    private fun createTransport(name: String): Transport {
        Logger.info(TAG, "createTransport $name")
        val query = HashMap(opt.query)
        query["EIO"] = "4"
        query["transport"] = name
        if (id.isNotEmpty()) {
            query["sid"] = id
        }

        val options = opt.transportOptions[name]
        val opts = Transport.Options()
        opts.query = query

        opts.secure = options?.secure ?: opt.secure
        opts.hostname = options?.hostname ?: opt.hostname
        opts.port = options?.port ?: opt.port
        opts.path = options?.path ?: opt.path
        opts.timestampRequests = options?.timestampRequests ?: opt.timestampRequests
        opts.timestampParam = options?.timestampParam ?: opt.timestampParam
        opts.extraHeaders = opt.extraHeaders

        val transport = factory.create(name, opts, scope)
        emit(EVENT_TRANSPORT, transport)
        return transport
    }

    @WorkThread
    private fun setTransport(transport: Transport) {
        Logger.info(TAG, "setTransport ${transport.name}")
        val oldTransport = this.transport
        if (oldTransport != null) {
            Logger.info(TAG, "clearing existing transport ${transport.name}")
            oldTransport.off()
        }

        this.transport = transport

        transport.on(Transport.EVENT_DRAIN, object : Listener {
            override fun call(vararg args: Any) {
                if (args.isNotEmpty() && args[0] is Int) {
                    onDrain(args[0] as Int)
                } else {
                    Logger.error(TAG, "onDrain with wrong args: `${args.joinToString()}`")
                }
            }
        }).on(Transport.EVENT_PACKET, object : Listener {
            override fun call(vararg args: Any) {
                val packet = args.firstOrNull() ?: return
                Logger.debug(TAG, "transport on packet $packet")
                if (packet is EngineIOPacket<*>) {
                    @Suppress("UNCHECKED_CAST")
                    onPacket(packet as EngineIOPacket<SocketIOPacket>)
                }
            }
        }).on(Transport.EVENT_ERROR, object : Listener {
            override fun call(vararg args: Any) {
                val msg = args.firstOrNull() ?: ""
                if (msg is String) {
                    onError(msg)
                }
            }
        }).on(Transport.EVENT_CLOSE, object : Listener {
            override fun call(vararg args: Any) {
                onClose("transport close")
            }
        })
    }

    @WorkThread
    private fun onDrain(len: Int) {
        Logger.debug(TAG, "onDrain: prevBufferLen $prevBufferLen, writeBuffer.size ${writeBuffer.size}, len $len")
        for (i in 1..len) {
            writeBuffer.removeFirst()
        }
        prevBufferLen -= len

        if (writeBuffer.isEmpty()) {
            emit(EVENT_DRAIN)
        } else if (writeBuffer.size > prevBufferLen) {
            flush()
        }
    }

    @WorkThread
    private fun sendPacket(pkt: EngineIOPacket<*>) {
        Logger.debug(TAG, "sendPacket: state $state, pkt $pkt")
        if (state == State.CLOSING || state == State.CLOSED) {
            return
        }

        emit(EVENT_PACKET_CREATE, pkt)
        writeBuffer.addLast(pkt)
        flush()
    }

    @WorkThread
    private fun onPacket(packet: EngineIOPacket<SocketIOPacket>) {
        if (inactive()) {
            Logger.error(TAG, "packet received with wrong state $state, $packet")
            return
        }

        Logger.debug(TAG, "onPacket $packet")
        emit(EVENT_PACKET, packet)
        emit(EVENT_HEARTBEAT)

        when (packet) {
            is EngineIOPacket.Open -> {
                try {
                    onHandshake(packet)
                } catch (e: Exception) {
                    emit(EVENT_ERROR, "Error handling Open packet: ${e.message}")
                }
            }

            is EngineIOPacket.Ping -> {
                emit(EVENT_PING)
                sendPacket(EngineIOPacket.Pong(null))
            }

            is EngineIOPacket.Message<*> -> {
                val data = packet.payload
                if (data != null) {
                    emit(EVENT_DATA, data)
                    emit(EVENT_MESSAGE, data)
                }
            }

            else -> {}
        }
    }

    @WorkThread
    private fun onHandshake(pkt: EngineIOPacket.Open) {
        emit(EVENT_HANDSHAKE, pkt)
        id = pkt.sid
        transport?.opt?.query?.set("sid", id)
        upgrades = filterUpgrades(pkt.upgrades)
        pingInterval = pkt.pingInterval
        pingTimeout = pkt.pingTimeout
        onOpen()

        // In case open handler closes socket
        if (state == State.CLOSED) {
            return
        }

        onHeartBeat()
        off(EVENT_HEARTBEAT, heartbeatListener)
        on(EVENT_HEARTBEAT, heartbeatListener)
    }

    internal fun filterUpgrades(upgrades: List<String>): List<String> =
        upgrades.filter { opt.transports.contains(it) && it != transport?.name }

    @WorkThread
    private fun onOpen() {
        Logger.info(TAG, "onOpen")
        state = State.OPEN
        priorWebsocketSuccess = transport?.name == WebSocket.NAME
        emit(EVENT_OPEN)
        flush()

        if (state == State.OPEN && opt.upgrade && transport is PollingXHR) {
            Logger.info(TAG, "starting upgrade probes")
            for (upgrade in upgrades) {
                probe(upgrade)
            }
        }
    }

    @WorkThread
    private fun flush() {
        Logger.debug(
            TAG, "flush: state $state, writable ${transport?.writable}, " +
                    "upgrading $upgrading, prevBufferLen $prevBufferLen, " +
                    "writeBuffer.size ${writeBuffer.size}"
        )
        if (state != State.CLOSED
            && transport?.writable == true
            && !upgrading
            && writeBuffer.size > prevBufferLen
        ) {
            val packets = writeBuffer.subList(prevBufferLen, writeBuffer.size)
            prevBufferLen = writeBuffer.size
            transport?.send(ArrayList(packets))
            emit(EVENT_FLUSH)
        }
    }

    @WorkThread
    private fun probe(name: String) {
        Logger.info(TAG, "probing transport '$name'")
        val transport = createTransport(name)
        var failed = false
        priorWebsocketSuccess = false

        // TODO
    }

    @WorkThread
    private fun onHeartBeat() {
        if (disablePingTimeout) {
            return
        }
        pingTimeoutJob?.cancel()
        pingTimeoutJob = scope.launch {
            delay((pingInterval + pingTimeout).toLong())
            if (state != State.CLOSED) {
                onClose("ping timeout")
            }
        }
    }

    @WorkThread
    private fun onError(msg: String) {
        val log = "transport onError: `$msg`"
        Logger.error(TAG, log)
        priorWebsocketSuccess = false
        emit(EVENT_ERROR, msg)
        onClose(log)
    }

    @WorkThread
    private fun onClose(reason: String) {
        if (inactive()) {
            return
        }

        Logger.info(TAG, "onClose $reason")
        pingTimeoutJob?.cancel()

        // stop event from firing again for transport
        transport?.off(EVENT_CLOSE)
        // ensure transport won't stay open
        transport?.close()
        // ignore further transport communication
        transport?.off()

        state = State.CLOSED
        id = ""

        emit(EVENT_CLOSE, reason)

        // clear buffers after, so users can still
        // grab the buffers on `close` event
        writeBuffer.clear()
        prevBufferLen = 0
    }

    private fun inactive() = state != State.OPENING
            && state != State.OPEN
            && state != State.CLOSING

    companion object {
        private const val TAG = "EngineIOSocket"
        private var priorWebsocketSuccess = false


        /**
         * Called on successful connection.
         */
        const val EVENT_OPEN: String = "open"

        /**
         * Called on disconnection.
         */
        const val EVENT_CLOSE: String = "close"

        /**
         * Called when data is received from the server.
         */
        const val EVENT_MESSAGE: String = "message"

        /**
         * Called when an error occurs.
         */
        const val EVENT_ERROR: String = "error"


        const val EVENT_UPGRADE_ERROR: String = "upgradeError"

        /**
         * Called on completing a buffer flush.
         */
        const val EVENT_FLUSH: String = "flush"

        /**
         * Called after `drain` event of transport if writeBuffer is empty.
         */
        const val EVENT_DRAIN: String = "drain"


        const val EVENT_HANDSHAKE: String = "handshake"
        const val EVENT_UPGRADING: String = "upgrading"
        const val EVENT_UPGRADE: String = "upgrade"
        const val EVENT_PACKET: String = "packet"
        const val EVENT_PACKET_CREATE: String = "packetCreate"
        const val EVENT_HEARTBEAT: String = "heartbeat"
        const val EVENT_DATA: String = "data"
        const val EVENT_PING: String = "ping"

        /**
         * Called on new transport is created.
         */
        const val EVENT_TRANSPORT: String = "transport"

    }
}
