package com.piasy.kmp.socketio.socketio

import com.piasy.kmp.socketio.emitter.Emitter
import com.piasy.kmp.socketio.engineio.State
import com.piasy.kmp.socketio.engineio.WorkThread
import com.piasy.kmp.xlog.Logging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Extension function to open Manager using coroutines.
 * Converts the callback-based open() method to a suspend function.
 * 
 * @throws Exception if connection fails or times out.
 */
@WorkThread
suspend fun Manager.openSuspend() {
    suspendCancellableCoroutine<Unit> { continuation ->
        var callbackInvoked = false
        open { error ->
            if (!callbackInvoked) {
                callbackInvoked = true
                if (error.isEmpty()) {
                    continuation.resume(Unit)
                } else {
                    continuation.resumeWithException(Exception(error))
                }
            }
        }
        continuation.invokeOnCancellation {
            // Cleanup if cancelled
        }
    }
}

/**
 * Extension function to open Socket using coroutines.
 * Converts the callback-based open() method to a suspend function.
 */
suspend fun Socket.openSuspend() {
    suspendCancellableCoroutine<Unit> { continuation ->
        val connectListener = object : Emitter.Listener {
            override fun call(vararg args: Any) {
                continuation.resume(Unit)
            }
        }
        
        // Subscribe to connect event
        on(Socket.EVENT_CONNECT, connectListener)
        
        // Also handle connection errors
        val errorListener = object : Emitter.Listener {
            override fun call(vararg args: Any) {
                val error = if (args.isNotEmpty() && args[0] is String) args[0] as String else "Connection error"
                continuation.resumeWithException(Exception(error))
            }
        }
        on(Socket.EVENT_CONNECT_ERROR, errorListener)
        
        continuation.invokeOnCancellation {
            off(Socket.EVENT_CONNECT, connectListener)
            off(Socket.EVENT_CONNECT_ERROR, errorListener)
        }
        
        // Start connection
        open()
    }
}

/**
 * Extension function to emit event using coroutines.
 * Supports both regular Ack and suspend lambda for acknowledgements.
 * 
 * @param event event name
 * @param args only accepts String/Boolean/Number/JsonElement/ByteString
 */
suspend fun Socket.emitSuspend(event: String, vararg args: Any) {
    // Check reserved events manually (since RESERVED_EVENTS is private)
    val reservedEvents = setOf(
        Socket.EVENT_CONNECT,
        Socket.EVENT_CONNECT_ERROR,
        Socket.EVENT_DISCONNECT,
        "disconnecting",
        "newListener",
        "removeListener",
    )
    if (reservedEvents.contains(event)) {
        Logging.error(Socket.TAG, "emit reserved event: $event")
        return
    }
    
    // Regular emit without ack - just call the existing method
    emit(event, *args)
}

/**
 * Extension function to emit event with suspend acknowledgement callback.
 * 
 * @param event event name
 * @param args only accepts String/Boolean/Number/JsonElement/ByteString
 * @param ack suspend lambda that will be called when acknowledgement is received (trailing lambda)
 */
suspend fun Socket.emitSuspend(event: String, vararg args: Any, ack: suspend (Array<out Any>) -> Unit) {
    // Check reserved events manually (since RESERVED_EVENTS is private)
    val reservedEvents = setOf(
        Socket.EVENT_CONNECT,
        Socket.EVENT_CONNECT_ERROR,
        Socket.EVENT_DISCONNECT,
        "disconnecting",
        "newListener",
        "removeListener",
    )
    if (reservedEvents.contains(event)) {
        Logging.error(Socket.TAG, "emit reserved event: $event")
        return
    }
    
    // Use suspendCancellableCoroutine to wait for ack
    suspendCancellableCoroutine<Unit> { continuation ->
        val ackWrapper = object : Ack {
            override fun call(vararg ackArgs: Any) {
                // Use CoroutineScope since socket's scope is private
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        ack(ackArgs)
                        continuation.resume(Unit)
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }
            }
        }
        
        // Emit with wrapped ack
        emit(event, *args, ackWrapper)
        
        continuation.invokeOnCancellation {
            // Cleanup if needed
        }
    }
}

/**
 * Extension function to create socket using coroutines.
 * Converts the callback-based socket() method to a suspend function.
 * 
 * @param uri socket server URI
 * @param opt socket options
 * @return Socket instance
 */
suspend fun IO.socketSuspend(uri: String, opt: IO.Options): Socket {
    return suspendCancellableCoroutine { continuation ->
        socket(uri, opt) { socket ->
            continuation.resume(socket)
        }
    }
}

