package isro.itantra

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import isro.itantra.audio.Wav
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Phase 0.5 — on-device verification of the Phase 0 model selection, run on the
 * Android emulator (or any device) via Android Studio's Gradle integration:
 *
 *   ./gradlew connectedDebugAndroidTest
 *
 * Models + test audio are pushed beforehand (see tools/android/run_verification.sh):
 *   adb push <pack contents> /storage/emulated/0/Android/data/in.isro.itantra/files/models/...
 *
 * Results are written to:
 *   /storage/emulated/0/Android/data/in.isro.itantra/files/results/device_verification.json
 * and logged with tag ITANTRA.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ModelVerificationTest {

    private val tag = "ITANTRA"
    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext
    // internal files dir: on Android 15 emulators the app uid cannot read
    // shell-pushed files under /sdcard/Android/data (FUSE owner filtering), so the
    // harness pushes models into /data/data/<pkg>/files via adb root instead.
    private val filesRoot: File = ctx.filesDir

    private val modelsRoot = File(filesRoot, "models")
    private val audioRoot = File(filesRoot, "audio")
    private val resultsFile = File(File(filesRoot, "results").apply { mkdirs() }, "device_verification.json")

    private val results = JSONObject()

    private fun requireModels(vararg paths: String) {
        for (p in paths) {
            val f = File(modelsRoot, p)
            assertTrue("missing model file (push it via adb, see tools/android/run_verification.sh): $f", f.exists())
        }
    }

    private fun memoryPssMb(): Int {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        return (mi.totalPss / 1024)
    }

    private fun record(key: String, value: Any) {
        // JUnit creates a new test-class instance per method; merge into the file so
        // results from earlier tests survive.
        val merged = if (resultsFile.exists()) {
            try { JSONObject(resultsFile.readText()) } catch (_: Exception) { JSONObject() }
        } else JSONObject()
        merged.put(key, value)
        merged.put("device", android.os.Build.FINGERPRINT)
        resultsFile.writeText(merged.toString(2))
    }

    private fun log(msg: String) = Log.i(tag, msg).also { println("$tag: $msg") }

    @Test
    fun test01_omnilingualSttEnglishAndHindi() {
        requireModels("omnilingual/model.int8.onnx", "omnilingual/tokens.txt")
        val enWav = Wav.read(File(audioRoot, "en.wav").absolutePath)
        val hiWav = Wav.read(File(audioRoot, "hi.wav").absolutePath)
        val t0 = SystemClock.elapsedRealtime()
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                omnilingual = OfflineOmnilingualAsrCtcModelConfig(
                    model = File(modelsRoot, "omnilingual/model.int8.onnx").absolutePath,
                ),
                tokens = File(modelsRoot, "omnilingual/tokens.txt").absolutePath,
                numThreads = 4,
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        )
        val rec = OfflineRecognizer(assetManager = null, config = config)
        val loadMs = SystemClock.elapsedRealtime() - t0
        log("omnilingual loaded in ${loadMs}ms")

        // warm-up (graph init)
        rec.createStream().use { s ->
            s.acceptWaveform(enWav.samples.copyOfRange(0, 16000), enWav.sampleRate)
            rec.decode(s); rec.getResult(s)
        }

        fun decode(wav: Wav, label: String): JSONObject {
            val s = rec.createStream()
            s.acceptWaveform(wav.samples, wav.sampleRate)
            val t1 = SystemClock.elapsedRealtime()
            rec.decode(s)
            val dt = SystemClock.elapsedRealtime() - t1
            val text = rec.getResult(s).text.trim()
            val audioMs = wav.samples.size * 1000L / wav.sampleRate
            val rtf = dt.toDouble() / audioMs
            val o = JSONObject()
                .put("text", text)
                .put("audioMs", audioMs)
                .put("decodeMs", dt)
                .put("rtf", rtf)
            log("$label: '$text' (${audioMs}ms audio, ${dt}ms decode, RTF $rtf)")
            s.release()
            return o
        }

        val en = decode(enWav, "STT en")
        val hi = decode(hiWav, "STT hi")
        record(
            "stt_omnilingual",
            JSONObject()
                .put("loadMs", loadMs)
                .put("pssAfterDecodeMb", memoryPssMb())
                .put("en", en)
                .put("hi", hi),
        )
        rec.release()

        assertTrue(
            "english transcript mismatch: ${en.getString("text")}",
            en.getString("text").lowercase().contains("country"),
        )
        assertTrue(
            "hindi transcript has no Devanagari: ${hi.getString("text")}",
            hi.getString("text").any { it in '\u0900'..'\u097F' },
        )
        assertTrue(
            "hindi transcript missing expected word संदेश: ${hi.getString("text")}",
            hi.getString("text").contains("संदेश") || hi.getString("text").contains("चेतावनी") || hi.getString("text").contains("आपातकालीन"),
        )
    }

    @Test
    fun test02_sileroVadSegmentsHindiSpeech() {
        val vadModel = File(modelsRoot, "vad/silero_vad.onnx")
        assertTrue("missing silero_vad.onnx — it normally ships inside the APK; push it for this test", vadModel.exists())
        val wav = Wav.read(File(audioRoot, "hi.wav").absolutePath)

        val config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = vadModel.absolutePath,
                threshold = 0.5f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 15.0f,
            ),
            sampleRate = 16000,
            numThreads = 1,
            provider = "cpu",
        )
        val vad = Vad(assetManager = null, config = config)

        val t0 = SystemClock.elapsedRealtime()
        vad.acceptWaveform(wav.samples)
        vad.flush()
        val segments = mutableListOf<Int>()
        while (!vad.empty()) {
            val seg = vad.front()
            segments.add(seg.samples.size)
            vad.pop()
        }
        val dt = SystemClock.elapsedRealtime() - t0
        log("VAD: ${segments.size} segment(s), sample counts $segments, ${dt}ms for ${wav.samples.size * 1000L / wav.sampleRate}ms audio")
        record(
            "vad_silero",
            JSONObject()
                .put("segments", segments.size)
                .put("segmentSampleCounts", segments)
                .put("audioMs", wav.samples.size * 1000L / wav.sampleRate)
                .put("processMs", dt),
        )
        vad.release()

        assertTrue("expected at least 1 speech segment, got ${segments.size}", segments.isNotEmpty())
        assertTrue("first segment shorter than minSpeechDuration: ${segments[0]}", segments[0] >= 4000)
    }

    private fun synthTest(
        key: String,
        modelDir: String,
        text: String,
    ) {
        val modelFile = File(File(modelsRoot, modelDir), "model.onnx")
        val tokensFile = File(File(modelsRoot, modelDir), "tokens.txt")
        assertTrue("missing ${modelFile.absolutePath}", modelFile.exists())
        assertTrue("missing ${tokensFile.absolutePath}", tokensFile.exists())

        val espeakDir = File(File(modelsRoot, modelDir), "espeak-ng-data")
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = modelFile.absolutePath,
                    tokens = tokensFile.absolutePath,
                    dataDir = if (espeakDir.exists()) espeakDir.absolutePath else "",
                    noiseScale = 0.667f,
                    noiseScaleW = 0.8f,
                    lengthScale = 1.0f,
                ),
                numThreads = 2,
                provider = "cpu",
            ),
            maxNumSentences = 1,
        )

        val t0 = SystemClock.elapsedRealtime()
        val tts = OfflineTts(assetManager = null, config = config)
        val loadMs = SystemClock.elapsedRealtime() - t0
        log("$key TTS loaded in ${loadMs}ms")

        // warm-up
        tts.generate("warm up", 0, 1.0f)

        // First-audio proxy: time synthesising just the first clause. This mirrors the
        // Phase 1 app design (clause-by-clause generate() -> streaming AudioTrack),
        // which also avoids OfflineTts.generateWithCallback(): its JNI expects a boxed
        // invoke([F)java/lang/Integer callback method that Kotlin 2.1 lambdas don't
        // emit -> hard SIGABRT (verified on emulator, Android 15, sherpa-onnx 1.13.8).
        val firstClause = text.split('.', '।', '॥').first { it.isNotBlank() }.trim()
        val t1 = SystemClock.elapsedRealtime()
        val firstAudio = tts.generate(firstClause, 0, 1.0f)
        val firstChunkMs = SystemClock.elapsedRealtime() - t1

        val t2 = SystemClock.elapsedRealtime()
        val audio = tts.generate(text, 0, 1.0f)
        val synthMs = SystemClock.elapsedRealtime() - t2
        val audioMs = audio.samples.size * 1000L / audio.sampleRate
        val rtf = synthMs.toDouble() / audioMs

        val outWav = File(File(filesRoot, "results").apply { mkdirs() }, "$key.wav")
        audio.save(outWav.absolutePath)
        log("$key TTS: ${audioMs}ms audio in ${synthMs}ms (RTF $rtf), first clause '${firstClause.take(20)}…' in ${firstChunkMs}ms -> ${outWav.absolutePath}")

        record(
            key,
            JSONObject()
                .put("loadMs", loadMs)
                .put("audioMs", audioMs)
                .put("synthMs", synthMs)
                .put("rtf", rtf)
                .put("firstClauseMs", firstChunkMs)
                .put("sampleRate", audio.sampleRate)
                .put("pssMb", memoryPssMb())
                .put("wav", outWav.name)
                .put("text", text),
        )
        tts.free()

        assertTrue("$key produced no audio", audio.samples.isNotEmpty())
        assertTrue("$key first clause produced no audio", firstAudio.samples.isNotEmpty())
        assertTrue("$key audio too short: ${audioMs}ms", audioMs > 1000)
        assertTrue("$key RTF pathological: $rtf", rtf < 5.0)
    }

    @Test
    fun test03_ttsPiperEnglish() {
        requireModels("tts-en/model.onnx", "tts-en/tokens.txt")
        synthTest(
            key = "tts_piper_en",
            modelDir = "tts-en",
            text = "Attention please. This is an emergency alert message. Move to the nearest exit immediately.",
        )
    }

    @Test
    fun test04_ttsMmsHindi() {
        requireModels("tts-hi/model.onnx", "tts-hi/tokens.txt")
        synthTest(
            key = "tts_mms_hi",
            modelDir = "tts-hi",
            text = "सुनो, यह एक आपातकालीन चेतावनी संदेश है। तुरंत निकटतम निकास की ओर जाएँ।",
        )
    }

    @Test
    fun test05_whisperSttEnglish() {
        requireModels("whisper-tiny/encoder.int8.onnx", "whisper-tiny/decoder.int8.onnx", "whisper-tiny/tokens.txt")
        val wav = Wav.read(File(audioRoot, "en.wav").absolutePath)

        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig(
                    encoder = File(modelsRoot, "whisper-tiny/encoder.int8.onnx").absolutePath,
                    decoder = File(modelsRoot, "whisper-tiny/decoder.int8.onnx").absolutePath,
                    language = "en",
                    task = "transcribe",
                    tailPaddings = 300,
                ),
                tokens = File(modelsRoot, "whisper-tiny/tokens.txt").absolutePath,
                numThreads = 4,
                provider = "cpu",
            ),
        )
        val rec = OfflineRecognizer(assetManager = null, config = config)
        val s = rec.createStream()
        s.acceptWaveform(wav.samples, wav.sampleRate)
        val t0 = SystemClock.elapsedRealtime()
        rec.decode(s)
        val dt = SystemClock.elapsedRealtime() - t0
        val text = rec.getResult(s).text.trim()
        log("whisper en: '$text' ($dt ms)")
        record("stt_whisper_en", JSONObject().put("text", text).put("decodeMs", dt))
        s.release(); rec.release()
        assertTrue("whisper transcript mismatch: $text", text.lowercase().contains("country"))
    }
}
