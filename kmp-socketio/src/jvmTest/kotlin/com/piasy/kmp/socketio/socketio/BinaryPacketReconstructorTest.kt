package com.piasy.kmp.socketio.socketio

import kotlinx.io.bytestring.unsafe.UnsafeByteStringApi
import kotlinx.io.bytestring.unsafe.UnsafeByteStringOperations
import kotlinx.serialization.json.JsonPrimitive
import org.hildan.socketio.PayloadElement
import org.hildan.socketio.SocketIOPacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(UnsafeByteStringApi::class)
class BinaryPacketReconstructorTest {

    @Test
    fun `normal reconstruct`() {
        val dataList = ArrayList<ArrayList<Any>>()

        val payload = listOf(
            PayloadElement.Json(JsonPrimitive(1)),
            PayloadElement.AttachmentRef(0)
        )
        val packet = SocketIOPacket.BinaryEvent("ns", null, payload, 1)
        val reconstructor = BinaryPacketReconstructor(packet) { isAck, ackId, data ->
            assertFalse(isAck)
            assertNull(ackId)
            dataList.add(data)
        }

        val bin = UnsafeByteStringOperations.wrapUnsafe(byteArrayOf(0x1, 0x3, 0x1, 0x4))
        reconstructor.add(bin)

        assertEquals(1, dataList.size)
        assertEquals(JsonPrimitive(1), dataList[0][0])
        assertEquals(bin, dataList[0][1])
    }

    @Test
    fun `wrong attachment index`() {
        val dataList = ArrayList<ArrayList<Any>>()

        val payload = listOf(
            PayloadElement.Json(JsonPrimitive(1)),
            PayloadElement.AttachmentRef(1)
        )
        val packet = SocketIOPacket.BinaryEvent("ns", null, payload, 1)
        val reconstructor = BinaryPacketReconstructor(packet) { isAck, ackId, data ->
            assertFalse(isAck)
            assertNull(ackId)
            dataList.add(data)
        }

        val bin = UnsafeByteStringOperations.wrapUnsafe(byteArrayOf(0x1, 0x3, 0x1, 0x4))
        reconstructor.add(bin)

        assertEquals(0, dataList.size)
    }
}
