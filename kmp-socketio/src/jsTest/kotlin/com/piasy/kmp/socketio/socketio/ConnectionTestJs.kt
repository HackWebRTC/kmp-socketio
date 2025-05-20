package com.piasy.kmp.socketio.socketio

class ConnectionTestJs : ConnectionTest() {
    override fun startServer() {}

    override fun stopServer() {}

    // JS doesn't implement trustAllCerts, so skip this test
    override fun shouldConnectUntrusted() = doTest {  }
}
