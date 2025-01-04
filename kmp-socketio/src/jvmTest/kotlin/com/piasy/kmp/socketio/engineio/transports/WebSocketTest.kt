package com.piasy.kmp.socketio.engineio.transports

import com.piasy.kmp.socketio.engineio.BaseTest
import com.piasy.kmp.socketio.engineio.Transport
import com.piasy.kmp.socketio.engineio.on
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.websocket.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.runTest
import org.hildan.socketio.EngineIOPacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class WebSocketTest : BaseTest() {
    private fun prepareWs(
        scope: CoroutineScope,
        incomingWait: Boolean = true
    ): TestWs {
        val ws = mockk<DefaultClientWebSocketSession>(relaxed = true)

        val incoming = mockk<ReceiveChannel<Frame>>()
        if (incomingWait) {
            coEvery { incoming.receive() } just Awaits
        }
        every { ws.incoming } returns incoming

        mockkStatic("io.ktor.websocket.WebSocketSessionKt")
        coEvery { ws.send(any<String>()) } just Runs
        coEvery { ws.close(any<CloseReason>()) } just Runs

        val factory = mockk<HttpClientFactory>()
        val requestBuilder = slot<HttpRequestBuilder.() -> Unit>()
        val block = slot<suspend DefaultClientWebSocketSession.() -> Unit>()
        coEvery { factory.createWs(any(), capture(requestBuilder), capture(block)) } coAnswers {
            requestBuilder.captured(HttpRequestBuilder())
            block.captured(ws)
        }

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
        waitExec(this)

        coVerify(exactly = 1) { ws.factory.createWs(any(), any(), any()) }
        assertEquals(
            listOf(
                Transport.EVENT_REQUEST_HEADERS,
                Transport.EVENT_RESPONSE_HEADERS,
                Transport.EVENT_OPEN,
            ),
            ws.events
        )
    }

    @Test
    fun sendBeforeOpen() = runTest {
        val ws = prepareWs(this)

        try {
            ws.ws.send(listOf(EngineIOPacket.Pong(null)))
            fail()
        } catch (e: Exception) {
            assertEquals("Transport not open", e.message)
        }
    }

    @Test
    fun send() = runTest {
        val ws = prepareWs(this)

        ws.ws.open()
        waitExec(this)
        ws.ws.send(listOf(EngineIOPacket.Pong(null)))
        waitExec(this)

        coVerify(exactly = 1) { ws.inWs.send("3") }
        assertEquals(
            listOf(
                Transport.EVENT_REQUEST_HEADERS,
                Transport.EVENT_RESPONSE_HEADERS,
                Transport.EVENT_OPEN,
                Transport.EVENT_DRAIN,
            ),
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
            delay(400)
            Frame.Close()
        }

        ws.ws.open()
        waitExec(this)
        ws.ws.close()
        waitExec(this)

        coVerify(exactly = 1) { ws.inWs.close(any<CloseReason>()) }
        assertEquals(
            listOf(
                Transport.EVENT_REQUEST_HEADERS,
                Transport.EVENT_RESPONSE_HEADERS,
                Transport.EVENT_OPEN,
                Transport.EVENT_CLOSE
            ),
            ws.events
        )
    }

    @Test
    fun packet() = runTest {
        val ws = prepareWs(this, false)
        var givePacket = false
        coEvery { ws.incoming.receive() } coAnswers {
            if (givePacket) {
                awaitCancellation()
            } else {
                givePacket = true
                delay(100)
                Frame.Text("2")
            }
        }

        ws.ws.open()
        waitExec(this, 2000)

        assertEquals(
            listOf(
                Transport.EVENT_REQUEST_HEADERS,
                Transport.EVENT_RESPONSE_HEADERS,
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
