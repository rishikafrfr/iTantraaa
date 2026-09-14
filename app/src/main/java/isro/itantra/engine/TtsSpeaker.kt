package isro.itantra.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import isro.itantra.nlp.TextNormaliser
import java.io.File

/**
 * Offline TTS over a per-language VITS pack (piper-en / mms-*), with
 * clause-streamed playback:
 *
 *  - text is split on sentence terminators (ASCII '.' / '!' / '?', Devanagari
 *    '।' / '॥' — the C++ splitter inside sherpa-onnx does NOT split on danda,
 *    verified in Phase 0) into clauses;
 *  - each clause is synthesised with plain generate() and enqueued to a
 *    streaming AudioTrack as soon as it is ready, so playback starts after the
 *    FIRST clause, not the whole utterance;
 *  - generateWithCallback() is deliberately NOT used: its JNI callback is
 *    incompatible with Kotlin 2.x lambdas (SIGABRT, see docs/BENCHMARKS.md).
 *
 * Pack layout (filesDir/models/tts-<lang>): model.onnx, tokens.txt[, espeak-ng-data/]
 */
class TtsSpeaker private constructor(
    private val context: Context,
    private val tts: OfflineTts,
    val loadMs: Long,
) : AutoCloseable {

    val sampleRate: Int get() = tts.sampleRate()

    data class Stats(
        val synthMs: Long,
        val audioMs: Long,
        val firstClauseMs: Long,
        val clauses: Int,
        val interrupted: Boolean = false,
    ) {
        val rtf: Double get() = if (audioMs > 0) synthMs.toDouble() / audioMs else 0.0
    }

    @Volatile private var cancelRequested = false

    /**
     * Ask an in-progress [speak] to stop at the next clause boundary and drop
     * whatever is still buffered. Used for ALERT preemption of NORMAL speech.
     */
    fun cancel() {
        cancelRequested = true
    }

    /** Split text into speakable clauses. Keeps terminal punctuation off the model input. */
    private fun clauses(text: String): List<String> =
        text.split('.', '!', '?', '।', '॥', '\n')
            .map { it.trim(' ', ',', ';', ':', '-') }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(text.trim()).filter { it.isNotBlank() } }

    /**
     * Synthesise and play [text]. Returns per-call stats. Runs on the caller's
     * thread (call from a worker, never the UI thread).
     *
     * ALERT playback: alarm usage, exclusive transient focus, max alarm volume
     * (restored after), attention tone prepended, vibration pattern.
     */
    fun speak(text: String, alert: Boolean = false, lang: String = "en"): Stats {
        cancelRequested = false
        val parts = clauses(text)
        if (parts.isEmpty()) return Stats(0, 0, 0, 0)

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val oldVol = if (alert) am.getStreamVolume(AudioManager.STREAM_ALARM) else -1
        val focus = alertFocus(am, alert)
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                if (alert) {
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                } else {
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                }
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuf, sampleRate * 4))
        val track = builder.build()
        if (alert) {
            am.setStreamVolume(
                AudioManager.STREAM_ALARM,
                am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0,
            )
            vibrateAlert()
        }
        track.play()

        var firstClauseMs = -1L
        val t0 = SystemClock.elapsedRealtime()
        var totalAudioMs = 0L
        var framesWritten = 0
        try {
            if (alert) {
                val tone = attentionTone(sampleRate)
                track.write(tone, 0, tone.size, AudioTrack.WRITE_BLOCKING)
                totalAudioMs += tone.size * 1000L / sampleRate
                framesWritten += tone.size
            }
            for ((i, rawClause) in parts.withIndex()) {
                if (cancelRequested) break // ALERT preemption, at a clause boundary
                val clause = TextNormaliser.normalise(rawClause, lang)
                if (clause.isBlank()) continue
                val audio = tts.generate(clause, 0, 1.0f)
                if (i == 0) firstClauseMs = SystemClock.elapsedRealtime() - t0
                totalAudioMs += audio.samples.size * 1000L / audio.sampleRate
                track.write(audio.samples, 0, audio.samples.size, AudioTrack.WRITE_BLOCKING)
                framesWritten += audio.samples.size
            }
            if (cancelRequested) {
                track.pause()
                track.flush() // drop buffered audio so the alert starts now, not in a second
            } else {
                track.stop()
                // MODE_STREAM: a blocking write returns once the data is *buffered*, and
                // stop() only starts the play-out. Releasing here cut the last ~1 s
                // (= buffer size) off every message. Wait for the head to catch up,
                // bounded by a stall timeout so a dead track can't hang the TTS queue.
                var lastHead = -1
                var stalls = 0
                while (track.playbackHeadPosition < framesWritten && stalls < 30) {
                    val head = track.playbackHeadPosition
                    if (head == lastHead) stalls++ else { lastHead = head; stalls = 0 }
                    Thread.sleep(20)
                }
            }
        } finally {
            if (alert && oldVol >= 0) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, oldVol, 0)
            }
            abandonFocus(am, focus)
            track.release()
        }
        val synthMs = SystemClock.elapsedRealtime() - t0
        return Stats(synthMs, totalAudioMs, firstClauseMs, parts.size, interrupted = cancelRequested)
    }

    private var focusRequest: AudioFocusRequest? = null

    private fun alertFocus(am: AudioManager, alert: Boolean): AudioFocusRequest? {
        val usage = if (alert) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA
        val attrs = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focusGain =
            if (alert) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        val req = AudioFocusRequest.Builder(focusGain)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .build()
        am.requestAudioFocus(req)
        focusRequest = req
        return req
    }

    private fun abandonFocus(am: AudioManager, req: AudioFocusRequest?) {
        req?.let { am.abandonAudioFocusRequest(it) }
        if (req === focusRequest) focusRequest = null
    }

    private fun vibrateAlert() {
        val v = if (android.os.Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        }
        v?.vibrate(
            android.os.VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250), -1)
        )
    }

    /** Generated in code (no copyrighted sounds): rising dual beep, 150 ms each. */
    private fun attentionTone(sampleRate: Int): FloatArray {
        val seg = (sampleRate * 0.15).toInt()
        val fade = (sampleRate * 0.01).toInt()
        val out = FloatArray(seg * 2 + sampleRate / 20) // tone, gap, tone
        for (half in 0 until 2) {
            val freq = if (half == 0) 880.0 else 1320.0
            for (i in 0 until seg) {
                val env = when {
                    i < fade -> i.toFloat() / fade
                    i > seg - fade -> (seg - i).toFloat() / fade
                    else -> 1f
                }
                out[half * (seg + sampleRate / 20) + i] =
                    (env * 0.85 * Math.sin(2 * Math.PI * freq * i / sampleRate)).toFloat()
            }
        }
        return out
    }

    override fun close() = tts.free()

    companion object {
        /** Pack dir name per language code; add more languages in Phase 4. */
        fun packDirFor(lang: String): String = when (lang) {
            "en" -> "tts-en"
            "hi" -> "tts-hi"
            else -> "tts-$lang"
        }

        fun load(context: Context, lang: String, threads: Int = 2): TtsSpeaker {
            // was a mutable static Context on the companion: whichever load() ran
            // last decided which context every instance used
            val app = context.applicationContext
            val dir = File(context.filesDir, "models/${packDirFor(lang)}")
            val model = File(dir, "model.onnx")
            val tokens = File(dir, "tokens.txt")
            require(model.exists() && tokens.exists()) {
                "TTS pack for '$lang' not installed at $dir — sideload it first"
            }
            val espeak = File(dir, "espeak-ng-data")
            val t0 = SystemClock.elapsedRealtime()
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = model.absolutePath,
                        tokens = tokens.absolutePath,
                        dataDir = if (espeak.exists()) espeak.absolutePath else "",
                        noiseScale = 0.667f,
                        noiseScaleW = 0.8f,
                        lengthScale = 1.0f,
                    ),
                    numThreads = threads,
                    provider = "cpu",
                ),
                maxNumSentences = 1,
            )
            val tts = OfflineTts(assetManager = null, config = config)
            return TtsSpeaker(app, tts, SystemClock.elapsedRealtime() - t0)
        }
    }
}
