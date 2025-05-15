package com.piasy.kmp.socketio.socketio

import com.piasy.kmp.socketio.engineio.transports.DefaultHttpClientFactory
import com.piasy.kmp.socketio.engineio.transports.WebSocket
import com.piasy.kmp.xlog.Logging
import io.ktor.util.date.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.unsafe.UnsafeByteStringApi
import kotlinx.io.bytestring.unsafe.UnsafeByteStringOperations
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(UnsafeByteStringApi::class)
abstract class ConnectionTest {
    @BeforeTest
    abstract fun startServer()

    @AfterTest
    abstract fun stopServer()

    protected fun client(path: String = "/", opt: IO.Options = IO.Options(), block: (Socket) -> Unit) {
        IO.socket("http://localhost:3000$path", opt, block)
    }

    protected fun doTest(
        timeout: Duration = 10.seconds,
        testBody: suspend TestScope.() -> Unit
    ): TestResult {
        val trueBody: suspend TestScope.() -> Unit = {
            withContext(Dispatchers.Default) {
                delay(1000)
            }
            testBody()
        }
        return runTest(timeout = timeout, testBody = trueBody)
    }

    @Test
    fun connectAndEcho() = doTest {
        Logging.info(TAG, "connectAndEcho start")

        val now = GMTDate()
        val connected = CompletableDeferred<Boolean>()
        val echoBack = CompletableDeferred<Array<out Any>>()

        val opt = IO.Options()
        opt.transports = listOf(WebSocket.NAME)
        client(opt = opt) { socket ->
            socket.on(Socket.EVENT_CONNECT) { args ->
                Logging.info(TAG, "connectAndEcho on connect ${args.joinToString()}")
                connected.complete(true)
                val bin = UnsafeByteStringOperations.wrapUnsafe(byteArrayOf(0x1, 0x3, 0x1, 0x4))
                socket.emit("echo", 1, "2", bin, now)
                Logging.info(TAG, "connectAndEcho echo emitted")
            }.on("echoBack") { args ->
                Logging.info(TAG, "connectAndEcho on echoBack ${args.joinToString()}")
                echoBack.complete(args)
            }

            socket.open()
        }

        assertTrue(connected.await())
        val args = echoBack.await()
        assertEquals(1, args[0])
        assertEquals("2", args[1])
        assertEquals(ByteString(byteArrayOf(0x1, 0x3, 0x1, 0x4)), args[2])
        assertEquals(now.toString(), args[3])
    }

    @Test
    fun shouldConnectUntrusted() = doTest {
        val trustAllCertsHttpClientFactory = DefaultHttpClientFactory(
            trustAllCerts = true,
        )
        val responseResult = runCatching {
            trustAllCertsHttpClientFactory.httpRequest(
                url = "https://expired.badssl.com/",
            ) {}
        }
        assertTrue(responseResult.isSuccess)
        assertEquals(responseResult.getOrThrow().status.value, 200)
    }

    @Test
    fun shouldResetConnectionUntrusted() = doTest {
        val trustAllCertsHttpClientFactory = DefaultHttpClientFactory(
            trustAllCerts = false,
        )
        val responseResult = runCatching {
            trustAllCertsHttpClientFactory.httpRequest(
                url = "https://expired.badssl.com/",
            ) {}
        }
        assertTrue(responseResult.isFailure)
    }

    companion object {
        const val TAG = "ConnectionTest"
    }
}
