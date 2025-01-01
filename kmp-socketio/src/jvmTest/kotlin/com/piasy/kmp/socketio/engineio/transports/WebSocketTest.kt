package com.piasy.kmp.socketio.engineio.transports

import com.piasy.kmp.socketio.engineio.BaseTest
import com.piasy.kmp.socketio.engineio.Transport
import com.piasy.kmp.socketio.engineio.on
import io.ktor.websocket.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.hildan.socketio.EngineIOPacket
import kotlin.test.Test
import kotlin.test.assertEquals

class WebSocketTest : BaseTest() {
    private fun prepareWs(
        scope: CoroutineScope,
        incomingWait: Boolean = true
    ): TestWs {
        val ws = mockk<WebSocketSession>(relaxed = true)

        val incoming = mockk<ReceiveChannel<Frame>>()
        if (incomingWait) {
            coEvery { incoming.receive() } just Awaits
        }
        every { ws.incoming } returns incoming

        mockkStatic("io.ktor.websocket.WebSocketSessionKt")
        coEvery { ws.send(any<String>()) } just Runs
        coEvery { ws.close(any<CloseReason>()) } just Runs

        val factory = mockk<HttpClientFactory>()
        coEvery { factory.createWs(any(), any()) } returns ws

        val socket = WebSocket(
            Transport.Options(), scope,
            CoroutineScope(Dispatchers.Default), factory
        )

        val events = ArrayList<String>()
        val data = HashMap<String, MutableList<Any>>()
        on(socket, Transport.EVENT_OPEN, events, data)
        on(socket, Transport.EVENT_CLOSE, events, data)
        on(socket, Transport.EVENT_PACKET, events, data)
        on(socket, Transport.EVENT_DRAIN, events, data)
        on(socket, Transport.EVENT_ERROR, events, data)
        on(socket, Transport.EVENT_REQUEST_HEADERS, events, data)
        on(socket, Transport.EVENT_RESPONSE_HEADERS, events, data)

        return TestWs(socket, factory, ws, incoming, events, data)
    }

    @Test
    fun open() = runTest {
        val ws = prepareWs(this)

        ws.ws.open()
        advanceUntilIdle()

        coVerify(exactly = 1) { ws.factory.createWs(any(), any()) }
        assertEquals(
            listOf(Transport.EVENT_OPEN),
            ws.events
        )
    }

    @Test
    fun sendBeforeOpen() = runTest {
        var exceptionMessage = ""
        val exceptionHandler = CoroutineExceptionHandler { _, e ->
            exceptionMessage = e.message ?: ""
        }
        val scope = CoroutineScope(testScheduler + SupervisorJob() + exceptionHandler)
        val ws = prepareWs(scope)

        ws.ws.send(listOf(EngineIOPacket.Pong(null)))
        advanceUntilIdle()

        withContext(Dispatchers.Default) {
            delay(10)
        }

        assertEquals("Transport not open", exceptionMessage)
    }

    @Test
    fun send() = runTest {
        val ws = prepareWs(this)

        ws.ws.open()
        ws.ws.send(listOf(EngineIOPacket.Pong(null)))
        advanceUntilIdle()
        withContext(Dispatchers.Default) {
            delay(10)
        }

        coVerify(exactly = 1) { ws.inWs.send("3") }
        assertEquals(
            listOf(Transport.EVENT_OPEN, Transport.EVENT_DRAIN),
            ws.events
        )
        assertEquals(
            listOf(1),
            ws.data[Transport.EVENT_DRAIN]
        )
    }

    @Test
    fun close() = runTest {
        val ws = prepareWs(this, false)
        coEvery { ws.incoming.receive() } coAnswers {
            delay(100)
            Frame.Close(CloseReason(CloseReason.Codes.NORMAL, ""))
        }

        ws.ws.open()
        ws.ws.close()
        advanceUntilIdle()
        withContext(Dispatchers.Default) {
            delay(200)
        }

        coVerify(exactly = 1) { ws.inWs.close(any<CloseReason>()) }
        assertEquals(
            listOf(
                Transport.EVENT_OPEN,
                Transport.EVENT_CLOSE
            ),
            ws.events
        )
    }

    @Test
    fun packet() = runTest {
        val ws = prepareWs(this, false)
        coEvery { ws.incoming.receive() } coAnswers {
            delay(100)
            Frame.Text("2")
        }

        ws.ws.open()
        advanceUntilIdle()
        withContext(Dispatchers.Default) {
            delay(200)
        }

        assertEquals(
            listOf(
                Transport.EVENT_OPEN,
                Transport.EVENT_PACKET
            ),
            ws.events
        )
        assertEquals(
            listOf(EngineIOPacket.Ping(null)),
            ws.data[Transport.EVENT_PACKET]
        )
    }

    class TestWs(
        val ws: WebSocket,
        val factory: HttpClientFactory,
        val inWs: WebSocketSession,
        val incoming: ReceiveChannel<Frame>,
        val events: List<String>,
        val data: Map<String, List<Any>>,
    )
}
