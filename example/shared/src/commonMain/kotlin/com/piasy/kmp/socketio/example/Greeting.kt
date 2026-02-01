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
        val opt = IO.Options()
        //opt.trustAllCerts = true
        IO.socket("http://172.16.11.186:3000", opt) { socket ->
            socket.on(Socket.EVENT_CONNECT) { args ->
                println("Greeting on connect ${args.joinToString()}")

                val bin = UnsafeByteStringOperations.wrapUnsafe(byteArrayOf(0x1, 0x3, 0x1, 0x4))
                socket.emit("echo", 1, "2", bin, GMTDate())
            }.on("echoBack") { args ->
                println("Greeting on echoBack ${args.joinToString()}")
            }

            socket.open()
        }
    }
}
