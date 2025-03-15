package com.piasy.kmp.socketio.socketio

import com.kgit2.kommand.process.Child
import com.kgit2.kommand.process.Command
import com.kgit2.kommand.process.Stdio
import com.piasy.kmp.xlog.Logging
import io.ktor.client.*
import io.ktor.client.engine.curl.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.hildan.socketio.EngineIO
import org.hildan.socketio.EngineIOPacket
import org.hildan.socketio.SocketIO
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConnectionTestLinux : ConnectionTest() {
    private var server: Child? = null

    override fun startServer() {
        Logging.info(TAG, "startServer")
        server = Command("node")
            .args(listOf("src/jvmTest/resources/socket-server.js", "/"))
            .stdout(Stdio.Inherit)
            .spawn()
        Logging.info(TAG, "startServer finish")
    }

    override fun stopServer() {
        Logging.info(TAG, "stopServer")
        server?.kill()
        Logging.info(TAG, "stopServer finish")
    }

    @Test
    fun `simultaneous ws and http request with two http clients works`() = doTest {
        val config: HttpClientConfig<*>.() -> Unit = {
            install(io.ktor.client.plugins.logging.Logging) {
                logger = object : Logger {
                    override fun log(message: String) {
                        Logging.info("Net", message)
                    }
                }
                level = LogLevel.ALL
            }
            install(WebSockets) {
                pingIntervalMillis = 20_000
            }
        }
        val wsClient = HttpClient(Curl) {
            config(this)
        }
        val httpClient = HttpClient(Curl) {
            config(this)
        }

        var pollRes = ""
        wsClient.webSocket("ws://localhost:3000/socket.io/?EIO=4&transport=websocket", {}) {
            Logging.info(TAG, "sent ws request")

            while (true) {
                try {
                    val frame = incoming.receive()
                    Logging.info(TAG, "Receive frame: $frame")

                    val resp = httpClient.request("http://localhost:3000/socket.io/?EIO=4&transport=polling") {
                        this.method = HttpMethod.Get
                    }
                    Logging.info(TAG, "http response status: ${resp.status}")
                    if (resp.status.isSuccess()) {
                        pollRes = resp.bodyAsText()
                        Logging.info(TAG, "http response body: $pollRes")
                        break
                    }
                } catch (e: Exception) {
                    Logging.info(TAG, "Receive error while reading websocket frame: `${e.message}`")
                    break
                }
            }
        }

        val pkt = EngineIO.decodeHttpBatch(pollRes, SocketIO::decode)[0]
        assertTrue(pkt is EngineIOPacket.Open)
    }

    @Test
    fun `simultaneous ws and http request with a single http client will hang`() = doTest {
        val config: HttpClientConfig<*>.() -> Unit = {
            install(io.ktor.client.plugins.logging.Logging) {
                logger = object : Logger {
                    override fun log(message: String) {
                        Logging.info("Net", message)
                    }
                }
                level = LogLevel.ALL
            }
            install(WebSockets) {
                pingIntervalMillis = 20_000
            }
        }
        val client = HttpClient(Curl) {
            config(this)
        }

        assertFailsWith<TimeoutCancellationException> {
            withContext(Dispatchers.Default) {
                withTimeout(5000) {
                    client.webSocket("ws://localhost:3000/socket.io/?EIO=4&transport=websocket", {}) {
                        Logging.info(TAG, "sent ws request")

                        while (true) {
                            try {
                                val frame = incoming.receive()
                                Logging.info(TAG, "Receive frame: $frame")

                                val resp = client.request("http://localhost:3000/socket.io/?EIO=4&transport=polling") {
                                    this.method = HttpMethod.Get
                                }
                                Logging.info(TAG, "http response status: ${resp.status}")
                                if (resp.status.isSuccess()) {
                                    val body = resp.bodyAsText()
                                    Logging.info(TAG, "http response body: $body")
                                    break
                                }
                            } catch (e: Exception) {
                                Logging.info(TAG, "Receive error while reading websocket frame: `${e.message}`")
                                break
                            }
                        }
                    }
                }
            }
        }
    }
}
