package com.piasy.kmp.socketio.socketio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class ConnectionTestMingw : ConnectionTest() {
    private val ioScope: CoroutineScope = CoroutineScope(Dispatchers.Default)
    private var pid = -1

    override fun startServer() {
        TODO("Not yet implemented")
    }

    override fun stopServer() {
        TODO("Not yet implemented")
    }
}
