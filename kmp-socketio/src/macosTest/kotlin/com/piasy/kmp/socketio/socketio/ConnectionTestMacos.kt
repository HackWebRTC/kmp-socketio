package com.piasy.kmp.socketio.socketio

import com.kgit2.kommand.process.Child
import com.kgit2.kommand.process.Command
import com.kgit2.kommand.process.Stdio
import com.piasy.kmp.xlog.Logging

class ConnectionTestMacos : ConnectionTest() {
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
}
