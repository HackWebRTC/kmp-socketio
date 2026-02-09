package com.piasy.kmp.socketio.socketio

import com.piasy.kmp.socketio.engineio.transports.WebSocket
import com.piasy.kmp.socketio.socketio.Ack
import com.piasy.kmp.socketio.socketio.IO
import com.piasy.kmp.socketio.socketio.Socket
import com.piasy.kmp.socketio.socketio.openSuspend
import com.piasy.kmp.socketio.socketio.socketSuspend
import com.piasy.kmp.socketio.socketio.emitSuspend
import com.piasy.kmp.socketio.socketio.stateFlow
import com.piasy.kmp.socketio.socketio.connectedFlow
import com.piasy.kmp.socketio.socketio.socketIdFlow
import com.piasy.kmp.socketio.socketio.flow
import com.piasy.kmp.xlog.Logging
import io.ktor.util.date.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.unsafe.UnsafeByteStringApi
import kotlinx.io.bytestring.unsafe.UnsafeByteStringOperations
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.*
import kotlin.jvm.JvmStatic

@OptIn(UnsafeByteStringApi::class)
abstract class CoroutinesExtensionsTest {
    @BeforeTest
    abstract fun startServer()

    @AfterTest
    abstract fun stopServer()

    protected suspend fun clientSuspend(path: String = "/", opt: IO.Options = IO.Options()): Socket {
        return IO.socketSuspend("http://localhost:3000$path", opt)
    }

    protected fun doTest(
        timeout: Duration = 10.seconds,
        testBody: suspend TestScope.() -> Unit
    ) {
        runTest(timeout = timeout, testBody = testBody)
    }

    @Test
    fun testSocketSuspend() = doTest {
        Logging.info(TAG, "testSocketSuspend start")

        val opt = IO.Options()
        opt.transports = listOf(WebSocket.NAME)
        val socket = clientSuspend(opt = opt)
        
        assertNotNull(socket)
        assertEquals("/", socket.nsp)
        
        socket.close()
    }

    @Test
    fun testOpenSuspend() = doTest {
        Logging.info(TAG, "testOpenSuspend start")

        val connected = CompletableDeferred<Boolean>()
        val opt = IO.Options()
        opt.transports = listOf(WebSocket.NAME)
        val socket = clientSuspend(opt = opt)
        
        socket.on(Socket.EVENT_CONNECT) {
            connected.complete(true)
        }
        
        socket.openSuspend()
        delay(1000)
        
        assertTrue(connected.await())
        assertTrue(socket.connectedFlow.value)
        
        socket.close()
    }

    @Test
    fun testEmitSuspend() = doTest {
        Logging.info(TAG, "testEmitSuspend start")

        val now = GMTDate()
        val echoBack = CompletableDeferred<Array<out Any>>()

        val opt = IO.Options()
        opt.transports = listOf(WebSocket.NAME)
        val socket = clientSuspend(opt = opt)
        
        socket.on("echoBack") { args ->
            Logging.info(TAG, "testEmitSuspend on echoBack ${args.joinToString()}")
            echoBack.complete(args)
        }

        socket.openSuspend()
        delay(1000)
        
        // Emit using suspend function
        socket.emitSuspend("echo", 1, "2", now)
        Logging.info(TAG, "testEmitSuspend echo emitted")

        val args = echoBack.await()
        assertEquals(1, args[0])
        assertEquals("2", args[1])
        assertEquals(now.toString(), args[2])
        
        socket.close()
    }

    @Test
    fun testEmitSuspendWithAck() = doTest {
        Logging.info(TAG, "testEmitSuspendWithAck start")

        val ackReceived = CompletableDeferred<Array<out Any>>()

        val opt = IO.Options()
        opt.transports = listOf(WebSocket.NAME)
        val socket = clientSuspend(opt = opt)

        socket.openSuspend()
        delay(1000)
        
        // Emit with suspend ack lambda (trailing lambda syntax)
        socket.emitSuspend("echoWithAck", 42) { args ->
            Logging.info(TAG, "testEmitSuspendWithAck ack received ${args.joinToString()}")
            ackReceived.complete(args)
        }

        val args = ackReceived.await()
        assertTrue(args.isNotEmpty())
        assertEquals(42, args[0])
        
        socket.close()
    }

    @Test
    fun testStateFlow() = doTest {
        Logging.info(TAG, "testStateFlow start")

        val opt = IO.Options()
        opt.transports = listOf(WebSocket.NAME)
        val socket = clientSuspend(opt = opt)
        
        // Collect state changes
        val stateChanges = mutableListOf<com.piasy.kmp.socketio.engineio.State>()
        coroutineScope {
            val job = launch {
                socket.io.stateFlow.take(3).collect { state ->
                    stateChanges.add(state)
                }
            }
            
            delay(100)
            socket.openSuspend()
            delay(1000)
            
            job.cancel()
        }
        
        // Should have at least INIT -> OPENING -> OPEN
        assertTrue(stateChanges.size >= 2)
        assertEquals(com.piasy.kmp.socketio.engineio.State.INIT, stateChanges[0])
        
        socket.close()
    }

    @Test
    fun testConnectedFlow() = doTest {
        Logging.info(TAG, "testConnectedFlow start")

        val opt = IO.Options()
        opt.transports = listOf(WebSocket.NAME)
        val socket = clientSuspend(opt = opt)
        
        // Initial state should be false
        assertFalse(socket.connectedFlow.value)
        
        // Collect connection state changes
        val connectionStates = mutableListOf<Boolean>()
        coroutineScope {
            val job = launch {
                socket.connectedFlow.take(2).collect { connected ->
                    connectionStates.add(connected)
                }
            }
            
            delay(100)
            socket.openSuspend()
            delay(1000)
            
            job.cancel()
        }
        
        // Should transition from false to true
        assertTrue(connectionStates.size >= 1)
        assertTrue(connectionStates.last())
        assertTrue(socket.connectedFlow.value)
        
        socket.close()
    }

    @Test
    fun testSocketIdFlow() = doTest {
        Logging.info(TAG, "testSocketIdFlow start")

        val opt = IO.Options()
        opt.transports = listOf(WebSocket.NAME)
        val socket = clientSuspend(opt = opt)
        
        // Initial ID should be empty
        assertEquals("", socket.socketIdFlow.value)
        
        socket.openSuspend()
        delay(1000)
        
        // After connection, ID should be set
        val socketId = socket.socketIdFlow.value
        assertTrue(socketId.isNotEmpty())
        assertEquals(socketId, socket.id)
        
        socket.close()
    }

    @Test
    fun testEventFlow() = doTest {
        Logging.info(TAG, "testEventFlow start")

        val opt = IO.Options()
        opt.transports = listOf(WebSocket.NAME)
        val socket = clientSuspend(opt = opt)
        
        val connectEvents = mutableListOf<Array<out Any>>()
        coroutineScope {
            val job = launch {
                socket.flow(Socket.EVENT_CONNECT).take(1).collect { args ->
                    connectEvents.add(args)
                }
            }
            
            socket.openSuspend()
            delay(1000)
            
            job.cancel()
        }
        
        // Should have received connect event
        assertTrue(connectEvents.isNotEmpty())
        
        socket.close()
    }

    @Test
    fun testEventFlowWithData() = doTest {
        Logging.info(TAG, "testEventFlowWithData start")

        val now = GMTDate()
        val opt = IO.Options()
        opt.transports = listOf(WebSocket.NAME)
        val socket = clientSuspend(opt = opt)
        
        val echoBackEvents = mutableListOf<Array<out Any>>()
        coroutineScope {
            val job = launch {
                socket.flow("echoBack").take(1).collect { args ->
                    echoBackEvents.add(args)
                }
            }
            
            socket.openSuspend()
            delay(1000)
            
            socket.emitSuspend("echo", 1, "2", now)
            delay(500)
            
            job.cancel()
        }
        
        // Should have received echoBack event
        assertTrue(echoBackEvents.isNotEmpty())
        val args = echoBackEvents[0]
        assertEquals(1, args[0])
        assertEquals("2", args[1])
        assertEquals(now.toString(), args[2])
        
        socket.close()
    }

    @Test
    fun testEmitSuspendWithBinaryData() = doTest {
        Logging.info(TAG, "testEmitSuspendWithBinaryData start")

        val bin = UnsafeByteStringOperations.wrapUnsafe(byteArrayOf(0x1, 0x3, 0x1, 0x4))
        val echoBack = CompletableDeferred<Array<out Any>>()

        val opt = IO.Options()
        opt.transports = listOf(WebSocket.NAME)
        val socket = clientSuspend(opt = opt)
        
        socket.on("echoBack") { args ->
            echoBack.complete(args)
        }

        socket.openSuspend()
        delay(1000)
        
        socket.emitSuspend("echo", bin)
        val args = echoBack.await()
        
        assertEquals(ByteString(byteArrayOf(0x1, 0x3, 0x1, 0x4)), args[0])
        
        socket.close()
    }

    @Test
    fun testEmitSuspendWithFalse() = doTest {
        Logging.info(TAG, "testEmitSuspendWithFalse start")

        val echoBack = CompletableDeferred<Any>()

        val opt = IO.Options()
        opt.transports = listOf(WebSocket.NAME)
        val socket = clientSuspend(opt = opt)
        
        socket.on("echoBack") { args ->
            echoBack.complete(args[0])
        }

        socket.openSuspend()
        delay(1000)
        
        socket.emitSuspend("echo", false)
        val result = echoBack.await()
        
        assertEquals(false, result)
        
        socket.close()
    }

    @Test
    fun testEmitSuspendWithUTF8MultibyteCharacters() = doTest {
        Logging.info(TAG, "testEmitSuspendWithUTF8MultibyteCharacters start")

        val correct = listOf(
            "てすと",
            "Я Б Г Д Ж Й",
            "Ä ä Ü ü ß",
            "utf8 — string",
            "utf8 — string"
        )
        val received = mutableListOf<String>()

        val opt = IO.Options()
        opt.transports = listOf(WebSocket.NAME)
        val socket = clientSuspend(opt = opt)
        
        socket.on("echoBack") { args ->
            received.add(args[0] as String)
        }

        socket.openSuspend()
        delay(1000)
        
        correct.forEach { str ->
            socket.emitSuspend("echo", str)
            delay(100)
        }
        
        delay(1000)
        
        assertEquals(correct.size, received.size)
        correct.forEachIndexed { index, expected ->
            assertEquals(expected, received[index])
        }
        
        socket.close()
    }

    @Test
    fun testEmitSuspendWithBinaryAck() = doTest {
        Logging.info(TAG, "testEmitSuspendWithBinaryAck start")

        val buf = "huehue".toByteArray()
        val ackReceived = CompletableDeferred<ByteString>()

        val opt = IO.Options()
        opt.transports = listOf(WebSocket.NAME)
        val socket = clientSuspend(opt = opt)

        socket.on("ack") { args ->
            val ack = args[0] as Ack
            ack.call(ByteString(buf, 0, buf.size))
        }

        socket.on("ackBack") { args ->
            ackReceived.complete(args[0] as ByteString)
        }

        socket.openSuspend()
        delay(1000)
        
        socket.emitSuspend("callAckBinary")
        delay(500)
        
        val result = ackReceived.await()
        assertArrayEquals(buf, result.toByteArray())
        
        socket.close()
    }

    @Test
    fun testEmitSuspendReceiveBinaryDataWithAck() = doTest {
        Logging.info(TAG, "testEmitSuspendReceiveBinaryDataWithAck start")

        val buf = "huehue".toByteArray()
        val ackReceived = CompletableDeferred<ByteString>()

        val opt = IO.Options()
        opt.transports = listOf(WebSocket.NAME)
        val socket = clientSuspend(opt = opt)

        socket.openSuspend()
        delay(1000)
        
        socket.emitSuspend("getAckBinary", "") { args ->
            ackReceived.complete(args[0] as ByteString)
        }

        val result = ackReceived.await()
        assertArrayEquals(buf, result.toByteArray())
        
        socket.close()
    }

    @Test
    fun testEmitSuspendWithBinaryDataMixedWithJson() = doTest {
        Logging.info(TAG, "testEmitSuspendWithBinaryDataMixedWithJson start")

        val buf = "howdy".toByteArray()
        val echoBack = CompletableDeferred<Array<out Any>>()

        val opt = IO.Options()
        opt.transports = listOf(WebSocket.NAME)
        val socket = clientSuspend(opt = opt)
        
        socket.on("echoBack") { args ->
            echoBack.complete(args)
        }

        socket.openSuspend()
        delay(1000)
        
        val data = buildJsonObject {
            put("hello", "lol")
            put("goodbye", "gotcha")
        }
        socket.emitSuspend("echo", data, ByteString(buf, 0, buf.size))
        
        val args = echoBack.await()
        val json = args[0] as JsonObject
        assertEquals("lol", json["hello"]?.jsonPrimitive?.content)
        assertEquals("gotcha", json["goodbye"]?.jsonPrimitive?.content)
        assertEquals(ByteString(buf, 0, buf.size), args[1] as ByteString)
        
        socket.close()
    }

    @Test
    fun testTwoConnectionsWithSamePath() = doTest {
        Logging.info(TAG, "testTwoConnectionsWithSamePath start")

        val opt = IO.Options()
        opt.transports = listOf(WebSocket.NAME)
        
        val socket1 = clientSuspend(opt = opt)
        val socket2 = clientSuspend(opt = opt)
        
        assertNotEquals(socket1, socket2)
        
        socket1.close()
        socket2.close()
    }

    @Test
    fun testTwoConnectionsWithDifferentQueryStrings() = doTest {
        Logging.info(TAG, "testTwoConnectionsWithDifferentQueryStrings start")

        val opt = IO.Options()
        opt.transports = listOf(WebSocket.NAME)
        
        val socket1 = clientSuspend("/?woot", opt)
        val socket2 = clientSuspend("/", opt)
        
        assertNotEquals(socket1, socket2)
        
        socket1.close()
        socket2.close()
    }

    @Test
    fun testReconnectManually() = doTest {
        Logging.info(TAG, "testReconnectManually start")

        val opt = IO.Options()
        opt.transports = listOf(WebSocket.NAME)
        val socket = clientSuspend(opt = opt)
        
        val firstConnect = CompletableDeferred<Unit>()
        val secondConnect = CompletableDeferred<Unit>()
        
        socket.once(Socket.EVENT_CONNECT) {
            firstConnect.complete(Unit)
            socket.close()
        }
        
        socket.once(Socket.EVENT_DISCONNECT) {
            socket.once(Socket.EVENT_CONNECT) {
                secondConnect.complete(Unit)
            }
            launch {
                socket.openSuspend()
            }
        }
        
        socket.openSuspend()
        firstConnect.await()
        delay(500)
        secondConnect.await()
        
        socket.close()
    }

    @Test
    fun testConnectToNamespaceAfterConnectionEstablished() = doTest {
        Logging.info(TAG, "testConnectToNamespaceAfterConnectionEstablished start")

        val opt = IO.Options()
        opt.transports = listOf(WebSocket.NAME)
        opt.forceNew = true
        
        val socket1 = clientSuspend("/", opt)
        val socket2Connected = CompletableDeferred<Unit>()
        
        socket1.on(Socket.EVENT_CONNECT) {
            launch {
                val socket2 = clientSuspend("/foo", opt)
                socket2.on(Socket.EVENT_CONNECT) {
                    socket2Connected.complete(Unit)
                }
                socket2.openSuspend()
            }
        }
        
        socket1.openSuspend()
        delay(1000)
        socket2Connected.await()
        
        socket1.close()
    }

    @Test
    fun testEmitSuspendWithDateAsString() = doTest {
        Logging.info(TAG, "testEmitSuspendWithDateAsString start")

        val date = GMTDate()
        val echoBack = CompletableDeferred<Any>()

        val opt = IO.Options()
        opt.transports = listOf(WebSocket.NAME)
        val socket = clientSuspend(opt = opt)
        
        socket.on("echoBack") { args ->
            echoBack.complete(args[0])
        }

        socket.openSuspend()
        delay(1000)
        
        socket.emitSuspend("echo", date)
        val result = echoBack.await()
        
        assertTrue(result is String)
        
        socket.close()
    }

    @Test
    fun testEmitSuspendWithDateInObject() = doTest {
        Logging.info(TAG, "testEmitSuspendWithDateInObject start")

        val date = GMTDate()
        val echoBack = CompletableDeferred<JsonObject>()

        val opt = IO.Options()
        opt.transports = listOf(WebSocket.NAME)
        val socket = clientSuspend(opt = opt)
        
        socket.on("echoBack") { args ->
            echoBack.complete(args[0] as JsonObject)
        }

        socket.openSuspend()
        delay(1000)
        
        val data = buildJsonObject {
            put("date", date.toString())
        }
        socket.emitSuspend("echo", data)
        val result = echoBack.await()
        
        assertTrue(result["date"]?.jsonPrimitive?.content is String)
        
        socket.close()
    }

    @Test
    fun testEmitSuspendSendBinaryData() = doTest {
        Logging.info(TAG, "testEmitSuspendSendBinaryData start")

        val buf = "asdfasdf".toByteArray()
        val echoBack = CompletableDeferred<ByteString>()

        val opt = IO.Options()
        opt.transports = listOf(WebSocket.NAME)
        val socket = clientSuspend(opt = opt)
        
        socket.on("echoBack") { args ->
            echoBack.complete(args[0] as ByteString)
        }

        socket.openSuspend()
        delay(1000)
        
        socket.emitSuspend("echo", ByteString(buf, 0, buf.size))
        val result = echoBack.await()
        
        assertArrayEquals(buf, result.toByteArray())
        
        socket.close()
    }

    @Test
    fun testEmitSuspendSendEventsWithByteArraysInCorrectOrder() = doTest {
        Logging.info(TAG, "testEmitSuspendSendEventsWithByteArraysInCorrectOrder start")

        val buf = "abuff1".toByteArray()
        val received = mutableListOf<Any>()

        val opt = IO.Options()
        opt.transports = listOf(WebSocket.NAME)
        val socket = clientSuspend(opt = opt)
        
        socket.on("echoBack") { args ->
            received.add(args[0])
        }

        socket.openSuspend()
        delay(1000)
        
        socket.emitSuspend("echo", ByteString(buf, 0, buf.size))
        socket.emitSuspend("echo", "please arrive second")
        
        delay(1000)
        
        assertEquals(2, received.size)
        assertTrue(received[0] is ByteString)
        assertEquals("please arrive second", received[1])
        
        socket.close()
    }

    companion object {
        const val TAG = "CoroutinesExtensionsTest"
        
        private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
            assertEquals(expected.size, actual.size)
            expected.forEachIndexed { index, byte ->
                assertEquals(byte, actual[index])
            }
        }
    }
}
