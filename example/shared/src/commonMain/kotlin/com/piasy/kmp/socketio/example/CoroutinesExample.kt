package com.piasy.kmp.socketio.example

import com.piasy.kmp.socketio.socketio.*
import com.piasy.kmp.socketio.engineio.transports.WebSocket
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.unsafe.UnsafeByteStringApi
import kotlinx.io.bytestring.unsafe.UnsafeByteStringOperations

/**
 * Example demonstrating coroutines-based API using extension functions.
 */
class CoroutinesExample {
    @OptIn(UnsafeByteStringApi::class)
    fun example() = runBlocking {
        val opt = IO.Options()
        opt.transports = listOf(WebSocket.NAME)
        
        // Create socket using suspend function
        val socket = IO.socketSuspend("http://localhost:3000", opt)
        
        // ⚠️ IMPORTANT: Subscribe to events BEFORE opening connection!
        // This ensures you don't miss EVENT_CONNECT if connection is established quickly.
        // Order: 1) Create socket, 2) Subscribe to events, 3) Open connection
        
        // Monitor connection state using StateFlow
        launch {
            socket.connectedFlow.collect { connected ->
                println("Socket connected: $connected")
            }
        }
        
        // Monitor manager state using StateFlow
        launch {
            socket.io.stateFlow.collect { state ->
                println("Manager state: $state")
            }
        }
        
        // Listen to events using Flow
        launch {
            socket.flow(Socket.EVENT_CONNECT).collect { args ->
                println("Connected event received: ${args.joinToString()}")
            }
        }
        
        launch {
            socket.flow("echoBack").collect { args ->
                println("EchoBack event received: ${args.joinToString()}")
            }
        }
        
        // Now open the socket (after subscriptions are set up)
        socket.openSuspend()
        
        // Wait a bit for connection
        kotlinx.coroutines.delay(1000)
        
        // Emit event using suspend function
        val bin = UnsafeByteStringOperations.wrapUnsafe(byteArrayOf(0x1, 0x3, 0x1, 0x4))
        socket.emitSuspend("echo", 1, "2", bin, GMTDate())
        
        // Emit with suspend acknowledgement callback (trailing lambda syntax)
        socket.emitSuspend("echoWithAck", 42, "test") { args ->
            println("Acknowledgement received: ${args.joinToString()}")
        }
        
        // Keep running for a while to see events
        kotlinx.coroutines.delay(2000)
        
        socket.close()
    }

    /**
     * Example demonstrating error handling and subscription cancellation.
     * 
     * Common errors that can occur with socket.flow("someEvent"):
     * 1. CancellationException - when coroutine/job is cancelled
     * 2. Exceptions in collect block - if you throw inside collect { }
     * 3. Exceptions from listener callbacks - if Emitter.Listener.call() throws
     *    (Note: these are caught by Emitter, but won't propagate to Flow)
     * 4. ConcurrentModificationException - if you call on/once/off inside callback
     *    (Emitter is NOT thread-safe, see Emitter.kt comment)
     * 
     * Note: socket.flow() itself doesn't throw exceptions because:
     * - tryEmit() is non-blocking and doesn't throw
     * - on() just registers listener, doesn't throw
     * - Flow creation doesn't throw
     */
    fun exampleWithErrorHandling() = runBlocking {
        val opt = IO.Options()
        opt.transports = listOf(WebSocket.NAME)
        
        val socket = IO.socketSuspend("http://localhost:3000", opt)
        
        // Example 1: Handle errors in suspend functions with try-catch
        try {
            socket.openSuspend()
            socket.emitSuspend("someEvent", "data")
        } catch (e: Exception) {
            println("Error occurred: ${e.message}")
            // Handle error appropriately
        }
        
        // Example 2: Handle errors in Flow collection with catch
        // Note: StateFlow/Flow can throw CancellationException when cancelled
        val connectionJob = launch {
            socket.connectedFlow
                .catch { e ->
                    // Catches exceptions from upstream
                    // Most common: CancellationException when job.cancel() is called
                    when (e) {
                        is CancellationException -> {
                            println("Flow collection cancelled")
                            throw e // Re-throw to properly handle cancellation
                        }
                        else -> {
                            println("Error in connectedFlow: ${e.message}")
                            // You can emit a default value if needed
                            // But StateFlow doesn't support emit() in catch
                        }
                    }
                }
                .collect { connected ->
                    // Exceptions here won't be caught by .catch() above
                    println("Socket connected: $connected")
                }
        }
        
        // Example 3: Handle errors in event Flow with onCompletion
        // Note: socket.flow() itself doesn't throw exceptions, but errors can occur:
        // - CancellationException: when coroutine is cancelled
        // - Exceptions in collect block: if you throw inside collect
        // - Exceptions from listener callbacks: if callback throws (rare, but possible)
        val eventJob = launch {
            socket.flow("someEvent")
                .catch { e ->
                    // Catches exceptions from upstream (Flow itself)
                    // Note: tryEmit() in flow() doesn't throw, but if listener callback
                    // throws an exception, it won't be caught here (it's in Emitter's thread)
                    println("Error receiving event: ${e.message}")
                    // You can emit a default value or rethrow
                    // emit(emptyArray()) // if you want to continue
                }
                .onCompletion { cause ->
                    // Called when Flow completes (normally or with exception)
                    if (cause != null) {
                        println("Flow completed with error: ${cause.message}")
                        // cause can be:
                        // - CancellationException: when job is cancelled
                        // - Other exceptions from collect block
                    } else {
                        println("Flow completed normally")
                    }
                }
                .collect { args ->
                    // Exceptions here won't be caught by .catch() above
                    // They need try-catch inside collect or use onEach + catch pattern
                    try {
                        println("Event received: ${args.joinToString()}")
                        // If you throw here, it will stop the Flow
                        // check(args.isNotEmpty()) { "Empty args" } // would stop Flow
                    } catch (e: Exception) {
                        println("Error processing event: ${e.message}")
                        // Re-throw if you want to stop Flow, or handle silently
                    }
                }
        }
        
        // Example 4: Handle errors in emitSuspend with acknowledgement
        try {
            socket.emitSuspend("requestData", "param") { args ->
                try {
                    // Process acknowledgement
                    println("Ack received: ${args.joinToString()}")
                } catch (e: Exception) {
                    println("Error processing ack: ${e.message}")
                }
            }
        } catch (e: Exception) {
            println("Error emitting event: ${e.message}")
        }
        
        // Example 5: Listen to error events using Flow
        // This listens to Socket.IO error events (not Flow exceptions)
        val errorJob = launch {
            socket.flow(Socket.EVENT_CONNECT_ERROR)
                .catch { e ->
                    // Catches Flow-level exceptions (CancellationException, etc.)
                    // NOT Socket.IO error events (those come as args in collect)
                    when (e) {
                        is CancellationException -> {
                            println("Error event flow cancelled")
                            throw e
                        }
                        else -> {
                            println("Error in error event flow: ${e.message}")
                        }
                    }
                }
                .onEach { args ->
                    // Use onEach + catch pattern to catch exceptions in processing
                    // This way catch() can intercept exceptions from onEach
                    println("Connection error event: ${args.joinToString()}")
                    // If you throw here, catch() will catch it
                }
                .catch { e ->
                    println("Error processing error event: ${e.message}")
                }
                .collect() // Empty collect since we use onEach
        }
        
        // Alternative: Handle errors in collect with try-catch
        val errorJob2 = launch {
            try {
                socket.flow(Socket.EVENT_CONNECT_ERROR).collect { args ->
                    println("Connection error: ${args.joinToString()}")
                    // Handle connection error
                }
            } catch (e: CancellationException) {
                println("Error flow cancelled")
                throw e // Re-throw cancellation
            } catch (e: Exception) {
                println("Unexpected error: ${e.message}")
            }
        }
        
        socket.openSuspend()
        delay(1000)
        
        // Cancel subscriptions when done
        connectionJob.cancel()
        eventJob.cancel()
        errorJob.cancel()
        
        socket.close()
    }

    /**
     * Example demonstrating how to close old socket and reconnect with new parameters.
     */
    fun exampleReconnectWithNewParameters() = runBlocking {
        // Step 1: Create and use initial socket
        var socket: Socket? = null
        
        try {
            val initialOpt = IO.Options()
            initialOpt.transports = listOf(WebSocket.NAME)
            initialOpt.auth = mapOf("token" to "old_token")
            
            socket = IO.socketSuspend("http://localhost:3000", initialOpt)
            
            // Monitor connection
            val connectionJob = launch {
                socket?.connectedFlow?.collect { connected ->
                    println("Initial socket connected: $connected")
                }
            }
            
            socket.openSuspend()
            delay(1000)
            
            // Use socket for some operations
            socket.emitSuspend("someEvent", "data")
            
            // Step 2: Close old socket properly
            println("Closing old socket...")
            connectionJob.cancel() // Cancel Flow collection first
            socket.close() // Close the socket
            socket = null // Clear reference
            
            // Wait a bit for cleanup
            delay(500)
            
            // Step 3: Create new socket with new parameters
            val newOpt = IO.Options()
            newOpt.transports = listOf(WebSocket.NAME)
            newOpt.auth = mapOf("token" to "new_token") // New auth token
            newOpt.forceNew = true // Force new connection
            
            socket = IO.socketSuspend("http://localhost:3000", newOpt)
            
            // Monitor new connection
            val newConnectionJob = launch {
                socket?.connectedFlow?.collect { connected ->
                    println("New socket connected: $connected")
                }
            }
            
            socket.openSuspend()
            delay(1000)
            
            // Use new socket
            socket.emitSuspend("someEvent", "new_data")
            
            delay(1000)
            
            // Cleanup
            newConnectionJob.cancel()
            socket?.close()
            
        } catch (e: Exception) {
            println("Error in reconnect example: ${e.message}")
            socket?.close() // Ensure socket is closed on error
        }
    }

    /**
     * Example demonstrating proper cleanup with coroutine scope.
     */
    fun exampleWithScopeCleanup() = runBlocking {
        coroutineScope {
            val opt = IO.Options()
            opt.transports = listOf(WebSocket.NAME)
            
            val socket = IO.socketSuspend("http://localhost:3000", opt)
            
            // Collect flows in separate coroutines
            val connectionJob = launch {
                socket.connectedFlow
                    .catch { e -> println("Connection flow error: ${e.message}") }
                    .collect { connected ->
                        println("Connected: $connected")
                    }
            }
            
            val eventJob = launch {
                socket.flow("someEvent")
                    .catch { e -> println("Event flow error: ${e.message}") }
                    .collect { args ->
                        println("Event: ${args.joinToString()}")
                    }
            }
            
            try {
                socket.openSuspend()
                delay(1000)
                socket.emitSuspend("test", "data")
                delay(1000)
            } catch (e: Exception) {
                println("Error: ${e.message}")
            } finally {
                // All jobs will be cancelled when scope completes
                // But we can also cancel manually if needed
                connectionJob.cancel()
                eventJob.cancel()
                socket.close()
            }
        }
        // All coroutines are automatically cancelled when scope completes
    }

    /**
     * Example demonstrating reconnection handling with error recovery.
     */
    fun exampleReconnectionHandling() = runBlocking {
        val opt = IO.Options()
        opt.transports = listOf(WebSocket.NAME)
        opt.reconnection = true // Enable automatic reconnection
        opt.reconnectionAttempts = 5
        opt.reconnectionDelay = 1000
        
        val socket = IO.socketSuspend("http://localhost:3000", opt)
        
        // Monitor reconnection events
        // Note: Flow errors here are typically CancellationException when cancelled
        val reconnectJob = launch {
            socket.io.flow(Manager.EVENT_RECONNECT)
                .catch { e ->
                    when (e) {
                        is CancellationException -> {
                            println("Reconnect flow cancelled")
                            throw e // Properly handle cancellation
                        }
                        else -> {
                            println("Reconnect flow error: ${e.message}")
                        }
                    }
                }
                .collect { args ->
                    // Safe access with getOrNull to avoid IndexOutOfBoundsException
                    println("Reconnected! Attempt: ${args.getOrNull(0)}")
                }
            }
        
        val reconnectAttemptJob = launch {
            socket.io.flow(Manager.EVENT_RECONNECT_ATTEMPT)
                .catch { e -> println("Reconnect attempt flow error: ${e.message}") }
                .collect { args ->
                    println("Reconnection attempt: ${args.getOrNull(0)}")
                }
            }
        
        val reconnectErrorJob = launch {
            socket.io.flow(Manager.EVENT_RECONNECT_ERROR)
                .catch { e -> println("Reconnect error flow error: ${e.message}") }
                .collect { args ->
                    println("Reconnection error: ${args.joinToString()}")
                }
            }
        
        val reconnectFailedJob = launch {
            socket.io.flow(Manager.EVENT_RECONNECT_FAILED)
                .catch { e -> println("Reconnect failed flow error: ${e.message}") }
                .collect {
                    println("Reconnection failed - all attempts exhausted")
                    // Handle failed reconnection - maybe create new socket with new params
                }
            }
        
        try {
            socket.openSuspend()
            delay(2000)
            
            // Simulate connection loss - socket will automatically try to reconnect
            // In real scenario, connection might be lost due to network issues
            
        } catch (e: Exception) {
            println("Error: ${e.message}")
        } finally {
            // Cleanup
            reconnectJob.cancel()
            reconnectAttemptJob.cancel()
            reconnectErrorJob.cancel()
            reconnectFailedJob.cancel()
            socket.close()
        }
    }

    /**
     * Detailed example explaining what errors can occur with socket.flow("someEvent")
     * 
     * IMPORTANT: socket.flow() itself does NOT throw exceptions because:
     * 1. tryEmit() is non-blocking and returns Boolean (doesn't throw)
     * 2. on() just registers a listener (doesn't throw)
     * 3. Flow creation doesn't throw
     * 
     * However, errors CAN occur during Flow collection:
     */
    fun exampleFlowErrorsDetailed() = runBlocking {
        val socket = IO.socketSuspend("http://localhost:3000", IO.Options())
        
        // ERROR TYPE 1: CancellationException
        // This is the MOST COMMON error - occurs when coroutine/job is cancelled
        val job1 = launch {
            try {
                socket.flow("someEvent").collect { args ->
                    println("Event: ${args.joinToString()}")
                }
            } catch (e: CancellationException) {
                // This is NORMAL when job.cancel() is called
                println("Flow collection cancelled (this is expected)")
                throw e // Re-throw to properly handle cancellation
            }
        }
        
        delay(1000)
        job1.cancel() // This will cause CancellationException in collect
        
        // ERROR TYPE 2: Exceptions thrown inside collect block
        // These are NOT caught by .catch() operator (catch only catches upstream errors)
        val job2 = launch {
            socket.flow("someEvent")
                .catch { e ->
                    // This WON'T catch exceptions from collect block below
                    println("Caught upstream error: ${e.message}")
                }
                .collect { args ->
                    // If you throw here, it will stop the Flow
                    // and WON'T be caught by .catch() above
                    if (args.isEmpty()) {
                        throw IllegalArgumentException("Empty args") // This stops Flow
                    }
                    println("Event: ${args.joinToString()}")
                }
        }
        
        // Solution: Use onEach + catch pattern
        val job3 = launch {
            socket.flow("someEvent")
                .onEach { args ->
                    // Process event here - exceptions will be caught by catch below
                    if (args.isEmpty()) {
                        throw IllegalArgumentException("Empty args")
                    }
                    println("Event: ${args.joinToString()}")
                }
                .catch { e ->
                    // Now this WILL catch exceptions from onEach
                    println("Caught error in onEach: ${e.message}")
                    // Flow continues (doesn't stop)
                }
                .collect() // Empty collect since we use onEach
        }
        
        // ERROR TYPE 3: Exceptions in try-catch around collect
        val job4 = launch {
            try {
                socket.flow("someEvent").collect { args ->
                    // Exceptions here will be caught by outer try-catch
                    check(args.isNotEmpty()) { "Empty args" }
                    println("Event: ${args.joinToString()}")
                }
            } catch (e: CancellationException) {
                println("Cancelled: ${e.message}")
                throw e // Re-throw cancellation
            } catch (e: IllegalArgumentException) {
                println("Invalid args: ${e.message}")
                // Flow stops here
            } catch (e: Exception) {
                println("Unexpected error: ${e.message}")
            }
        }
        
        // ERROR TYPE 4: IndexOutOfBoundsException when accessing args
        val job5 = launch {
            socket.flow("someEvent").collect { args ->
                // Safe access to avoid IndexOutOfBoundsException
                val firstArg = args.getOrNull(0) // Safe - returns null if index out of bounds
                // val firstArg = args[0] // Unsafe - throws IndexOutOfBoundsException if empty
                
                println("First arg: $firstArg")
            }
        }
        
        // ERROR TYPE 5: Type casting exceptions
        val job6 = launch {
            socket.flow("someEvent").collect { args ->
                // Safe casting to avoid ClassCastException
                val stringArg = args.getOrNull(0) as? String // Safe cast - returns null if wrong type
                // val stringArg = args[0] as String // Unsafe - throws ClassCastException if wrong type
                
                println("String arg: $stringArg")
            }
        }
        
        socket.openSuspend()
        delay(1000)
        
        // Cleanup
        job2.cancel()
        job3.cancel()
        job4.cancel()
        job5.cancel()
        job6.cancel()
        socket.close()
    }

    /**
     * Example demonstrating the importance of subscription order.
     * 
     * IMPORTANT: Order matters for EVENT_CONNECT!
     * 
     * Why:
     * 1. socket.open() is asynchronous (runs in scope.launch)
     * 2. EVENT_CONNECT is emitted when server responds (in onConnect())
     * 3. If you subscribe AFTER open(), you might miss EVENT_CONNECT if connection
     *    is established very quickly (race condition)
     * 
     * Best practice: Subscribe BEFORE opening connection
     */
    fun exampleSubscriptionOrder() = runBlocking {
        val socket = IO.socketSuspend("http://localhost:3000", IO.Options())
        
        // ✅ CORRECT: Subscribe BEFORE opening
        val connectJob = launch {
            socket.flow(Socket.EVENT_CONNECT)
                .collect { args ->
                    println("Connected! (subscribed before open)")
                }
        }
        
        // Subscribe to other events before opening
        val eventJob = launch {
            socket.flow("someEvent")
                .collect { args ->
                    println("Event received: ${args.joinToString()}")
                }
        }
        
        // Now open the connection
        socket.openSuspend()
        delay(1000)
        
        // Cleanup
        connectJob.cancel()
        eventJob.cancel()
        socket.close()
        
        // ❌ WRONG: Subscribe AFTER opening (might miss EVENT_CONNECT)
        val socket2 = IO.socketSuspend("http://localhost:3000", IO.Options())
        socket2.openSuspend() // Opening first
        
        delay(100) // Even small delay might be enough for connection
        
        // This might miss EVENT_CONNECT if connection was fast
        val connectJob2 = launch {
            socket2.flow(Socket.EVENT_CONNECT)
                .collect { args ->
                    println("Connected! (might be missed)")
                }
        }
        
        delay(1000)
        connectJob2.cancel()
        socket2.close()
        
        // ✅ SOLUTION: Use flowWithReplay to get last event
        val socket3 = IO.socketSuspend("http://localhost:3000", IO.Options())
        socket3.openSuspend()
        delay(100)
        
        // flowWithReplay(1) will replay the last EVENT_CONNECT if it already happened
        val connectJob3 = launch {
            socket3.flowWithReplay(Socket.EVENT_CONNECT, replay = 1)
                .collect { args ->
                    println("Connected! (using replay - safe even if subscribed late)")
                }
        }
        
        delay(1000)
        connectJob3.cancel()
        socket3.close()
        
        // ✅ ALTERNATIVE: Use StateFlow for connection state (always has current value)
        val socket4 = IO.socketSuspend("http://localhost:3000", IO.Options())
        socket4.openSuspend()
        delay(100)
        
        // connectedFlow always has current state, no race condition
        val isConnected = socket4.connectedFlow.value
        println("Is connected: $isConnected") // Will show current state
        
        val connectJob4 = launch {
            socket4.connectedFlow.collect { connected ->
                println("Connection state: $connected") // Will get current + future changes
            }
        }
        
        delay(1000)
        connectJob4.cancel()
        socket4.close()
    }
}
