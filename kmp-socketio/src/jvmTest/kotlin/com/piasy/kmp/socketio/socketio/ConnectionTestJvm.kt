package com.piasy.kmp.socketio.socketio

import com.piasy.kmp.socketio.engineio.transports.WebSocket
import com.piasy.kmp.xlog.Logging
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

class ConnectionTestJvm {
    private var server: Process? = null
    private var serverOutputThread: Thread? = null
    private val serverReady = CountDownLatch(1)

    @BeforeTest
    fun startServer() {
        Logging.info(TAG, "startServer")
        val process = ProcessBuilder("node", "src/jvmTest/resources/socket-server.js", "/")
            .redirectErrorStream(true)
            .start()
        server = process
        serverOutputThread = thread(start = true, isDaemon = true, name = "socket-server-jvm-test-stdout") {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    Logging.info(TAG, "SERVER OUT: $line")
                    if (line.contains("Socket.IO server listening on port 3000")) {
                        serverReady.countDown()
                    }
                }
            }
        }
        check(serverReady.await(3, TimeUnit.SECONDS)) { "Socket.IO test server did not start in 3s" }
        Logging.info(TAG, "startServer finish")
    }

    @AfterTest
    fun stopServer() {
        Logging.info(TAG, "stopServer")
        serverReady.countDown()
        server?.let { process ->
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(3, TimeUnit.SECONDS)
            }
        }
        server = null

        serverOutputThread?.join(1000)
        serverOutputThread = null
        Logging.info(TAG, "stopServer finish")
    }

    @Test
    fun shouldExposeConnectionState() = runTest(timeout = 10.seconds) {
        withContext(Dispatchers.Default) {
            delay(1000)
        }

        val isConnectedBeforeOpen = CompletableDeferred<Boolean>()
        val isConnectedWhenConnectedEvent = CompletableDeferred<Boolean>()
        val isConnectedWhenDisconnectedEvent = CompletableDeferred<Boolean>()

        val opt = IO.Options()
        opt.transports = listOf(WebSocket.NAME)
        IO.socket("http://localhost:3000/", opt) { socket ->
            isConnectedBeforeOpen.complete(socket.connected)
            socket.on(Socket.EVENT_CONNECT) {
                isConnectedWhenConnectedEvent.complete(socket.connected)
                socket.close()
            }.on(Socket.EVENT_DISCONNECT) {
                isConnectedWhenDisconnectedEvent.complete(socket.connected)
            }

            socket.open()
        }

        assertFalse(isConnectedBeforeOpen.await())
        assertTrue(isConnectedWhenConnectedEvent.await())
        assertFalse(isConnectedWhenDisconnectedEvent.await())
    }

    companion object {
        private const val TAG = "ConnectionTestJvm"
    }
}
