package com.piasy.kmp.socketio.socketio

import com.piasy.kmp.xlog.Logging
import io.ktor.client.*
import io.ktor.client.engine.curl.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import platform.posix.*
import kotlin.test.Test

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class ConnectionTestMingw : ConnectionTest() {
    private var pid = -1

    override fun startServer() {
        Logging.info(TAG, "startServer")
        pid = fork()
        if (pid == 0) {
            val command = "node src/jvmTest/resources/socket-server.js /"
            val res = execlp("/bin/sh", "sh", "-c", command, null)
            Logging.info(TAG, "startServer res: $res")
        } else {
            Logging.info(TAG, "startServer pid: $pid")
        }
    }

    override fun stopServer() {
        Logging.info(TAG, "stopServer pid: $pid")
        if (pid > 0) {
            val res1 = kill(pid, SIGINT)
            val res2 = kill(pid + 1, SIGINT)
            Logging.info(TAG, "stopServer kill res: $res1 $res2")
            val res3 = waitpid(pid, null, 0)
            val res4 = waitpid(pid + 1, null, 0)
            Logging.info(TAG, "stopServer wait res: $res3 $res4")
        }
    }

    @Test
    fun testSimultaneousRequests() = doTest {
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
                    }
                } catch (e: Exception) {
                    Logging.info(TAG, "Receive error while reading websocket frame: `${e.message}`")
                    break
                }
            }
        }
    }
}
