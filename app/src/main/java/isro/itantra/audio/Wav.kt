package isro.itantra.audio

import java.io.DataInputStream
import java.io.EOFException
import java.io.File

/** Minimal RIFF/WAVE reader for PCM-16 mono/stereo files at any sample rate. */
class Wav private constructor(val samples: FloatArray, val sampleRate: Int) {

    companion object {

        /**
         * Write mono PCM-16. Used by the debug segment dump so a captured
         * utterance can be replayed through a different recogniser offline —
         * without it, diagnosing "is it capture or decode?" is guesswork.
         */
        fun write(file: File, samples: FloatArray, sampleRate: Int) {
            val dataBytes = samples.size * 2
            val b = java.nio.ByteBuffer.allocate(44 + dataBytes)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray(Charsets.US_ASCII)); b.putInt(36 + dataBytes)
            b.put("WAVE".toByteArray(Charsets.US_ASCII))
            b.put("fmt ".toByteArray(Charsets.US_ASCII)); b.putInt(16)
            b.putShort(1); b.putShort(1) // PCM, mono
            b.putInt(sampleRate); b.putInt(sampleRate * 2)
            b.putShort(2); b.putShort(16)
            b.put("data".toByteArray(Charsets.US_ASCII)); b.putInt(dataBytes)
            for (s in samples) {
                b.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
            file.parentFile?.mkdirs()
            file.writeBytes(b.array())
        }
        fun read(path: String): Wav = File(path).inputStream().use { ins ->
            val din = DataInputStream(ins)

            fun readStr(n: Int): String {
                val b = ByteArray(n)
                din.readFully(b)
                return String(b, Charsets.US_ASCII)
            }

            fun leInt(): Int {
                val b = IntArray(4) { din.read() }
                if (b[3] < 0) throw EOFException()
                return b[0] or (b[1] shl 8) or (b[2] shl 16) or (b[3] shl 24)
            }

            fun leShort(): Int {
                val b1 = din.read(); val b2 = din.read()
                if (b2 < 0) throw EOFException()
                return b1 or (b2 shl 8)
            }

            require(readStr(4) == "RIFF") { "not RIFF: $path" }
            din.readInt()
            require(readStr(4) == "WAVE") { "not WAVE: $path" }

            var format = -1; var channels = -1; var sampleRate = -1; var bits = -1
            var data: ByteArray? = null
            while (true) {
                val id = try { readStr(4) } catch (_: EOFException) { break }
                val size = leInt()
                when (id) {
                    "fmt " -> {
                        format = leShort(); channels = leShort(); sampleRate = leInt()
                        din.readInt(); din.readShort(); bits = leShort()
                        if (size > 16) din.skipBytes(size - 16)
                    }
                    "data" -> { val b = ByteArray(size); din.readFully(b); data = b }
                    else -> din.skipBytes(size)
                }
                if (size % 2 == 1) din.skipBytes(1)
            }

            val d = data ?: throw IllegalArgumentException("no data chunk: $path")
            require(format == 1) { "unsupported format $format: $path" }
            require(bits == 16) { "unsupported bits $bits: $path" }
            val frames = d.size / (2 * channels)
            val out = FloatArray(frames)
            for (i in 0 until frames) {
                var acc = 0f
                for (c in 0 until channels) {
                    val idx = (i * channels + c) * 2
                    val s = ((d[idx + 1].toInt() shl 8) or (d[idx].toInt() and 0xFF))
                    acc += s / 32768.0f
                }
                out[i] = acc / channels
            }
            Wav(out, sampleRate)
        }
    }
}
