package com.piasy.kmp.socketio.logging

interface LoggerInterface {
    fun debug(tag: String, log: String)
    fun info(tag: String, log: String)
    fun error(tag: String, log: String)
}

internal object Logger : LoggerInterface {
    internal var logger: LoggerInterface = DefaultLogger

    override fun debug(tag: String, log: String) {
        logger.debug(tag, log)
    }

    override fun info(tag: String, log: String) {
        logger.info(tag, log)
    }

    override fun error(tag: String, log: String) {
        logger.error(tag, log)
    }
}

private object DefaultLogger : LoggerInterface {
    override fun debug(tag: String, log: String) {
        println("D $tag $log")
    }

    override fun info(tag: String, log: String) {
        println("I $tag $log")
    }

    override fun error(tag: String, log: String) {
        println("E $tag $log")
    }
}
