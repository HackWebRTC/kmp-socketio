package com.piasy.kmp.socketio.engineio.transports

import io.ktor.client.HttpClientConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js

actual fun httpClient(trustAllCerts: Boolean, config: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(Js) {
    config(this)
    /** Ignore `unsafeClient` variable */
}
