package isro.itantra.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTest {

    private fun textFrame(text: String = "नमस्ते दुनिया hello", alert: Boolean = true, timing: Boolean = true) =
        Wire.Frame(
            type = Wire.TYPE_TEXT,
            flags = ((if (alert) Wire.FLAG_ALERT.toInt() else 0) or
                Wire.FLAG_ACK_REQ.toInt() or
                (if (timing) Wire.FLAG_HAS_TIMING.toInt() else 0)).toByte(),
            langId = Wire.langId("hi"),
            msgId = 0x01020304L,
            payload = text.toByteArray(Charsets.UTF_8),
            tSpeechEndUs = 1234567890L,
            tSendUs = 1234567999L,
        )

    @Test
    fun `round trip text frame with timing`() {
        val bytes = Wire.encode(textFrame())
        val r = Wire.parse(bytes, bytes.size)
        assertTrue(r is Wire.ParseResult.Ok)
        val f = (r as Wire.ParseResult.Ok).frame
        assertEquals(Wire.TYPE_TEXT, f.type)
        assertTrue(f.alert)
        assertTrue(f.ackReq)
        assertEquals(Wire.langId("hi"), f.langId)
        assertEquals(0x01020304L, f.msgId)
        assertEquals("नमस्ते दुनिया hello", f.text)
        assertEquals(1234567890L, f.tSpeechEndUs)
        assertEquals(1234567999L, f.tSendUs)
        assertEquals(bytes.size, r.consumed)
    }

    @Test
    fun `round trip without timing`() {
        val bytes = Wire.encode(textFrame(timing = false))
        val f = (Wire.parse(bytes, bytes.size) as Wire.ParseResult.Ok).frame
        assertNull(f.tSpeechEndUs)
        assertTrue(f.text.isNotEmpty())
    }

    @Test
    fun `corrupted crc is bad`() {
        val bytes = Wire.encode(textFrame())
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x55).toByte()
        assertTrue(Wire.parse(bytes, bytes.size) is Wire.ParseResult.Bad)
    }

    @Test
    fun `need more for truncated frame`() {
        val bytes = Wire.encode(textFrame())
        assertTrue(Wire.parse(bytes.copyOfRange(0, 13), 13) is Wire.ParseResult.NeedMore)
    }

    @Test
    fun `stream parser resyncs after garbage prefix`() {
        val good = Wire.encode(textFrame())
        val stream = byteArrayOf(0x00, 0x01, 0x02, MAGIC_GARBAGE) + good
        val p = Wire.StreamParser()
        p.feed(stream, stream.size)
        val frames = p.drain()
        assertEquals(1, frames.size)
        assertEquals("नमस्ते दुनिया hello", frames[0].text)
    }

    @Test
    fun `stream parser handles two frames in one feed`() {
        val two = Wire.encode(textFrame("first")) + Wire.encode(textFrame("second"))
        val p = Wire.StreamParser()
        p.feed(two, two.size)
        val frames = p.drain()
        assertEquals(listOf("first", "second"), frames.map { it.text })
    }

    @Test
    fun `ble fragmentation reassembles`() {
        val frame = textFrame("A fairly longer sentence to fragment across several BLE chunks.")
        val frags = Wire.fragment(frame, 33) // tiny MTU to force many fragments
        assertTrue(frags.size > 2)
        val defrag = Wire.Defragmenter()
        var out: Wire.Frame? = null
        for (f in frags) out = defrag.accept(f)
        assertNotNull(out)
        assertEquals(frame.text, out!!.text)
        assertEquals(frame.tSendUs, out.tSendUs)
    }

    @Test
    fun `crc16 known vector`() {
        // CRC-16/CCITT-FALSE("123456789") = 0x29B1
        assertEquals(0x29B1, Wire.crc16("123456789".toByteArray(), 0, 9))
    }

    @Test
    fun `language table is stable`() {
        assertEquals(10, Wire.LANGUAGES.size)
        assertEquals(0, Wire.langId("hi"))
        assertEquals(9, Wire.langId("en"))
        assertEquals("or", Wire.langCode(7))
    }

    companion object {
        private const val MAGIC_GARBAGE: Byte = 0x7F
    }
}
