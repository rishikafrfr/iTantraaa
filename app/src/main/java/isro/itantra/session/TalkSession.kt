package isro.itantra.session

import android.content.Context
import isro.itantra.audio.MicSource
import isro.itantra.engine.SttEngine
import isro.itantra.engine.VadSegmenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SPEAKER-side pipeline for Phase 1: mic -> energy gate + Silero VAD ->
 * end-of-sentence segmentation -> offline STT -> callback with text + timings.
 *
 * VAD segmentation happens on the capture thread (cheap); STT decode runs on a
 * background dispatcher and never overlaps itself (guard via [decoding]).
 */
class TalkSession(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onTranscript: (SttEngine.Result) -> Unit,
) {
    private var mic: MicSource? = null

    /**
     * Segments wait here instead of being thrown away while a decode is in
     * flight. Bounded + DROP_OLDEST: if STT falls badly behind, lose the stalest
     * audio rather than grow without limit.
     */
    private var segments: Channel<FloatArray>? = null

    @Volatile
    private var stt: SttEngine? = null

    @Volatile
    private var vad: VadSegmenter? = null

    @Volatile
    private var loadedLang: String? = null

    /**
     * Debug builds only: write every captured segment to filesDir/audio as a WAV,
     * so an utterance can be pulled off the device and re-decoded by a different
     * engine. This is what separates a capture problem from a decode problem.
     */
    private val dumpSegments: Boolean =
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * [lang] directs the recogniser (see [SttEngine.load]); changing it rebuilds
     * the engine, because the language is baked into the Whisper config.
     */
    suspend fun load(lang: String? = null) = withContext(Dispatchers.IO) {
        if (stt == null || loadedLang != lang) {
            stt?.close()
            stt = SttEngine.load(context, lang)
            loadedLang = lang
        }
        if (vad == null) vad = VadSegmenter.load(context)
    }

    fun start() {
        val v = vad ?: error("call load() first")
        val ch = Channel<FloatArray>(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        segments = ch
        scope.launch(Dispatchers.Default) {
            for (segment in ch) {
                val engine = stt ?: continue
                // level check: a recogniser fed near-silence or a clipped signal
                // invents text, so log what we actually captured before blaming it
                var sum = 0.0
                var peak = 0f
                for (s in segment) { sum += (s * s).toDouble(); if (kotlin.math.abs(s) > peak) peak = kotlin.math.abs(s) }
                val rms = kotlin.math.sqrt(sum / segment.size).toFloat()
                android.util.Log.i(
                    "ITANTRA",
                    "segment ${segment.size / 16}ms rms=${"%.4f".format(rms)} " +
                        "peak=${"%.3f".format(peak)} dBFS=${"%.1f".format(20 * kotlin.math.log10(rms.coerceAtLeast(1e-6f)))}",
                )
                if (dumpSegments) {
                    runCatching {
                        val f = java.io.File(context.filesDir, "audio/seg-${System.currentTimeMillis()}.wav")
                        isro.itantra.audio.Wav.write(f, segment, 16000)
                        android.util.Log.i("ITANTRA", "segment dumped: ${f.absolutePath}")
                    }
                }
                onTranscript(engine.decode(segment))
            }
        }
        mic = MicSource { window ->
            for (segment in v.accept(window)) ch.trySend(segment)
        }.also { it.start() }
    }

    /** Gate/un-gate the mic (phone mode: muted while our TTS speaks). */
    fun setMuted(m: Boolean) {
        mic?.muted = m
    }

    fun stop() {
        mic?.stop()
        mic = null
        segments?.close()
        segments = null
    }

    fun release() {
        stop()
        stt?.close()
        stt = null
        vad?.close()
        vad = null
    }

    val isRunning: Boolean get() = mic != null
}
