package com.piasy.kmp.socketio.example

import com.piasy.kmp.socketio.socketio.IO
import com.piasy.kmp.socketio.socketio.Socket
import io.ktor.util.date.GMTDate

class Greeting {
    fun greet() {
        IO.socket("http://172.16.11.25:3000", IO.Options()) { socket ->
            socket.on(Socket.EVENT_CONNECT) { args ->
                println("on connect ${args.joinToString()}")

                socket.emit("echo", 1, "2", GMTDate())
            }.on("echoBack") { args ->
                println("on echoBack ${args.joinToString()}")
            }

            socket.open()
        }
    }
}
