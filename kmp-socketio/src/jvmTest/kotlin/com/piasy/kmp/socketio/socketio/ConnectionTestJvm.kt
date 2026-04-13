package com.piasy.kmp.socketio.socketio

import com.piasy.kmp.xlog.Logging
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

class ConnectionTestJvm : ConnectionTest() {
    private var server: Process? = null
    private var serverOutputThread: Thread? = null

    @BeforeTest
    override fun startServer() {
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
                }
            }
        }
        Logging.info(TAG, "startServer finish")
    }

    @AfterTest
    override fun stopServer() {
        Logging.info(TAG, "stopServer")
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
}
