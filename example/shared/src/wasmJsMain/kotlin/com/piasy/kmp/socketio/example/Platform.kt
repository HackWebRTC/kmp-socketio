package com.piasy.kmp.socketio.example

import kotlinx.browser.document

fun main() {
    document.getElementById("text")?.innerHTML = "hello world"
    Greeting().greet()
}
