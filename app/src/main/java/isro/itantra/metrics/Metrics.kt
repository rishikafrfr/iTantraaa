package isro.itantra.metrics

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import isro.itantra.protocol.Wire
import java.io.File
import java.io.PrintWriter
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * NTP-style clock sync over the active link (build-spec 7.11):
 * PING carries t0 (sender local µs); PONG echoes t0 and carries t1 (receiver
 * local µs at receive). On PONG, the original sender computes t3.
 *
 *   offset = ((t1 - t0) + (t2 - t3)) / 2   (t2 == t1 here; receiver replies instantly)
 *   rtt    = t3 - t0
 *
 * Offset is taken from the lowest-RTT sample over a sliding window of 20;
 * uncertainty reported as ±minRtt/2.
 */
class ClockSync {
    data class Sample(val offsetUs: Long, val rttUs: Long)

    private val window = ArrayDeque<Sample>()

    @Synchronized
    fun add(t0: Long, t1: Long, t3: Long): Sample {
        val rtt = t3 - t0
        val offset = ((t1 - t0) + (t1 - t3)) / 2
        window.addLast(Sample(offset, rtt))
        while (window.size > 20) window.removeFirst()
        return Sample(offset, rtt)
    }

    @Synchronized
    fun best(): Sample? = window.minByOrNull { s: Sample -> s.rttUs }

    /** Receiver-side helper: convert a remote (sender-clock) µs timestamp to local. */
    @Synchronized
    fun toLocal(remoteUs: Long): Long? = best()?.let { remoteUs - it.offsetUs }

    @Synchronized
    fun uncertaintyUs(): Long = best()?.let { it.rttUs / 2 } ?: -1

    /** Build the PING frame payload (t0). */
    fun pingPayload(t0: Long): ByteArray =
        ByteBuffer.allocate(8).putLong(t0).array()

    /** Parse PING payload → t0; build PONG payload (t0, t1). */
    fun pongPayloadFromPing(payload: ByteArray, t1: Long): ByteArray? {
        if (payload.size < 8) return null
        val t0 = ByteBuffer.wrap(payload).long
        return ByteBuffer.allocate(16).putLong(t0).putLong(t1).array()
    }

    /** Parse PONG payload → (t0, t1). */
    fun parsePong(payload: ByteArray): Pair<Long, Long>? {
        if (payload.size < 16) return null
        val b = ByteBuffer.wrap(payload)
        return b.long to b.long
    }
}

/** Per-message latency record (receiver side). */
data class MsgTiming(
    val msgId: Long,
    val text: String,
    val lang: String,
    val alert: Boolean,
    val tSpeechEndRemoteUs: Long,
    val tRecvUs: Long,
    val tTtsFirstClauseMs: Long,   // local, relative
    val tAudioStartUs: Long,       // local
    var e2eMs: Long? = null,       // offset-corrected
    var uncertaintyMs: Long? = null,
)

object MetricsExporter {

    /** Export message history + timings as CSV to Downloads (API 29+) or filesDir. */
    fun exportCsv(context: Context, rows: List<List<String>>): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "itantra-benchmarks-$ts.csv"
        val body = rows.joinToString("\n") { r ->
            r.joinToString(",") { c -> "\"" + c.replace("\"", "\"\"") + "\"" }
        }
        // MediaStore.Downloads is API 29+. On 26–28 touching it throws NoSuchFieldError,
        // which is an Error, not an Exception — the old catch never saw it and the
        // export crashed the app on Android 8/9.
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: error("insert failed")
                context.contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
                return "Downloads/$name"
            }
        }
        val f = File(File(context.filesDir, "exports").apply { mkdirs() }, name)
        PrintWriter(f).use { it.write(body) }
        return f.absolutePath
    }

    /** Default benchmark sheet header. */
    val HEADER = listOf(
        "msgId", "lang", "alert", "text",
        "e2eMs", "uncertaintyMs", "rttMs", "firstClauseMs",
    )

    fun toRow(m: MsgTiming, rttMs: Long?): List<String> = listOf(
        m.msgId.toString(), m.lang, m.alert.toString(), m.text.take(80),
        m.e2eMs?.toString() ?: "", m.uncertaintyMs?.toString() ?: "",
        rttMs?.toString() ?: "", m.tTtsFirstClauseMs.toString(),
    )

    /** Convenience: decode timing from an incoming TEXT frame (receiver side). */
    fun fromFrame(f: Wire.Frame, recvUs: Long, firstClauseMs: Long): MsgTiming? =
        if (f.hasTiming && f.tSpeechEndUs != null) {
            MsgTiming(
                msgId = f.msgId, text = f.text, lang = Wire.langCode(f.langId), alert = f.alert,
                tSpeechEndRemoteUs = f.tSpeechEndUs, tRecvUs = recvUs,
                tTtsFirstClauseMs = firstClauseMs, tAudioStartUs = recvUs + firstClauseMs * 1000,
            )
        } else null
}
