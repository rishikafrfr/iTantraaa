package isro.itantra.packs

import android.content.Context
import org.json.JSONObject
import java.io.File

/** One installed pack under filesDir/models (manifest.json + model files). */
data class Pack(
    val dir: File,
    val manifest: JSONObject,
) {
    val id: String get() = manifest.optString("id", dir.name)
    val lang: String get() = manifest.optString("lang", "")
    val kind: String get() = manifest.optString("kind", "")
    val engine: String get() = manifest.optString("engine", "sherpa-offline")
    val modelType: String get() = manifest.optString("modelType", "")
    val license: String get() = manifest.optString("license", "")
    val sizeMb: Long get() = manifest.optLong("sizeBytes", 0) / 1_000_000
}

/**
 * Phase 1 pack registry: discovers installed packs by scanning for
 * manifest.json under filesDir/models/&lt;pack&gt;/. No model path is hard-coded
 * anywhere else.
 */
object PackRegistry {

    fun packsDir(context: Context): File = File(context.filesDir, "models")

    fun installed(context: Context): List<Pack> =
        packsDir(context).listFiles { f -> f.isDirectory }
            ?.mapNotNull { dir ->
                val m = File(dir, "manifest.json")
                if (!m.exists()) return@mapNotNull null
                runCatching { Pack(dir, JSONObject(m.readText())) }.getOrNull()
            }
            .orEmpty()
            .sortedBy { it.id }

    /** True if any recogniser is available — omnilingual or the whisper fallback. */
    fun isSttInstalled(context: Context): Boolean =
        File(packsDir(context), "omnilingual/model.int8.onnx").exists() ||
            File(packsDir(context), "whisper-tiny/tiny-encoder.int8.onnx").exists()

    fun isTtsInstalled(context: Context, lang: String): Boolean {
        val d = File(packsDir(context), "tts-$lang")
        return File(d, "model.onnx").exists() && File(d, "tokens.txt").exists()
    }

    /** Installed TTS languages in canonical Wire.LANGUAGES order (for the UI). */
    fun ttsLanguages(context: Context): List<String> =
        isro.itantra.protocol.Wire.LANGUAGES.filter { isTtsInstalled(context, it) }
}
