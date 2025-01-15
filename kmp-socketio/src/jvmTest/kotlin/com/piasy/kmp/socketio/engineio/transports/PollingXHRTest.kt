package com.piasy.kmp.socketio.engineio.transports

import com.piasy.kmp.socketio.engineio.BaseTest
import com.piasy.kmp.socketio.engineio.Transport
import com.piasy.kmp.socketio.engineio.mockOpen
import com.piasy.kmp.socketio.engineio.on
import com.piasy.kmp.socketio.logging.Logger
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.hildan.socketio.EngineIOPacket
import kotlin.test.Test
import kotlin.test.assertEquals

class PollingXHRTest : BaseTest() {
    private fun preparePolling(
        scope: CoroutineScope,
        respCode: HttpStatusCode,
        respBody: List<String>,
        replyPolls: Int = 1,
        delayPollResp: Long = 0,
    ): TestPolling {
        mockkStatic("io.ktor.client.statement.HttpResponseKt")

        val httpMethods = ArrayList<HttpMethod>()
        var respIdx = 0
        var pollCount = 0
        val mockResp: (String) -> HttpResponse = { data ->
            val resp = mockk<HttpResponse>(relaxed = true)
            every { resp.status } returns respCode
            coEvery { resp.bodyAsText() } returns data
            resp
        }

        val factory = mockk<HttpClientFactory>()
        val paramSlot = slot<HttpRequestBuilder.() -> Unit>()
        coEvery { factory.httpRequest(any(), capture(paramSlot)) } coAnswers {
            val builder = HttpRequestBuilder()
            paramSlot.captured(builder)
            httpMethods.add(builder.method)
            if (builder.method == HttpMethod.Get) {
                pollCount++
                if (pollCount <= replyPolls) {
                    val data = respBody[respIdx]
                    respIdx++
                    if (delayPollResp > 0) {
                        delay(delayPollResp)
                    }
                    mockResp(data)
                } else {
                    awaitCancellation()
                }
            } else {
                val data = respBody[respIdx]
                respIdx++
                mockResp(data)
            }
        }

        val polling = PollingXHR(
            Transport.Options(), scope,
            CoroutineScope(Dispatchers.Default), factory, false
        )

        val events = ArrayList<String>()
        val data = HashMap<String, MutableList<Any>>()
        on(polling, Transport.EVENT_OPEN, events, data)
        on(polling, Transport.EVENT_CLOSE, events, data)
        on(polling, Transport.EVENT_PACKET, events, data)
        on(polling, Transport.EVENT_DRAIN, events, data)
        on(polling, Transport.EVENT_ERROR, events, data)
        on(polling, Transport.EVENT_REQUEST_HEADERS, events, data)
        on(polling, Transport.EVENT_RESPONSE_HEADERS, events, data)
        on(polling, PollingXHR.EVENT_POLL, events, data)
        on(polling, PollingXHR.EVENT_POLL_COMPLETE, events, data)

        return TestPolling(polling, factory, events, data, httpMethods)
    }

    @Test
    fun openSuccess() = runTest {
        val polling = preparePolling(
            this, HttpStatusCode.OK, listOf(mockOpen())
        )
        polling.polling.open()
        waitExec(this, 2000)

        coVerify(exactly = 2) { polling.factory.httpRequest(any(), any()) }
        assertEquals(
            listOf(
                Transport.EVENT_REQUEST_HEADERS,
                PollingXHR.EVENT_POLL,
                Transport.EVENT_RESPONSE_HEADERS,
                Transport.EVENT_OPEN,
                Transport.EVENT_PACKET,
                PollingXHR.EVENT_POLL_COMPLETE,

                Transport.EVENT_REQUEST_HEADERS,
                PollingXHR.EVENT_POLL,
            ),
            polling.events
        )
    }

    @Test
    fun openFail() = runTest {
        val polling = preparePolling(
            this, HttpStatusCode.NotFound, listOf("")
        )
        polling.polling.open()
        waitExec(this)

        coVerify(exactly = 1) { polling.factory.httpRequest(any(), any()) }
        assertEquals(
            listOf(
                Transport.EVENT_REQUEST_HEADERS,
                PollingXHR.EVENT_POLL,
                Transport.EVENT_RESPONSE_HEADERS,
                Transport.EVENT_ERROR,
            ),
            polling.events
        )
    }

    @Test
    fun send() = runTest {
        val polling = preparePolling(
            this, HttpStatusCode.OK, listOf(mockOpen(), "")
        )
        polling.polling.open()
        waitExec(this)

        polling.polling.send(listOf(EngineIOPacket.Pong(null)))
        waitExec(this)

        coVerify(exactly = 3) { polling.factory.httpRequest(any(), any()) }
        assertEquals(
            listOf(
                HttpMethod.Get,
                HttpMethod.Get,
                HttpMethod.Post,
            ),
            polling.requestMethods
        )
        assertEquals(
            listOf(
                // poll & open
                Transport.EVENT_REQUEST_HEADERS,
                PollingXHR.EVENT_POLL,
                Transport.EVENT_RESPONSE_HEADERS,
                Transport.EVENT_OPEN,
                Transport.EVENT_PACKET,
                PollingXHR.EVENT_POLL_COMPLETE,

                // poll triggered by onOpen
                Transport.EVENT_REQUEST_HEADERS,
                PollingXHR.EVENT_POLL,

                // send
                Transport.EVENT_REQUEST_HEADERS,
                Transport.EVENT_RESPONSE_HEADERS,
                Transport.EVENT_DRAIN,
            ),
            polling.events
        )
        assertEquals(
            listOf(1),
            polling.data[Transport.EVENT_DRAIN]
        )
    }

    @Test
    fun packet() = runTest {
        val polling = preparePolling(
            this, HttpStatusCode.OK, listOf(mockOpen(), "2" /* ping */), 2
        )
        polling.polling.open()
        waitExec(this, 500)

        coVerify(exactly = 3) { polling.factory.httpRequest(any(), any()) }
        assertEquals(
            listOf(
                // poll & open
                Transport.EVENT_REQUEST_HEADERS,
                PollingXHR.EVENT_POLL,
                Transport.EVENT_RESPONSE_HEADERS,
                Transport.EVENT_OPEN,
                Transport.EVENT_PACKET,
                PollingXHR.EVENT_POLL_COMPLETE,

                // poll triggered by onOpen (with mock response)
                Transport.EVENT_REQUEST_HEADERS,
                PollingXHR.EVENT_POLL,
                Transport.EVENT_RESPONSE_HEADERS,
                Transport.EVENT_PACKET,
                PollingXHR.EVENT_POLL_COMPLETE,

                // poll triggered by onData
                Transport.EVENT_REQUEST_HEADERS,
                PollingXHR.EVENT_POLL,
            ),
            polling.events
        )

        assertEquals(EngineIOPacket.Ping(null), polling.data[Transport.EVENT_PACKET]!![1])
    }

    @Test
    fun close() = runTest {
        val polling = preparePolling(
            this, HttpStatusCode.OK, listOf(mockOpen(), "")
        )
        polling.polling.open()
        waitExec(this)
        polling.polling.close()
        waitExec(this)

        coVerify(exactly = 3) { polling.factory.httpRequest(any(), any()) }
        assertEquals(
            listOf(
                // poll & open
                Transport.EVENT_REQUEST_HEADERS,
                PollingXHR.EVENT_POLL,
                Transport.EVENT_RESPONSE_HEADERS,
                Transport.EVENT_OPEN,
                Transport.EVENT_PACKET,
                PollingXHR.EVENT_POLL_COMPLETE,

                // poll triggered by onOpen
                Transport.EVENT_REQUEST_HEADERS,
                PollingXHR.EVENT_POLL,

                // close
                Transport.EVENT_REQUEST_HEADERS,
                Transport.EVENT_RESPONSE_HEADERS,
                Transport.EVENT_DRAIN,
                Transport.EVENT_CLOSE,
            ),
            polling.events
        )
    }

    @Test
    fun closeOpening() = runTest {
        val polling = preparePolling(
            this, HttpStatusCode.OK, listOf(mockOpen(), ""), delayPollResp = 400
        )
        polling.polling.open()
        waitExec(this)
        polling.polling.close()
        waitExec(this, 2500)

        coVerify(exactly = 3) { polling.factory.httpRequest(any(), any()) }
        Logger.info("XXPXX", "closeOpening verify events")
        assertEquals(
            listOf(
                // poll & open
                Transport.EVENT_REQUEST_HEADERS,
                PollingXHR.EVENT_POLL,
                Transport.EVENT_RESPONSE_HEADERS,
                Transport.EVENT_OPEN,

                // onClose triggered by onOpen, prepare headers on work thread
                Transport.EVENT_REQUEST_HEADERS,

                // open packet
                Transport.EVENT_PACKET,
                PollingXHR.EVENT_POLL_COMPLETE,

                // poll triggered by onOpen
                Transport.EVENT_REQUEST_HEADERS,
                PollingXHR.EVENT_POLL,

                // close
                Transport.EVENT_RESPONSE_HEADERS,
                Transport.EVENT_DRAIN,
                Transport.EVENT_CLOSE,
            ),
            polling.events
        )
    }

    class TestPolling(
        val polling: PollingXHR,
        val factory: HttpClientFactory,
        val events: List<String>,
        val data: Map<String, List<Any>>,
        val requestMethods: List<HttpMethod>,
    )
}
