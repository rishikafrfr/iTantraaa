package isro.itantra.engine

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import kotlin.math.sqrt

/**
 * Two-stage VAD gating (idle-CPU requirement, 20% of the SIH score):
 *  1. a cheap RMS energy gate runs on every input window;
 *  2. only when energy exceeds an adaptive noise floor do we feed the window to
 *     Silero VAD and let it segment speech / end-of-sentence.
 *
 * Silero VAD model ships inside the APK (assets/vad/silero_vad.onnx) and is
 * copied to filesDir on first use — models must load from real file paths, not
 * compressed assets (pack-manager rule from the build spec).
 */
class VadSegmenter private constructor(
    private val vad: Vad,
    val threshold: Float,
    val minSilenceDuration: Float,
) : AutoCloseable {

    private var noiseFloor = 1e-4f

    /** RMS gate: if true, the window MAY contain speech and goes to Silero. */
    private fun energyGated(window: FloatArray): Boolean {
        var sum = 0.0
        for (s in window) sum += (s * s).toDouble()
        val rms = sqrt(sum / window.size).toFloat()
        // slow-adaptive noise floor, 0.98 old + 0.02 current while silent
        return if (rms > noiseFloor * 3f + 1e-4f) {
            true
        } else {
            noiseFloor = noiseFloor * 0.98f + rms * 0.02f
            false
        }
    }

    /**
     * Feed one capture window (multiple of 512 samples recommended for Silero).
     * Returns completed speech segments (may be empty).
     */
    fun accept(window: FloatArray): List<FloatArray> {
        // Once Silero is inside a speech segment it MUST keep receiving audio, or
        // the trailing silence never reaches it and minSilenceDuration never fires
        // — segments then only closed at maxSpeechDuration (15 s) or flush().
        // The energy gate is a pure idle-CPU saver; it must not gate mid-utterance.
        if (!energyGated(window) && !vad.isSpeechDetected()) return emptyList()
        vad.acceptWaveform(window)
        val out = mutableListOf<FloatArray>()
        while (!vad.empty()) {
            out.add(vad.front().samples)
            vad.pop()
        }
        return out
    }

    /** Flush trailing speech (e.g. on push-to-talk release). */
    fun flush(): List<FloatArray> {
        vad.flush()
        val out = mutableListOf<FloatArray>()
        while (!vad.empty()) {
            out.add(vad.front().samples)
            vad.pop()
        }
        return out
    }

    fun reset() = vad.reset()

    override fun close() = vad.release()

    companion object {
        fun load(context: Context, threshold: Float = 0.5f, minSilence: Float = 0.5f): VadSegmenter {
            val dir = File(context.filesDir, "models/vad")
            val target = File(dir, "silero_vad.onnx")
            if (!target.exists()) {
                dir.mkdirs()
                context.assets.open("vad/silero_vad.onnx").use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }
            val config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = target.absolutePath,
                    threshold = threshold,
                    minSilenceDuration = minSilence,
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                    maxSpeechDuration = 15.0f,
                ),
                sampleRate = 16000,
                numThreads = 1,
                provider = "cpu",
            )
            return VadSegmenter(Vad(assetManager = null, config = config), threshold, minSilence)
        }
    }
}
