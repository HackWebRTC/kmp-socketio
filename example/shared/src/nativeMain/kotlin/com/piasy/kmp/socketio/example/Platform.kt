package com.piasy.kmp.socketio.example

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

fun main() {
    Greeting().greet()
    runBlocking {
        delay(10_000)
    }
}
