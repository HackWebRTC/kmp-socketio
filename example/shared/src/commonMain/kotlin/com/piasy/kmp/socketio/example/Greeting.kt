package com.piasy.kmp.socketio.example

import com.piasy.kmp.socketio.socketio.IO
import com.piasy.kmp.socketio.socketio.Socket
import io.ktor.util.date.GMTDate
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.unsafe.UnsafeByteStringApi
import kotlinx.io.bytestring.unsafe.UnsafeByteStringOperations

class Greeting {
    @OptIn(UnsafeByteStringApi::class)
    fun greet() {
        IO.socket("http://172.16.11.25:3000", IO.Options()) { socket ->
            socket.on(Socket.EVENT_CONNECT) { args ->
                println("on connect ${args.joinToString()}")

                val bin = UnsafeByteStringOperations.wrapUnsafe(byteArrayOf(0x1, 0x3, 0x1, 0x4))
                socket.emit("echo", 1, "2", bin, GMTDate())
            }.on("echoBack") { args ->
                println("on echoBack ${args.joinToString()}")
            }

            socket.open()
        }
    }
}
