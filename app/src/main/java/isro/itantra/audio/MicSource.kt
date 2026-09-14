package isro.itantra.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import kotlin.concurrent.thread

/**
 * Continuous 16 kHz mono PCM16 microphone capture on a dedicated
 * URGENT_AUDIO thread. Windows of [windowSamples] are handed to [onWindow].
 */
class MicSource(
    private val sampleRate: Int = 16000,
    private val windowSamples: Int = 512, // Silero VAD window
    private val onWindow: (FloatArray) -> Unit,
) {
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    /** DUPLEX/phone mode: gate the mic while our TTS is playing (echo guard). */
    @Volatile
    var muted: Boolean = false

    @Volatile
    private var running = false

    val sampleRateOut: Int get() = sampleRate

    @SuppressLint("MissingPermission") // caller must hold RECORD_AUDIO
    fun start() {
        check(!running) { "already started" }
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, sampleRate * 2),
        )
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }
        record = rec
        running = true
        rec.startRecording()
        worker = thread(name = "itantra-mic", priority = Process.THREAD_PRIORITY_URGENT_AUDIO) {
            val pcm = ShortArray(windowSamples)
            val floats = FloatArray(windowSamples)
            while (running) {
                val n = rec.read(pcm, 0, windowSamples)
                if (n <= 0) continue
                if (muted) continue // gate: don't feed our own speaker output to STT
                for (i in 0 until n) floats[i] = pcm[i] / 32768.0f
                if (n == windowSamples) onWindow(floats.copyOf()) else {
                    onWindow(floats.copyOf(n))
                }
            }
        }
    }

    fun stop() {
        running = false
        worker?.join(1000)
        worker = null
        record?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        record = null
    }
}
