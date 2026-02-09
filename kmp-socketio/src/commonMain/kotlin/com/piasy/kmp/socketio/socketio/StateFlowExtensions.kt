package com.piasy.kmp.socketio.socketio

import com.piasy.kmp.socketio.emitter.Emitter
import com.piasy.kmp.socketio.engineio.State
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Extension property to get StateFlow for Manager state.
 * Creates a StateFlow that tracks the Manager's state changes through events.
 */
val Manager.stateFlow: StateFlow<State>
    get() {
        val stateFlow = MutableStateFlow<State>(state)
        
        // Track state through OPEN and CLOSE events
        val openListener = object : Emitter.Listener {
            override fun call(vararg args: Any) {
                stateFlow.value = State.OPEN
            }
        }
        val closeListener = object : Emitter.Listener {
            override fun call(vararg args: Any) {
                stateFlow.value = State.CLOSED
            }
        }
        
        on(Manager.EVENT_OPEN, openListener)
        on(Manager.EVENT_CLOSE, closeListener)
        
        return stateFlow.asStateFlow()
    }

/**
 * Extension property to get StateFlow for Socket connection state.
 * Creates a StateFlow that tracks whether the socket is connected.
 */
val Socket.connectedFlow: StateFlow<Boolean>
    get() {
        // Track connection state through events since 'connected' is private
        val stateFlow = MutableStateFlow<Boolean>(false)
        
        // Subscribe to connect/disconnect events
        on(Socket.EVENT_CONNECT) {
            stateFlow.value = true
        }
        on(Socket.EVENT_DISCONNECT) {
            stateFlow.value = false
        }
        on(Socket.EVENT_CONNECT_ERROR) {
            stateFlow.value = false
        }
        
        return stateFlow.asStateFlow()
    }

/**
 * Extension property to get StateFlow for Socket ID.
 * Creates a StateFlow that tracks the socket ID.
 */
val Socket.socketIdFlow: StateFlow<String>
    get() {
        val stateFlow = MutableStateFlow<String>(id)
        
        // Subscribe to connect event to get ID
        on(Socket.EVENT_CONNECT) {
            stateFlow.value = id
        }
        on(Socket.EVENT_DISCONNECT) {
            stateFlow.value = ""
        }
        
        return stateFlow.asStateFlow()
    }

/**
 * Extension function to get Flow for Emitter events.
 * Allows using coroutines to listen to events.
 * 
 * @param event event name
 * @return Flow that emits event arguments
 */
fun Emitter.flow(event: String): Flow<Array<out Any>> {
    val sharedFlow = MutableSharedFlow<Array<out Any>>(
        replay = 0,
        extraBufferCapacity = Channel.UNLIMITED
    )
    
    val listener = object : Emitter.Listener {
        override fun call(vararg args: Any) {
            sharedFlow.tryEmit(args)
        }
    }
    
    on(event, listener)
    
    return sharedFlow.asSharedFlow()
}

/**
 * Extension function to get Flow for Emitter events with replay buffer.
 * 
 * @param event event name
 * @param replay number of events to replay to new subscribers
 * @return Flow that emits event arguments
 */
fun Emitter.flowWithReplay(event: String, replay: Int = 1): Flow<Array<out Any>> {
    val sharedFlow = MutableSharedFlow<Array<out Any>>(
        replay = replay,
        extraBufferCapacity = Channel.UNLIMITED
    )
    
    val listener = object : Emitter.Listener {
        override fun call(vararg args: Any) {
            sharedFlow.tryEmit(args)
        }
    }
    
    on(event, listener)
    
    return sharedFlow.asSharedFlow()
}
