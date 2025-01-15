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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import org.hildan.socketio.EngineIO
import org.hildan.socketio.EngineIOPacket
import org.hildan.socketio.SocketIOPacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EngineSocketTest : BaseTest() {

    private fun prepareSocket(
        transports: List<String>,
        scope: CoroutineScope,
        disablePingTimeout: Boolean = true,
        upgrade: Boolean = false,
        transportsObj: List<Transport> = emptyList(),
    ): TestSocket {
        val opt = EngineSocket.Options()
        opt.transports = transports
        opt.upgrade = upgrade

        val transport = spyk(
            TestTransport(Transport.Options(), scope, transports[0])
        )

        val factory = mockk<TransportFactory>()
        if (!upgrade || transportsObj.isEmpty()) {
            every { factory.create(any(), any(), any(), any()) } returns transport
        } else {
            var count = 0
            every { factory.create(any(), any(), any(), any()) } answers {
                val trans = transportsObj[count]
                count++
                trans
            }
        }

        val socket = EngineSocket("http://localhost", opt, scope, factory)
        socket.disablePingTimeout = disablePingTimeout

        val events = ArrayList<String>()
        val data = HashMap<String, MutableList<Any>>()
        on(socket, EngineSocket.EVENT_OPEN, events, data)
        on(socket, EngineSocket.EVENT_CLOSE, events, data)
        on(socket, EngineSocket.EVENT_ERROR, events, data)
        on(socket, EngineSocket.EVENT_UPGRADE_ERROR, events, data)
        on(socket, EngineSocket.EVENT_FLUSH, events, data)
        on(socket, EngineSocket.EVENT_DRAIN, events, data)
        on(socket, EngineSocket.EVENT_HANDSHAKE, events, data)
        on(socket, EngineSocket.EVENT_UPGRADING, events, data)
        on(socket, EngineSocket.EVENT_UPGRADE, events, data)
        on(socket, EngineSocket.EVENT_PACKET, events, data)
        on(socket, EngineSocket.EVENT_PACKET_CREATE, events, data)
        on(socket, EngineSocket.EVENT_HEARTBEAT, events, data)
        on(socket, EngineSocket.EVENT_DATA, events, data)
        on(socket, EngineSocket.EVENT_PING, events, data)
        on(socket, EngineSocket.EVENT_TRANSPORT, events, data)

        return TestSocket(factory, transport, socket, events, data)
    }

    @Test
    fun testCancelDelayJob() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))
        var executed = false
        val job = scope.launch {
            delay(1000)
            executed = true
        }
        delay(500)
        job.cancel()
        delay(1000)
        assertFalse(executed)
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

        verify(exactly = 1) { sock.factory.create(name, any(), scope, any()) }

        verify(exactly = 1) { sock.transport.open() }
        verifyOn(sock.transport, Transport.EVENT_DRAIN)
        verifyOn(sock.transport, Transport.EVENT_PACKET)
        verifyOn(sock.transport, Transport.EVENT_ERROR)
        verifyOn(sock.transport, Transport.EVENT_CLOSE)

        assertEquals(listOf(EngineSocket.EVENT_TRANSPORT), sock.events)
    }

    @Test
    fun openSuccess() = runTest {
        val sock = prepareSocket(listOf(WebSocket.NAME), this)
        sock.socket.open()
        advanceUntilIdle()

        sock.transport.mockOnHandshake()
        advanceUntilIdle()

        verify(exactly = 1) { sock.transport.open() }

        assertEquals(
            listOf(
                EngineSocket.EVENT_TRANSPORT,
                EngineSocket.EVENT_PACKET,
                EngineSocket.EVENT_HEARTBEAT,
                EngineSocket.EVENT_HANDSHAKE,
                EngineSocket.EVENT_OPEN,
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

        sock.transport.mockOnHandshake(pingInterval = 500, pingTimeout = 1000)
        delay(1000)
        sock.transport.mockOnPing()
        delay(800)

        verify(exactly = 1) { sock.transport.open() }

        assertEquals(
            listOf(
                EngineSocket.EVENT_TRANSPORT,
                EngineSocket.EVENT_PACKET,
                EngineSocket.EVENT_HEARTBEAT,
                EngineSocket.EVENT_HANDSHAKE,
                EngineSocket.EVENT_OPEN,
                EngineSocket.EVENT_PACKET,
                EngineSocket.EVENT_HEARTBEAT,

                EngineSocket.EVENT_PING,
                EngineSocket.EVENT_PACKET_CREATE,
                EngineSocket.EVENT_FLUSH,
                EngineSocket.EVENT_DRAIN,
            ),
            sock.events
        )
    }

    @Test
    fun openPingTimeout() = runBlocking {
        val sock = prepareSocket(listOf(WebSocket.NAME), this, false)
        sock.socket.open()
        delay(10)

        sock.transport.mockOnHandshake(pingInterval = 500, pingTimeout = 1000)
        withContext(Dispatchers.Default) { delay(1800) }

        verify(exactly = 1) { sock.transport.open() }
        verify(exactly = 1) { sock.transport.close() }

        assertEquals(
            listOf(
                EngineSocket.EVENT_TRANSPORT,
                EngineSocket.EVENT_PACKET,
                EngineSocket.EVENT_HEARTBEAT,
                EngineSocket.EVENT_HANDSHAKE,
                EngineSocket.EVENT_OPEN,
                EngineSocket.EVENT_CLOSE,
            ),
            sock.events
        )
    }

    @Test
    fun openUpgrade() = runTest {
        val polling = spyk(TestTransport(Transport.Options(), this, PollingXHR.NAME))
        val ws = spyk(TestTransport(Transport.Options(), this, WebSocket.NAME))

        val transports = listOf(PollingXHR.NAME, WebSocket.NAME)
        val sock = prepareSocket(transports, this, upgrade = true, transportsObj = listOf(polling, ws))
        sock.socket.open()
        advanceUntilIdle()

        assertEquals(
            listOf(WebSocket.NAME),
            sock.socket.filterUpgrades(transports)
        )

        polling.mockOnHandshake(transports)
        advanceUntilIdle()

        assertEquals<List<EngineIOPacket<*>>>(listOf(EngineIOPacket.Ping(EngineSocket.PROBE)), ws.packets)
        ws.mockOnPong(EngineSocket.PROBE)
        advanceUntilIdle()

        assertEquals(
            listOf(
                EngineSocket.EVENT_TRANSPORT,
                EngineSocket.EVENT_PACKET,
                EngineSocket.EVENT_HEARTBEAT,
                EngineSocket.EVENT_HANDSHAKE,
                EngineSocket.EVENT_OPEN,

                EngineSocket.EVENT_TRANSPORT,
                EngineSocket.EVENT_UPGRADING,
                EngineSocket.EVENT_UPGRADE,
            ),
            sock.events,
        )
    }

    @Test
    fun close() = runTest {
        val sock = prepareSocket(listOf(WebSocket.NAME), this)
        sock.socket.open()
        advanceUntilIdle()
        sock.transport.mockOnHandshake()
        sock.socket.close()
        advanceUntilIdle()

        assertEquals(
            listOf(
                EngineSocket.EVENT_TRANSPORT,

                EngineSocket.EVENT_PACKET,
                EngineSocket.EVENT_HEARTBEAT,
                EngineSocket.EVENT_HANDSHAKE,
                EngineSocket.EVENT_OPEN,

                EngineSocket.EVENT_CLOSE,
            ),
            sock.events,
        )
    }

    @Test
    fun closeWithSendingPackets() = runTest {
        val sock = prepareSocket(listOf(WebSocket.NAME), this)
        sock.socket.open()
        advanceUntilIdle()
        sock.transport.mockOnHandshake()
        sock.socket.send(event("ev"))

        sock.socket.close()
        advanceUntilIdle()

        assertEquals(
            listOf(
                EngineSocket.EVENT_TRANSPORT,

                EngineSocket.EVENT_PACKET,
                EngineSocket.EVENT_HEARTBEAT,
                EngineSocket.EVENT_HANDSHAKE,
                EngineSocket.EVENT_OPEN,

                EngineSocket.EVENT_PACKET_CREATE,
                EngineSocket.EVENT_FLUSH,
                EngineSocket.EVENT_DRAIN,

                EngineSocket.EVENT_CLOSE,
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

        sock.transport.mockOnHandshake()
        advanceUntilIdle()

        val events2 = listOf("ev4", "ev5", "ev6")
        events2.forEach { sock.socket.send(event(it)) }
        advanceUntilIdle()

        assertEquals(
            listOf(
                EngineSocket.EVENT_TRANSPORT,

                EngineSocket.EVENT_PACKET_CREATE,
                EngineSocket.EVENT_FLUSH,
                EngineSocket.EVENT_PACKET_CREATE,
                EngineSocket.EVENT_FLUSH,
                EngineSocket.EVENT_PACKET_CREATE,
                EngineSocket.EVENT_FLUSH,
                EngineSocket.EVENT_DRAIN,

                EngineSocket.EVENT_PACKET,
                EngineSocket.EVENT_HEARTBEAT,
                EngineSocket.EVENT_HANDSHAKE,
                EngineSocket.EVENT_OPEN,

                EngineSocket.EVENT_PACKET_CREATE,
                EngineSocket.EVENT_FLUSH,
                EngineSocket.EVENT_PACKET_CREATE,
                EngineSocket.EVENT_FLUSH,
                EngineSocket.EVENT_PACKET_CREATE,
                EngineSocket.EVENT_FLUSH,
                EngineSocket.EVENT_DRAIN,
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
        sock.transport.mockOnHandshake()
        val pkt = event("ev")
        sock.transport.mockOnMessage(EngineIO.encodeSocketIO(pkt))
        advanceUntilIdle()

        assertEquals(
            listOf(
                EngineSocket.EVENT_TRANSPORT,

                EngineSocket.EVENT_PACKET,
                EngineSocket.EVENT_HEARTBEAT,
                EngineSocket.EVENT_HANDSHAKE,
                EngineSocket.EVENT_OPEN,

                EngineSocket.EVENT_PACKET,
                EngineSocket.EVENT_HEARTBEAT,
                EngineSocket.EVENT_DATA,
            ),
            sock.events,
        )

        assertEquals(listOf(pkt.payload), sock.data[EngineSocket.EVENT_DATA])
    }

    class TestTransport(
        opt: Options,
        scope: CoroutineScope,
        name: String,
    ) : Transport(opt, scope, name, false) {
        val packets = ArrayList<EngineIOPacket<*>>()
        override fun pause(onPause: () -> Unit) {
            if (name == PollingXHR.NAME) {
                onPause()
            }
        }

        override fun doOpen() {
            scope.launch { onOpen() }
        }

        override fun doSend(packets: List<EngineIOPacket<*>>) {
            this.packets.addAll(packets)
            scope.launch { emit(EVENT_DRAIN, packets.size) }
        }

        override fun doClose(fromOpenState: Boolean) {
            scope.launch { onClose() }
        }

        fun mockOnHandshake(
            upgrades: List<String> = emptyList(),
            pingInterval: Int = 25000,
            pingTimeout: Int = 20000
        ) {
            onPacket(EngineIO.decodeSocketIO(mockOpen(upgrades, pingInterval, pingTimeout)))
        }

        fun mockOnPing() {
            onPacket(EngineIO.decodeSocketIO("2"))
        }

        fun mockOnPong(data: String? = null) {
            onPacket(EngineIO.decodeSocketIO("3${data ?: ""}"))
        }

        fun mockOnMessage(msg: String) {
            onPacket(EngineIO.decodeSocketIO(msg))
        }
    }

    class TestSocket(
        val factory: TransportFactory,
        val transport: TestTransport,
        val socket: EngineSocket,
        val events: List<String>,
        val data: Map<String, List<Any>>,
    )
}
