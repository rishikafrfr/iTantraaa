package isro.itantra.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * iTantra wire protocol (build-spec section 7.5). Compact binary framing, small
 * enough for an ESP32 to parse in ~100 lines of C. All integers big-endian.
 *
 * | off | size | field                                             |
 * |-----|------|---------------------------------------------------|
 * | 0   | 2    | magic 0x49 0x54 ("IT")                            |
 * | 2   | 1    | version = 1                                       |
 * | 3   | 1    | type (HELLO/TEXT/ACK/PING/PONG/PARTIAL/BYE)       |
 * | 4   | 1    | flags (bit0 ALERT, bit1 ACK_REQ, bit2 FINAL,      |
 * |     |      |         bit3 HAS_TIMING)                          |
 * | 5   | 1    | language id (index into LANGUAGES)               |
 * | 6   | 4    | message id (uint32, per-sender incrementing)      |
 * | 10  | 2    | payload length N (uint16)                         |
 * | 12  | N    | payload (UTF-8 / JSON / timestamps)               |
 * 12+N  | 16?  | tSpeechEnd, tSend (int64 µs, sender monotonic)    |
 * end  | 2    | CRC-16/CCITT-FALSE over everything before it     |
 */
object Wire {

    const val MAGIC0: Byte = 0x49
    const val MAGIC1: Byte = 0x54
    const val VERSION: Byte = 1

    const val TYPE_HELLO: Byte = 0x01
    const val TYPE_TEXT: Byte = 0x02
    const val TYPE_ACK: Byte = 0x03
    const val TYPE_PING: Byte = 0x04
    const val TYPE_PONG: Byte = 0x05
    const val TYPE_PARTIAL: Byte = 0x06
    const val TYPE_BYE: Byte = 0x07

    const val FLAG_ALERT: Byte = 0x01
    const val FLAG_ACK_REQ: Byte = 0x02
    const val FLAG_FINAL: Byte = 0x04
    const val FLAG_HAS_TIMING: Byte = 0x08

    const val HEADER_LEN = 12
    const val CRC_LEN = 2
    const val TIMING_LEN = 16

    /** Language id table — index IS the wire id. Order is fixed forever. */
    val LANGUAGES = listOf("hi", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn", "en")

    fun langId(code: String): Int = LANGUAGES.indexOf(code).also {
        require(it >= 0) { "unknown language code $code" }
    }

    fun langCode(id: Int): String = LANGUAGES.getOrElse(id) { "en" }

    data class Frame(
        val type: Byte,
        val flags: Byte = 0,
        val langId: Int = 0,
        val msgId: Long = 0,
        val payload: ByteArray = ByteArray(0),
        val tSpeechEndUs: Long? = null,
        val tSendUs: Long? = null,
    ) {
        val alert: Boolean get() = (flags.toInt() and Wire.FLAG_ALERT.toInt()) != 0
        val ackReq: Boolean get() = (flags.toInt() and Wire.FLAG_ACK_REQ.toInt()) != 0
        val hasTiming: Boolean get() = (flags.toInt() and Wire.FLAG_HAS_TIMING.toInt()) != 0
        val final: Boolean get() = (flags.toInt() and Wire.FLAG_FINAL.toInt()) != 0
        val text: String get() = String(payload, Charsets.UTF_8)
    }

    fun encode(f: Frame): ByteArray {
        val timing = if (f.hasTiming) TIMING_LEN else 0
        val out = ByteArray(HEADER_LEN + f.payload.size + timing + CRC_LEN)
        val b = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        b.put(MAGIC0); b.put(MAGIC1); b.put(VERSION); b.put(f.type); b.put(f.flags)
        b.put(f.langId.toByte())
        b.putInt(f.msgId.toInt())
        b.putShort(f.payload.size.toShort())
        b.put(f.payload)
        if (f.hasTiming) {
            b.putLong(f.tSpeechEndUs ?: 0L)
            b.putLong(f.tSendUs ?: 0L)
        }
        val crc = crc16(out, 0, out.size - CRC_LEN)
        b.putShort(crc.toShort())
        return out
    }

    sealed interface ParseResult {
        data class Ok(val frame: Frame, val consumed: Int) : ParseResult
        object NeedMore : ParseResult
        object Bad : ParseResult // resync: drop first byte and retry
    }

    fun parse(buf: ByteArray, len: Int): ParseResult {
        if (len < HEADER_LEN + CRC_LEN) return ParseResult.NeedMore
        if (buf[0] != MAGIC0 || buf[1] != MAGIC1) return ParseResult.Bad
        val version = buf[2]
        if (version != VERSION) return ParseResult.Bad
        val type = buf[3]
        val flags = buf[4]
        val langId = buf[5].toInt() and 0xFF
        val b = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.BIG_ENDIAN)
        val msgId = b.getInt(6).toLong() and 0xFFFFFFFFL
        val payloadLen = b.getShort(10).toInt() and 0xFFFF
        val timing = if ((flags.toInt() and FLAG_HAS_TIMING.toInt()) != 0) TIMING_LEN else 0
        val total = HEADER_LEN + payloadLen + timing + CRC_LEN
        if (payloadLen > 65_000) return ParseResult.Bad
        if (len < total) return ParseResult.NeedMore
        val crcCalc = crc16(buf, 0, total - CRC_LEN)
        val crcGot = b.getShort(total - CRC_LEN).toInt() and 0xFFFF
        if (crcCalc != crcGot) return ParseResult.Bad
        val payload = buf.copyOfRange(HEADER_LEN, HEADER_LEN + payloadLen)
        var tEnd: Long? = null
        var tSend: Long? = null
        if (timing > 0) {
            tEnd = b.getLong(HEADER_LEN + payloadLen)
            tSend = b.getLong(HEADER_LEN + payloadLen + 8)
        }
        return ParseResult.Ok(
            Frame(type, flags, langId, msgId, payload, tEnd, tSend),
            total,
        )
    }

    /** Streaming parser over a growable buffer; resyncs on magic after CRC failure. */
    class StreamParser {
        private var buf = ByteArray(4096)
        private var len = 0

        fun feed(bytes: ByteArray, n: Int) {
            ensure(n)
            System.arraycopy(bytes, 0, buf, len, n)
            len += n
        }

        /** Returns all complete frames currently in the buffer. */
        fun drain(): List<Frame> {
            val frames = mutableListOf<Frame>()
            while (true) {
                when (val r = parse(buf, len)) {
                    is ParseResult.Ok -> {
                        frames.add(r.frame)
                        System.arraycopy(buf, r.consumed, buf, 0, len - r.consumed)
                        len -= r.consumed
                    }
                    ParseResult.NeedMore -> return frames
                    ParseResult.Bad -> {
                        // drop one byte and hunt for the next magic
                        System.arraycopy(buf, 1, buf, 0, len - 1)
                        len -= 1
                        if (len == 0) return frames
                    }
                }
            }
        }

        private fun ensure(n: Int) {
            if (len + n > buf.size) {
                var cap = buf.size
                while (cap < len + n) cap *= 2
                buf = buf.copyOf(cap)
            }
        }
    }

    /** BLE/NUS fragmentation: MTU-3 payload chunks with a 1-byte header. */
    fun fragment(frame: Frame, mtu: Int): List<ByteArray> {
        val data = encode(frame)
        val chunkCap = (mtu - 3) - 1 // minus ATT header, minus our frag header
        require(chunkCap > 1) { "mtu too small" }
        val nChunks = (data.size + chunkCap - 1) / chunkCap
        require(nChunks <= 128) { "frame too large for fragmentation" }
        return data.toList().chunked(chunkCap).mapIndexed { i, chunk ->
            val header = (if (i == nChunks - 1) 0x80 else 0x00) or i
            byteArrayOf(header.toByte()) + chunk.toByteArray()
        }
    }

    class Defragmenter {
        private val acc = ByteArrayOutputStream()
        private var expected = -1

        /** Returns the completed frame, or null while fragments are missing. */
        fun accept(fragment: ByteArray): Frame? {
            if (fragment.isEmpty()) return null
            val header = fragment[0].toInt() and 0xFF
            val idx = header and 0x7F
            if (idx == 0) acc.reset()
            acc.write(fragment, 1, fragment.size - 1)
            expected = idx + 1
            return if ((header and 0x80) != 0) {
                val data = acc.toByteArray()
                acc.reset()
                when (val r = parse(data, data.size)) {
                    is ParseResult.Ok -> r.frame
                    else -> null
                }
            } else null
        }
    }

    /** CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF, no reflection, no xor-out. */
    fun crc16(data: ByteArray, offset: Int, length: Int): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }
}
