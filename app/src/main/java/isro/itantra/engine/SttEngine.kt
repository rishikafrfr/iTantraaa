package isro.itantra.engine

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File

/**
 * Offline STT over the Omnilingual 300M CTC INT8 pack. One model serves all
 * supported languages; the output script follows the audio's language, so no
 * language parameter exists (verified in Phase 0/0.5).
 *
 * Pack layout (filesDir/models/omnilingual):
 *   model.int8.onnx, tokens.txt
 */
class SttEngine private constructor(
    private val recognizer: OfflineRecognizer,
    val loadMs: Long,
) : AutoCloseable {

    data class Result(val text: String, val decodeMs: Long, val audioMs: Long) {
        val rtf: Double get() = if (audioMs > 0) decodeMs.toDouble() / audioMs else 0.0
    }

    fun decode(samples: FloatArray, sampleRate: Int = 16000): Result {
        val s = recognizer.createStream()
        s.acceptWaveform(samples, sampleRate)
        val t0 = android.os.SystemClock.elapsedRealtime()
        recognizer.decode(s)
        val dt = android.os.SystemClock.elapsedRealtime() - t0
        val text = recognizer.getResult(s).text.trim()
        s.release()
        return Result(text, dt, samples.size * 1000L / sampleRate)
    }

    override fun close() = recognizer.release()

    companion object {
        private const val TAG = "ITANTRA"

        /** A whisper pack on disk. [rank] orders them by capability, tiny lowest. */
        private data class Whisper(val dirName: String, val prefix: String, val rank: Int)

        /** Smallest first: we want the cheapest pack that clears the bar, not the biggest. */
        private val WHISPER = listOf(
            Whisper("whisper-tiny", "tiny", 1),
            Whisper("whisper-base", "base", 2),
            Whisper("whisper-small", "small", 3),
        )

        /**
         * Smallest whisper that is usable per language; absent = use omnilingual.
         *
         * English is fine on tiny (correct script, punctuation, RTF 0.05 vs
         * omnilingual's 0.21).
         *
         * Hindi and Marathi are NOT here, despite whisper being the only engine
         * that takes a language. Measured, lang=hi:
         *   tiny   -> "Namasar 2-1 parikshana vakya hai."   (romanised)
         *   base   -> "نمسار دوستون یہ ایک پریکشان وقت ہے"    (Perso-Arabic)
         *   small  -> "ह क तकालीन िता"  for 3.9 s of clean speech
         * Small gets the script right but drops the leading characters of words:
         * sherpa-onnx's whisper decoding mangles multi-byte UTF-8. (Control: the
         * same log path prints omnilingual's Kannada perfectly, so the loss is in
         * whisper, not in capture or logging.) Complete text in the wrong script
         * beats shredded text in the right one, so Indic stays on omnilingual.
         *
         * Revisit if sherpa-onnx fixes byte-level BPE output; the rank machinery
         * below is all that is needed to switch back.
         */
        private val MIN_WHISPER_RANK = mapOf("en" to 1)

        private fun dirOf(context: Context, w: Whisper) =
            File(context.filesDir, "models/${w.dirName}")

        private fun installed(context: Context, w: Whisper): Boolean {
            val d = dirOf(context, w)
            return File(d, "${w.prefix}-encoder.int8.onnx").exists() &&
                File(d, "${w.prefix}-decoder.int8.onnx").exists()
        }

        /**
         * Cheapest installed whisper pack that clears [lang]'s bar, or null.
         * Picking the *largest* installed pack made English decode on small at
         * RTF 0.30 when tiny does the same job at 0.05.
         */
        private fun bestWhisper(context: Context, lang: String): Whisper? {
            val min = MIN_WHISPER_RANK[lang] ?: return null
            return WHISPER.firstOrNull { it.rank >= min && installed(context, it) }
        }

        fun isWhisperInstalled(context: Context): Boolean =
            WHISPER.any { installed(context, it) }

        /**
         * Pick the recogniser for [lang].
         *
         * The omnilingual CTC model takes no language argument — its config
         * exposes only a model path — and in practice it transcribes Indian
         * languages AND English into Perso-Arabic script, which no voice pack can
         * speak. Whisper accepts an explicit `language`, so when its pack is
         * present and the language is one it knows, we use it and get a
         * deterministic script. Otherwise we fall back to omnilingual and let
         * script detection sort out the tag.
         */
        fun load(context: Context, lang: String? = null, threads: Int = 4): SttEngine {
            val w = lang?.let { bestWhisper(context, it) }
            return if (w != null) loadWhisper(context, w, lang, threads)
            else loadOmnilingual(context, threads)
        }

        private fun loadWhisper(context: Context, w: Whisper, lang: String, threads: Int): SttEngine {
            val dir = dirOf(context, w)
            val t0 = android.os.SystemClock.elapsedRealtime()
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    whisper = com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig(
                        encoder = File(dir, "${w.prefix}-encoder.int8.onnx").absolutePath,
                        decoder = File(dir, "${w.prefix}-decoder.int8.onnx").absolutePath,
                        language = lang,
                        task = "transcribe", // never "translate": we must keep the native script
                    ),
                    tokens = File(dir, "${w.prefix}-tokens.txt").absolutePath,
                    numThreads = threads,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
            )
            val rec = OfflineRecognizer(assetManager = null, config = config)
            android.util.Log.i(TAG, "STT engine: ${w.dirName} language=$lang")
            return SttEngine(rec, android.os.SystemClock.elapsedRealtime() - t0)
        }

        private fun loadOmnilingual(context: Context, threads: Int): SttEngine {
            val dir = File(context.filesDir, "models/omnilingual")
            require(File(dir, "model.int8.onnx").exists() && File(dir, "tokens.txt").exists()) {
                "omnilingual STT pack not installed at $dir — sideload it first"
            }
            android.util.Log.i(TAG, "STT engine: omnilingual (script not controllable)")
            val t0 = android.os.SystemClock.elapsedRealtime()
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    omnilingual = OfflineOmnilingualAsrCtcModelConfig(
                        model = File(dir, "model.int8.onnx").absolutePath,
                    ),
                    tokens = File(dir, "tokens.txt").absolutePath,
                    numThreads = threads,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
            )
            val rec = OfflineRecognizer(assetManager = null, config = config)
            return SttEngine(rec, android.os.SystemClock.elapsedRealtime() - t0)
        }
    }
}
