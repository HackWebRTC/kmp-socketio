package com.piasy.kmp.socketio.engineio

enum class State {
    INIT, OPENING, OPEN, CLOSING, CLOSED, PAUSED
}

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class WorkThread

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class IoThread

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class CallerThread
