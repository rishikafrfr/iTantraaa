package isro.itantra

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import isro.itantra.audio.Wav
import isro.itantra.comm.CommApp
import isro.itantra.engine.SttEngine
import isro.itantra.engine.TtsSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Debug-only headless hook (debug builds) so the Phase 1 single-phone loop can
 * be driven end-to-end from adb without touching the UI:
 *
 *   adb shell am broadcast -a isro.itantra.DEBUG_STT_FILE --es path /data/data/isro.itantra/files/audio/hi.wav
 *   adb shell am broadcast -a isro.itantra.DEBUG_TTS --es text "..." --es lang hi
 *
 * Results append to files/results/phase1_e2e.json and log under tag ITANTRA.
 */
class DebugHookReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        // keep the process foregrounded so Android doesn't freeze it mid-relay;
        // background FGS starts throw on Android 12+ — production starts the
        // service from the foreground UI, here we merely try
        runCatching { isro.itantra.service.CommService.start(app) }
        when (intent.action) {
            "isro.itantra.DEBUG_STT_FILE" -> {
                val path = intent.getStringExtra("path") ?: return
                val sttLang = intent.getStringExtra("lang") // null = omnilingual
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        val wav = Wav.read(path)
                        val stt = SttEngine.load(app, sttLang)
                        val r = stt.decode(wav.samples, wav.sampleRate)
                        stt.close()
                        record(app, "stt_file", JSONObject()
                            .put("path", path)
                            .put("text", r.text)
                            .put("decodeMs", r.decodeMs)
                            .put("audioMs", r.audioMs)
                            .put("rtf", r.rtf))
                        Log.i(TAG, "DEBUG_STT_FILE ok: '${r.text}' rtf=${r.rtf}")
                    }.onFailure { Log.e(TAG, "DEBUG_STT_FILE failed", it) }
                }
            }
            "isro.itantra.DEBUG_TTS" -> {
                val text = intent.getStringExtra("text") ?: return
                val lang = intent.getStringExtra("lang") ?: "en"
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        val tts = TtsSpeaker.load(app, lang)
                        val stats = tts.speak(text)
                        tts.close()
                        record(app, "tts_${lang}", JSONObject()
                            .put("text", text)
                            .put("synthMs", stats.synthMs)
                            .put("audioMs", stats.audioMs)
                            .put("firstClauseMs", stats.firstClauseMs)
                            .put("clauses", stats.clauses))
                        Log.i(TAG, "DEBUG_TTS ok: $stats")
                    }.onFailure { Log.e(TAG, "DEBUG_TTS failed", it) }
                }
            }
            "isro.itantra.DEBUG_HOST" -> {
                CommApp.get(app).host()
                Log.i(TAG, "DEBUG_HOST issued")
            }
            "isro.itantra.DEBUG_JOIN" -> {
                val host = intent.getStringExtra("host") ?: return
                val port = intent.getIntExtra("port", isro.itantra.transport.TcpLink.PORT)
                CommApp.get(app).join2(host, port)
                Log.i(TAG, "DEBUG_JOIN issued: $host:$port")
            }
            "isro.itantra.DEBUG_SEND" -> {
                val text = intent.getStringExtra("text") ?: return
                val lang = intent.getStringExtra("lang") ?: "en"
                val alert = intent.getBooleanExtra("alert", false)
                val comm = CommApp.get(app)
                comm.sendText(
                    text, lang, alert,
                    tSpeechEndUs = android.os.SystemClock.elapsedRealtime() * 1000,
                )
                Log.i(TAG, "DEBUG_SEND queued: '$text' alert=$alert")
            }
            "isro.itantra.DEBUG_STATE" -> {
                val comm = CommApp.get(app)
                val (s, detail) = comm.state.value
                record(app, "comm_state", JSONObject()
                    .put("state", s.name)
                    .put("detail", detail ?: "")
                    .put("history", comm.history.value.size)
                    .put("rttMs", comm.lastRttMs.value ?: -1)
                    .put("clockUncertaintyMs", comm.clock.uncertaintyUs() / 1000)
                    .put("timings", comm.timings.value.size))
                Log.i(TAG, "DEBUG_STATE: $s $detail history=${comm.history.value.size} rtt=${comm.lastRttMs.value}")
            }
            "isro.itantra.DEBUG_DOWNLOAD" -> {
                val id = intent.getStringExtra("id") ?: return
                val entry = isro.itantra.packs.PackCatalog.entries.firstOrNull { it.id == id }
                if (entry == null) {
                    Log.e(TAG, "DEBUG_DOWNLOAD: no catalog entry $id")
                    return
                }
                CoroutineScope(Dispatchers.IO).launch {
                    Log.i(TAG, "DEBUG_DOWNLOAD start: $id")
                    val ok = isro.itantra.packs.PackDownloader(app).download(entry) { p ->
                        when (p) {
                            is isro.itantra.packs.PackDownloader.Progress.Done -> Log.i(TAG, "dl done: ${p.dir}")
                            is isro.itantra.packs.PackDownloader.Progress.Failed -> Log.e(TAG, "dl failed: ${p.message}")
                            else -> {}
                        }
                    }
                    Log.i(TAG, "DEBUG_DOWNLOAD ${if (ok) "OK" else "FAILED"}: $id")
                }
            }
            "isro.itantra.DEBUG_EXPORT" -> {
                val path = CommApp.get(app).exportBenchmarksCsv()
                Log.i(TAG, "DEBUG_EXPORT: $path")
            }
        }
    }

    companion object {
        private const val TAG = "ITANTRA"

        fun record(app: Context, key: String, value: JSONObject) {
            val dir = File(app.filesDir, "results").apply { mkdirs() }
            val f = File(dir, "phase1_e2e.json")
            val root = if (f.exists()) {
                runCatching { JSONObject(f.readText()) }.getOrNull() ?: JSONObject()
            } else JSONObject()
            root.put(key, value)
            f.writeText(root.toString(2))
        }
    }
}
