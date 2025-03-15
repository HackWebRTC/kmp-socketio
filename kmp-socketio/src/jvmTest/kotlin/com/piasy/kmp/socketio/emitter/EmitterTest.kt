package com.piasy.kmp.socketio.emitter

import kotlin.test.Test
import kotlin.test.assertEquals

class EmitterTest {
    @Test
    fun `on with block`() {
        val events = ArrayList<Array<out Any>>()

        val emitter = Emitter()
        val event = "test"
        emitter.on(event) {
            events.add(it)
        }
        emitter.emit(event, 0, "1", 2)
        emitter.emit(event, 3, "4")

        assertEquals(2, events.size)

        assertEquals(3, events[0].size)
        assertEquals(0, events[0][0])
        assertEquals("1", events[0][1])
        assertEquals(2, events[0][2])

        assertEquals(2, events[1].size)
        assertEquals(3, events[1][0])
        assertEquals("4", events[1][1])
    }

    @Test
    fun `once with block`() {
        val events = ArrayList<Array<out Any>>()

        val emitter = Emitter()
        val event = "test"
        emitter.once(event) {
            events.add(it)
        }
        emitter.emit(event, 0, "1", 2)
        emitter.emit(event, 3, "4")

        assertEquals(1, events.size)

        assertEquals(3, events[0].size)
        assertEquals(0, events[0][0])
        assertEquals("1", events[0][1])
        assertEquals(2, events[0][2])
    }
}
