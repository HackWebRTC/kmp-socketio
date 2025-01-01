package com.piasy.kmp.socketio.engineio

import com.piasy.kmp.socketio.emitter.Emitter
import com.piasy.kmp.socketio.logging.Logger
import com.piasy.kmp.socketio.logging.LoggerInterface
import io.ktor.util.date.*
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext
import kotlin.test.BeforeTest

fun verifyOn(emitter: Emitter, event: String) {
    verify(exactly = 1) { emitter.on(event, any()) }
}

fun on(
    emitter: Emitter, event: String, res: MutableList<String>,
    data: MutableMap<String, MutableList<Any>>
) {
    val listener = object : Emitter.Listener {
        override fun call(vararg args: Any) {
            res.add(event)
            if (args.isNotEmpty()) {
                data.getOrElse(event) {
                    val d = ArrayList<Any>()
                    data[event] = d
                    d
                }.addAll(args)
            }
        }
    }
    emitter.on(event, listener)
}

open class BaseTest {
    @BeforeTest
    fun setUp() {
        Logger.logger = object : LoggerInterface {
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
    }
}
