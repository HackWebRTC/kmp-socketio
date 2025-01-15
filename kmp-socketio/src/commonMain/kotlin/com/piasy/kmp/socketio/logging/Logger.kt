package com.piasy.kmp.socketio.logging

import io.ktor.util.date.*

interface LoggerInterface {
    fun debug(tag: String, log: String)
    fun info(tag: String, log: String)
    fun error(tag: String, log: String)
}

object Logger : LoggerInterface {
    private var logger: LoggerInterface = DefaultLogger

    fun setLogger(logger: LoggerInterface) {
        this.logger = logger
    }

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
        println("${GMTDate().timestamp} D $tag $log")
    }

    override fun info(tag: String, log: String) {
        println("${GMTDate().timestamp} I $tag $log")
    }

    override fun error(tag: String, log: String) {
        println("${GMTDate().timestamp} E $tag $log")
    }
}
