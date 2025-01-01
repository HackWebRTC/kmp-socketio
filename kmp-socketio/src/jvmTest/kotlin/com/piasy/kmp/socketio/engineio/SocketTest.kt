package com.piasy.kmp.socketio.engineio

import com.piasy.kmp.socketio.engineio.transports.PollingXHR
import com.piasy.kmp.socketio.engineio.transports.TransportFactory
import com.piasy.kmp.socketio.engineio.transports.WebSocket
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.*
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import org.hildan.socketio.EngineIO
import org.hildan.socketio.EngineIOPacket
import org.hildan.socketio.SocketIOPacket
import kotlin.test.Test
import kotlin.test.assertEquals

class SocketTest : BaseTest() {

    private fun prepareSocket(
        transports: List<String>,
        scope: CoroutineScope,
        disablePingTimeout: Boolean = true,
    ): TestSocket {
        val opt = Socket.Options()
        opt.transports = transports

        val transport = spyk(
            TestTransport(Transport.Options(), scope, transports[0])
        )

        val factory = mockk<TransportFactory>()
        every { factory.create(any(), any(), any()) } returns transport

        val socket = Socket("http://localhost", opt, scope, factory)
        socket.disablePingTimeout = disablePingTimeout

        val events = ArrayList<String>()
        val data = HashMap<String, MutableList<Any>>()
        on(socket, Socket.EVENT_OPEN, events, data)
        on(socket, Socket.EVENT_CLOSE, events, data)
        on(socket, Socket.EVENT_MESSAGE, events, data)
        on(socket, Socket.EVENT_ERROR, events, data)
        on(socket, Socket.EVENT_UPGRADE_ERROR, events, data)
        on(socket, Socket.EVENT_FLUSH, events, data)
        on(socket, Socket.EVENT_DRAIN, events, data)
        on(socket, Socket.EVENT_HANDSHAKE, events, data)
        on(socket, Socket.EVENT_UPGRADING, events, data)
        on(socket, Socket.EVENT_UPGRADE, events, data)
        on(socket, Socket.EVENT_PACKET, events, data)
        on(socket, Socket.EVENT_PACKET_CREATE, events, data)
        on(socket, Socket.EVENT_HEARTBEAT, events, data)
        on(socket, Socket.EVENT_DATA, events, data)
        on(socket, Socket.EVENT_PING, events, data)
        on(socket, Socket.EVENT_TRANSPORT, events, data)

        return TestSocket(factory, transport, socket, events, data)
    }

    @Test
    fun filterUpgrades() = runTest {
        val sock = prepareSocket(listOf(PollingXHR.NAME), this)
        assertEquals(
            listOf(PollingXHR.NAME),
            sock.socket.filterUpgrades(listOf(PollingXHR.NAME, WebSocket.NAME))
        )
    }

    @Test
    fun open() = runTest {
        val name = PollingXHR.NAME
        val scope = this
        val sock = prepareSocket(listOf(name), scope)

        sock.socket.open()
        advanceUntilIdle()

        verify(exactly = 1) { sock.factory.create(name, any(), scope) }

        verify(exactly = 1) { sock.transport.open() }
        verifyOn(sock.transport, Transport.EVENT_DRAIN)
        verifyOn(sock.transport, Transport.EVENT_PACKET)
        verifyOn(sock.transport, Transport.EVENT_ERROR)
        verifyOn(sock.transport, Transport.EVENT_CLOSE)

        assertEquals(listOf(Socket.EVENT_TRANSPORT), sock.events)
    }

    @Test
    fun openSuccess() = runTest {
        val sock = prepareSocket(listOf(WebSocket.NAME), this)
        sock.socket.open()
        advanceUntilIdle()

        sock.transport.mockHandshake()
        advanceUntilIdle()

        verify(exactly = 1) { sock.transport.open() }

        assertEquals(
            listOf(
                Socket.EVENT_TRANSPORT,
                Socket.EVENT_PACKET,
                Socket.EVENT_HEARTBEAT,
                Socket.EVENT_HANDSHAKE,
                Socket.EVENT_OPEN,
            ),
            sock.events
        )
    }

    // to test timeout, we don't want to skip delay,
    // so we don't use test scheduler and advanceUntilIdle,
    // and use delay to wait.
    @Test
    fun openPingNotTimeout() = runBlocking {
        val sock = prepareSocket(listOf(WebSocket.NAME), this, false)
        sock.socket.open()
        delay(10)

        sock.transport.mockHandshake(pingInterval = 500, pingTimeout = 1000)
        delay(1000)
        sock.transport.mockPing()
        delay(800)

        verify(exactly = 1) { sock.transport.open() }

        assertEquals(
            listOf(
                Socket.EVENT_TRANSPORT,
                Socket.EVENT_PACKET,
                Socket.EVENT_HEARTBEAT,
                Socket.EVENT_HANDSHAKE,
                Socket.EVENT_OPEN,
                Socket.EVENT_PACKET,
                Socket.EVENT_HEARTBEAT,

                Socket.EVENT_PING,
                Socket.EVENT_PACKET_CREATE,
                Socket.EVENT_FLUSH,
                Socket.EVENT_DRAIN,
            ),
            sock.events
        )
    }

    @Test
    fun openPingTimeout() = runBlocking {
        val sock = prepareSocket(listOf(WebSocket.NAME), this, false)
        sock.socket.open()
        delay(10)

        sock.transport.mockHandshake(pingInterval = 500, pingTimeout = 1000)
        withContext(Dispatchers.Default) { delay(1800) }

        verify(exactly = 1) { sock.transport.open() }
        verify(exactly = 1) { sock.transport.close() }

        assertEquals(
            listOf(
                Socket.EVENT_TRANSPORT,
                Socket.EVENT_PACKET,
                Socket.EVENT_HEARTBEAT,
                Socket.EVENT_HANDSHAKE,
                Socket.EVENT_OPEN,
                Socket.EVENT_CLOSE,
            ),
            sock.events
        )
    }

    @Test
    fun openUpgrade() = runTest {
        val sock = prepareSocket(listOf(PollingXHR.NAME, WebSocket.NAME), this)
        sock.socket.open()
        advanceUntilIdle()

        assertEquals(
            listOf(WebSocket.NAME),
            sock.socket.filterUpgrades(listOf(PollingXHR.NAME, WebSocket.NAME))
        )

        sock.transport.mockHandshake(listOf(PollingXHR.NAME, WebSocket.NAME))
        advanceUntilIdle()

        // TODO
        assertEquals(
            listOf(
                Socket.EVENT_TRANSPORT,
                Socket.EVENT_PACKET,
                Socket.EVENT_HEARTBEAT,
                Socket.EVENT_HANDSHAKE,
                Socket.EVENT_OPEN,
            ),
            sock.events,
        )
    }

    @Test
    fun close() = runTest {
        val sock = prepareSocket(listOf(WebSocket.NAME), this)
        sock.socket.open()
        advanceUntilIdle()
        sock.transport.mockHandshake()
        sock.socket.close()
        advanceUntilIdle()

        assertEquals(
            listOf(
                Socket.EVENT_TRANSPORT,

                Socket.EVENT_PACKET,
                Socket.EVENT_HEARTBEAT,
                Socket.EVENT_HANDSHAKE,
                Socket.EVENT_OPEN,

                Socket.EVENT_CLOSE,
            ),
            sock.events,
        )
    }

    @Test
    fun closeWithSendingPackets() = runTest {
        val sock = prepareSocket(listOf(WebSocket.NAME), this)
        sock.socket.open()
        advanceUntilIdle()
        sock.transport.mockHandshake()
        sock.socket.send(event("ev"))

        sock.socket.close()
        advanceUntilIdle()

        assertEquals(
            listOf(
                Socket.EVENT_TRANSPORT,

                Socket.EVENT_PACKET,
                Socket.EVENT_HEARTBEAT,
                Socket.EVENT_HANDSHAKE,
                Socket.EVENT_OPEN,

                Socket.EVENT_PACKET_CREATE,
                Socket.EVENT_FLUSH,
                Socket.EVENT_DRAIN,

                Socket.EVENT_CLOSE,
            ),
            sock.events,
        )
    }

    private fun event(event: String) = EngineIOPacket.Message(
        SocketIOPacket.Event("/", null, buildJsonArray { add(event) })
    )

    @Test
    fun send() = runTest {
        val sock = prepareSocket(listOf(WebSocket.NAME), this)
        sock.socket.open()
        advanceUntilIdle()

        val events1 = listOf("ev1", "ev2", "ev3")
        events1.forEach { sock.socket.send(event(it)) }
        advanceUntilIdle()

        sock.transport.mockHandshake()
        advanceUntilIdle()

        val events2 = listOf("ev4", "ev5", "ev6")
        events2.forEach { sock.socket.send(event(it)) }
        advanceUntilIdle()

        assertEquals(
            listOf(
                Socket.EVENT_TRANSPORT,

                Socket.EVENT_PACKET_CREATE,
                Socket.EVENT_FLUSH,
                Socket.EVENT_PACKET_CREATE,
                Socket.EVENT_FLUSH,
                Socket.EVENT_PACKET_CREATE,
                Socket.EVENT_FLUSH,
                Socket.EVENT_DRAIN,

                Socket.EVENT_PACKET,
                Socket.EVENT_HEARTBEAT,
                Socket.EVENT_HANDSHAKE,
                Socket.EVENT_OPEN,

                Socket.EVENT_PACKET_CREATE,
                Socket.EVENT_FLUSH,
                Socket.EVENT_PACKET_CREATE,
                Socket.EVENT_FLUSH,
                Socket.EVENT_PACKET_CREATE,
                Socket.EVENT_FLUSH,
                Socket.EVENT_DRAIN,
            ),
            sock.events,
        )

        val eventsSent = sock.transport.packets.map {
            @Suppress("UNCHECKED_CAST")
            ((it as EngineIOPacket.Message<SocketIOPacket.Event>)
                .payload.payload[0] as JsonPrimitive).content
        }
        val expected = ArrayList<String>()
        expected.addAll(events1)
        expected.addAll(events2)
        assertEquals(expected, eventsSent)
    }

    @Test
    fun onMessage() = runTest {
        val sock = prepareSocket(listOf(WebSocket.NAME), this)
        sock.socket.open()
        advanceUntilIdle()
        sock.transport.mockHandshake()
        val pkt = event("ev")
        sock.transport.mockMessage(EngineIO.encodeSocketIO(pkt))
        advanceUntilIdle()

        assertEquals(
            listOf(
                Socket.EVENT_TRANSPORT,

                Socket.EVENT_PACKET,
                Socket.EVENT_HEARTBEAT,
                Socket.EVENT_HANDSHAKE,
                Socket.EVENT_OPEN,

                Socket.EVENT_PACKET,
                Socket.EVENT_HEARTBEAT,
                Socket.EVENT_DATA,
                Socket.EVENT_MESSAGE,
            ),
            sock.events,
        )

        assertEquals(listOf(pkt.payload), sock.data[Socket.EVENT_MESSAGE])
    }

    class TestTransport(
        opt: Options,
        scope: CoroutineScope,
        name: String,
    ) : Transport(opt, scope, name) {
        val packets = ArrayList<EngineIOPacket<*>>()

        override suspend fun doOpen() {
            onOpen()
        }

        override suspend fun doSend(packets: List<EngineIOPacket<*>>) {
            this.packets.addAll(packets)
            emit(EVENT_DRAIN, packets.size)
        }

        override suspend fun doClose(fromOpenState: Boolean) {
            onClose()
        }

        fun mockHandshake(
            upgrades: List<String> = emptyList(),
            pingInterval: Int = 25000,
            pingTimeout: Int = 20000
        ) {
            onData(mockOpen(upgrades, pingInterval, pingTimeout))
        }

        fun mockPing() {
            onData("2")
        }

        fun mockMessage(msg: String) {
            onData(msg)
        }
    }

    class TestSocket(
        val factory: TransportFactory,
        val transport: TestTransport,
        val socket: Socket,
        val events: List<String>,
        val data: Map<String, List<Any>>,
    )
}
