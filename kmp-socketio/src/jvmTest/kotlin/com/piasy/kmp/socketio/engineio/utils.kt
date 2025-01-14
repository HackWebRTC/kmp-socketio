package com.piasy.kmp.socketio.engineio

import com.piasy.kmp.socketio.emitter.Emitter
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.BeforeTest

fun verifyOn(emitter: Emitter, event: String) {
    verify(exactly = 1) { emitter.on(event, any<Emitter.Listener>()) }
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

fun mockOpen(
    upgrades: List<String> = emptyList(),
    pingInterval: Int = 25000,
    pingTimeout: Int = 20000
): String {
    val jsonHandshake = """{"sid":"lv_VI97HAXpY6yYWAAAC",
            |"upgrades":${Json.encodeToString(upgrades)},"pingInterval":$pingInterval,
            |"pingTimeout":$pingTimeout,"maxPayload":1000000}"""
        .trimMargin()
    return "0$jsonHandshake"
}

open class BaseTest {
    @BeforeTest
    fun setUp() {
    }

    protected suspend fun waitExec(scope: TestScope, millis: Long = 300) {
        scope.advanceUntilIdle()
        withContext(Dispatchers.Default) {
            delay(millis)
        }
        scope.advanceUntilIdle()
    }
}
