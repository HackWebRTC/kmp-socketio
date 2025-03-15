package com.piasy.kmp.socketio.socketio

import com.piasy.kmp.xlog.Logging
import kotlinx.cinterop.*
import platform.posix.*

private fun doStartServer(ptr: COpaquePointer?): COpaquePointer? {
    //val command = "cmd /c node src/jvmTest/resources/socket-server.js /"
    val command = "powershell -File src/mingwTest/start_server.ps1"
    system(command)
    return null
}

class ConnectionTestMingw : ConnectionTest() {

    override fun startServer() {
        Logging.info(TAG, "startServer")
        memScoped {
            val threadId = alloc<pthread_tVar>()
            val res = pthread_create(threadId.ptr, null, staticCFunction(::doStartServer), null)
            Logging.info(TAG, "startServer res $res, tid: ${threadId.value}")
        }
    }

    override fun stopServer() {
        Logging.info(TAG, "stopServer start")
        val command = "powershell -File src/mingwTest/stop_server.ps1"
        system(command)
        Logging.info(TAG, "stopServer finish")
    }
}
