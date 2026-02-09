package com.piasy.kmp.socketio.socketio

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.*
import kotlin.jvm.JvmStatic

class CoroutinesExtensionsTestJvm : CoroutinesExtensionsTest() {
    private var serverProcess: Process? = null
    private var serverService: ExecutorService? = null
    private var serverOutput: Future<*>? = null
    private var serverError: Future<*>? = null

    override fun startServer() {
        com.piasy.kmp.xlog.Logging.info(TAG, "Starting server ...")
        val latch = CountDownLatch(1)
        serverProcess = Runtime.getRuntime().exec(
            "node src/jvmTest/resources/socket-server.js /",
            createEnv()
        )
        serverService = Executors.newCachedThreadPool()
        serverOutput = serverService!!.submit {
            val reader = BufferedReader(InputStreamReader(serverProcess!!.inputStream))
            var line = reader.readLine()
            latch.countDown()
            while (line != null) {
                com.piasy.kmp.xlog.Logging.info(TAG, "SERVER OUT: $line")
                line = reader.readLine()
            }
        }
        serverError = serverService!!.submit {
            val reader = BufferedReader(InputStreamReader(serverProcess!!.errorStream))
            var line = reader.readLine()
            while (line != null) {
                com.piasy.kmp.xlog.Logging.info(TAG, "SERVER ERR: $line")
                line = reader.readLine()
            }
        }
        latch.await(3, TimeUnit.SECONDS)
    }

    override fun stopServer() {
        com.piasy.kmp.xlog.Logging.info(TAG, "Stopping server ...")
        serverProcess?.destroy()
        serverOutput?.cancel(false)
        serverError?.cancel(false)
        serverService?.shutdown()
        serverService?.awaitTermination(3, TimeUnit.SECONDS)
    }

    private fun createEnv(): Array<String> {
        val env = System.getenv().toMutableMap()
        env["PORT"] = "3000"
        return env.map { "${it.key}=${it.value}" }.toTypedArray()
    }

    companion object {
        const val TAG = "CoroutinesExtensionsTestJvm"
    }
}
