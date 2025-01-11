package com.piasy.kmp.socketio.socketio

import com.piasy.kmp.socketio.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

interface Ack {
    fun call(vararg args: Any)
}

abstract class AckWithTimeout(val timeout: Long) : Ack {
    private var job: Job? = null

    override fun call(vararg args: Any) {
        Logger.info(TAG, "@${hashCode()} ack success")
        job?.cancel()
        onSuccess(*args)
    }

    internal fun schedule(scope: CoroutineScope, block: () -> Unit) {
        if (job != null) {
            Logger.error(TAG, "@${hashCode()} schedule error: already scheduled")
            return
        }
        Logger.info(TAG, "@${hashCode()} schedule ack timeout $timeout")
        job = scope.launch {
            delay(timeout)
            Logger.info(TAG, "@${hashCode()} ack timeout $timeout")
            block()
            onTimeout()
        }
    }

    internal fun cancel() {
        Logger.info(TAG, "@${hashCode()} cancel timeout")
        job?.cancel()
    }

    abstract fun onSuccess(vararg args: Any)
    abstract fun onTimeout()

    companion object {
        private const val TAG = "AckWithTimeout"
    }
}