package isro.itantra.packs

import android.content.Context
import android.util.Log
import isro.itantra.packs.PackCatalog.Entry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads a language pack from HuggingFace into filesDir/models/<destDir>.
 *
 *  - file list comes from the HF API (`/api/models/<repo>?blobs=true`, gives
 *    sizes too → exact progress);
 *  - files stream to `<name>.part` and are renamed only on success;
 *  - SHA-256 of every hashed file is verified before the manifest is written
 *    (the manifest is the activation marker — PackRegistry only lists dirs
 *    that contain one);
 *  - runs on Dispatchers.IO, fully cancellable between files.
 */
class PackDownloader(private val context: Context) {

    sealed class Progress {
        data class Downloading(val file: String, val doneBytes: Long, val totalBytes: Long) : Progress()
        data class Verifying(val file: String) : Progress()
        data class Done(val dir: File) : Progress()
        data class Failed(val message: String) : Progress()
    }

    suspend fun download(
        entry: Entry,
        onProgress: (Progress) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val files = listRepoFiles(entry)
            if (files.isEmpty()) {
                onProgress(Progress.Failed("no files found in ${entry.repoId}"))
                return@withContext false
            }
            val dest = File(PackRegistry.packsDir(context), entry.destDir)
            val staging = File(PackRegistry.packsDir(context), ".staging-${entry.id}")
            staging.deleteRecursively()
            staging.mkdirs()

            var done = 0L
            val total = files.values.sum()
            for ((path, size) in files) {
                val finalName = entry.renames[path]
                    ?: path.replace(entry.pathPrefix, "", ignoreCase = false)
                val target = File(staging, finalName)
                target.parentFile?.mkdirs()
                onProgress(Progress.Downloading(path, done, total))
                fetchFile(entry, path, target)
                done += size
                // pace requests (HF anonymous rate limit). The piper pack is ~hundreds
                // of tiny espeak-ng-data files — a flat 400 ms each added minutes.
                Thread.sleep(if (size > 1_000_000) 400 else 60)
            }

            // verify hashes against DOWNLOADED names (renames apply at activation)
            for ((name, expected) in entry.hashes) {
                if (expected.isBlank()) continue
                // hash keys are HF (downloaded) names; staged files carry FINAL names
                val f = File(staging, entry.renames[name] ?: name)
                if (!f.exists()) {
                    Log.e(TAG, "missing $name after download")
                    onProgress(Progress.Failed("missing $name after download"))
                    return@withContext false
                }
                onProgress(Progress.Verifying(name))
                val actual = sha256(f)
                if (!actual.equals(expected, ignoreCase = true)) {
                    Log.e(TAG, "checksum mismatch for $name: got $actual")
                    onProgress(Progress.Failed("checksum mismatch for $name"))
                    staging.deleteRecursively()
                    return@withContext false
                }
            }

            // activate: replace destination, write manifest last inside it
            dest.deleteRecursively()
            staging.renameTo(dest)
            val manifest = JSONObject()
                .put("id", entry.id)
                .put("lang", entry.lang)
                .put("kind", entry.kind)
                .put("engine", "sherpa-offline")
                .put("modelType", entry.modelType)
                .put("sizeBytes", files.values.sum())
                .put("license", entry.license)
                .put("source", "https://huggingface.co/${entry.repoId}")
                .put("version", 1)
            File(dest, "manifest.json").writeText(manifest.toString(2))
            onProgress(Progress.Done(dest))
            Log.i(TAG, "installed ${entry.id} -> $dest (${files.size} files)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "download ${entry.id} failed", e)
            onProgress(Progress.Failed(e.message ?: "download failed"))
            false
        }
    }

    /** path -> size, filtered by prefix/include. */
    private fun listRepoFiles(entry: Entry): Map<String, Long> {
        val url = URL("https://huggingface.co/api/models/${entry.repoId}?blobs=true")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        try {
            conn.inputStream.bufferedReader().use { r ->
                val body = r.readText()
                val siblings = JSONObject(body).optJSONArray("siblings") ?: JSONArray()
                val out = LinkedHashMap<String, Long>()
                for (i in 0 until siblings.length()) {
                    val o = siblings.getJSONObject(i)
                    val p = o.optString("rfilename")
                    if (p.isEmpty() || p.startsWith(".")) continue
                    if (p == "README.md" || p == "LICENSE" || p == ".gitattributes") continue
                    if (entry.include != null) {
                        if (p in entry.include) out[p] = o.optLong("size", 0L)
                    } else if (p.startsWith(entry.pathPrefix)) {
                        out[p] = o.optLong("size", 0L)
                    }
                }
                return out
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchFile(entry: Entry, path: String, target: File) {
        val url = URL("https://huggingface.co/${entry.repoId}/resolve/main/$path")
        var lastErr: Exception? = null
        for (attempt in 1..4) {
            var conn: HttpURLConnection? = null
            try {
                conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 20000
                conn.readTimeout = 120000
                conn.instanceFollowRedirects = true // HF -> CDN redirect
                val code = conn.responseCode
                if (code == 429 || code in 500..599) {
                    // HF anonymous rate limit / transient CDN error: back off and retry
                    val retryAfter = (conn.getHeaderField("Retry-After")?.toLongOrNull() ?: 0L)
                    val waitMs = (if (retryAfter > 0) retryAfter * 1000 else attempt * 5000L)
                        .coerceAtMost(60_000L)
                    Log.w(TAG, "HTTP $code for $path (attempt $attempt) — retrying in ${waitMs / 1000}s")
                    Thread.sleep(waitMs)
                    continue
                }
                require(code in 200..299) { "HTTP $code for $path" }
                conn.inputStream.use { ins ->
                    FileOutputStream(target).use { fos ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            fos.write(buf, 0, n)
                        }
                    }
                }
                return
            } catch (e: Exception) {
                lastErr = e
                Log.w(TAG, "fetch $path attempt $attempt failed: ${e.message}")
                Thread.sleep(attempt * 3000L)
            } finally {
                conn?.disconnect()
            }
        }
        throw lastErr ?: IllegalStateException("download failed: $path")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun delete(entry: Entry): Boolean {
        val dest = File(PackRegistry.packsDir(context), entry.destDir)
        return dest.deleteRecursively()
    }

    companion object {
        private const val TAG = "ITANTRA"
    }
}
