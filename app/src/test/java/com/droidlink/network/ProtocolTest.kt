package com.droidlink.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test fun screenFrameRoundTrip() {
        val jpeg = byteArrayOf(1, 2, 3, 4, 5)
        val decoded = BinaryFrames.decodeScreen(BinaryFrames.encodeScreen(1080, 2400, jpeg))
        assertNotNull(decoded)
        assertEquals(1080, decoded!!.width)
        assertEquals(2400, decoded.height)
        assertArrayEquals(jpeg, decoded.jpeg)
    }

    @Test fun malformedScreenFramesAreRejected() {
        assertNull(BinaryFrames.decodeScreen(ByteArray(0)))
        assertNull(BinaryFrames.decodeScreen(ByteArray(9)))
        assertNull(BinaryFrames.decodeScreen(byteArrayOf(0x02, 0, 0, 0, 1, 0, 0, 0, 1, 9)))
        assertNull(BinaryFrames.decodeScreen(BinaryFrames.encodeScreen(0, 100, byteArrayOf(1))))
        assertNull(BinaryFrames.decodeScreen(BinaryFrames.encodeScreen(100000, 100, byteArrayOf(1))))
    }

    @Test fun fileChunkRoundTripWithPartialBuffer() {
        val buf = ByteArray(10) { it.toByte() }
        val decoded = BinaryFrames.decodeChunk(BinaryFrames.encodeChunk("abc123", buf, 4))!!
        assertEquals("abc123", decoded.transferId)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), decoded.data)
    }

    @Test fun malformedChunksAreRejected() {
        assertNull(BinaryFrames.decodeChunk(byteArrayOf()))
        assertNull(BinaryFrames.decodeChunk(byteArrayOf(0x02, 0)))
        assertNull(BinaryFrames.decodeChunk(byteArrayOf(0x02, 9, 1, 2)))
        assertNull(BinaryFrames.decodeChunk(byteArrayOf(0x01, 1, 65)))
    }

    @Test fun controlMessagesSerialiseCompactly() {
        val json = RemoteMessage(t = MsgType.TAP, x = 0.25f, y = 0.5f).encode()
        assertTrue(json.contains("\"t\":\"tap\""))
        assertFalse("unused fields must be omitted", json.contains("null"))
        val back = decodeMessage(json)!!
        assertEquals(MsgType.TAP, back.t)
        assertEquals(0.25f, back.x!!, 0.0001f)
    }

    @Test fun unknownFieldsAreIgnoredAndGarbageIsNull() {
        assertEquals("nav", decodeMessage("""{"t":"nav","a":"home","future":123}""")!!.t)
        assertNull(decodeMessage("not json"))
        assertNull(decodeMessage("""{"x":1}"""))
    }
}
