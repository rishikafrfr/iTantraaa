package isro.itantra.comm

import android.content.Context
import android.util.Log
import isro.itantra.engine.SttEngine
import isro.itantra.metrics.ClockSync
import isro.itantra.metrics.MsgTiming
import isro.itantra.metrics.MetricsExporter
import isro.itantra.engine.TtsSpeaker
import isro.itantra.protocol.Wire
import isro.itantra.transport.BtLink
import isro.itantra.transport.LinkState
import isro.itantra.transport.TcpLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Application-level comm layer for Phase 2: owns the transport, the protocol
 * state machine (HELLO handshake, ACK/retransmit, dedupe), the receive→TTS
 * queue (ALERT preempts NORMAL), the message history, and per-message latency
 * timestamps (Phase 5 builds on these).
 */
class CommApp private constructor(private val context: Context) {

    data class Message(
        val id: Long,
        val text: String,
        val lang: String,
        val alert: Boolean,
        val fromMe: Boolean,
        val t: Long = System.currentTimeMillis(),
        var tAudioStartMs: Long = 0, // receiver: playback start
        var e2eDeltaMs: Long? = null, // receiver: tAudioStart - tSpeechEnd (offset-free same-device clock)
        var acked: Boolean = false, // sender: peer confirmed receipt
        var failed: Boolean = false, // sender: never left the device / no ACK after 3 tries
    )

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val link = TcpLink(onFrame = ::onFrame, onState = ::onLinkState)
    private val bt: BtLink = BtLink(onFrame = ::onFrame, onState = ::onLinkState)

    val state = MutableStateFlow(LinkState.DISCONNECTED to (null as String?))
    val peerLangs = MutableStateFlow<List<String>>(emptyList())
    val history = MutableStateFlow<List<Message>>(emptyList())
    val speaking = MutableStateFlow(false)
    val alertPlaying = MutableStateFlow(false)
    val clock = ClockSync()
    val timings = MutableStateFlow<List<MsgTiming>>(emptyList())
    val lastRttMs = MutableStateFlow<Long?>(null)

    init {
        bt.attach(context)
    }

    fun hostBt() = bt.host()
    fun joinBt(peer: String) = bt.join(peer)

    private val msgCounter = AtomicLong(1)
    private val ackWait = ConcurrentHashMap<Long, PendingAck>()
    private val seenIds = ConcurrentHashMap.newKeySet<Pair<Long, Long>>() // (msgId) sender dedupe
    // deque: a NORMAL message preempted by an ALERT goes back to the FRONT
    private val normalQueue = java.util.concurrent.ConcurrentLinkedDeque<Wire.Frame>()
    private val alertQueue = ConcurrentLinkedQueue<Wire.Frame>()
    private val ttsRunning = AtomicBoolean(false)
    private val ttsMutex = Mutex()

    /** Alerts replay once after 1 s (on by default, per build spec 7.8). */
    @Volatile var replayAlerts: Boolean = true

    /** one engine per language — a single cached engine spoke every language with the first pack's voice */
    private val tts = ConcurrentHashMap<String, TtsSpeaker>()

    @Volatile private var speakingNow: TtsSpeaker? = null
    @Volatile private var speakingAlert = false

    data class PendingAck(val frame: Wire.Frame, @Volatile var sentAt: Long, @Volatile var attempts: Int = 1)

    init {
        // ACK retransmit loop: resend ACK_REQ frames after 800 ms, max 3 attempts.
        scope.launch {
            while (true) {
                delay(400)
                val now = System.currentTimeMillis()
                for ((id, p) in ackWait) {
                    if (now - p.sentAt >= 800) {
                        if (p.attempts >= 3) {
                            ackWait.remove(id)
                            markSent(id) { it.failed = true }
                            Log.w(TAG, "msg $id: no ACK after 3 attempts, giving up")
                        } else {
                            p.attempts++
                            p.sentAt = now
                            link.send(p.frame)
                        }
                    }
                }
            }
        }
    }

    // ---- link lifecycle ----

    fun host() {
        lastJoin = null
        link.host()
    }

    fun join(host: String) {
        lastJoin = null
        link.join(host)
    }

    fun join2(host: String, port: Int) = joinAuto(host, port)
    fun disconnect() {
        lastJoin = null
        link.close()
    }

    @Volatile private var lastJoin: Pair<String, Int>? = null

    private fun onLinkState(s: LinkState, detail: String?) {
        state.value = s to detail
        if (s == LinkState.CONNECTED) {
            // the peer's msgId counter restarts at 1 on reconnect — stale ids would
            // make every message after a reconnect look like a duplicate and be dropped
            seenIds.clear()
            sendHello()
            lastJoin = null // stop rejoin attempts once connected
        }
        Log.i(TAG, "link: $s $detail")
    }

    /**
     * Join with automatic reconnect (backoff 2/5/10 s) until [disconnect] or a
     * fresh join/host is requested. Used by the client side so a frozen/sleeping
     * peer recovers without user interaction.
     */
    fun joinAuto(host: String, port: Int = isro.itantra.transport.TcpLink.PORT) {
        lastJoin = host to port
        scope.launch {
            val delays = longArrayOf(0, 2000, 5000, 10000)
            for (d in delays) {
                delay(d)
                if (lastJoin == null) return@launch
                link.join(host, port)
                delay(3000)
                if (state.value.first == LinkState.CONNECTED) return@launch
            }
            Log.w(TAG, "joinAuto gave up after 4 attempts")
            lastJoin = null
        }
    }

    // ---- send path ----

    fun sendText(text: String, lang: String, alert: Boolean, tSpeechEndUs: Long? = null) {
        var fl = Wire.FLAG_FINAL.toInt() or Wire.FLAG_ACK_REQ.toInt()
        if (alert) fl = fl or Wire.FLAG_ALERT.toInt()
        if (tSpeechEndUs != null) fl = fl or Wire.FLAG_HAS_TIMING.toInt()
        val frame = Wire.Frame(
            type = Wire.TYPE_TEXT,
            flags = fl.toByte(),
            langId = Wire.langId(lang),
            msgId = msgCounter.getAndIncrement(),
            payload = text.toByteArray(Charsets.UTF_8),
            tSpeechEndUs = tSpeechEndUs,
            tSendUs = System.nanoTime() / 1000,
        )
        history.value = history.value + Message(frame.msgId, text, lang, alert, fromMe = true)
        // socket writes must never run on the caller's (often main) thread
        scope.launch(Dispatchers.IO) {
            if (!link.send(frame)) {
                // no queue-on-disconnect: say so in the UI rather than pretend it went
                markSent(frame.msgId) { it.failed = true }
                Log.w(TAG, "send failed (not connected) — msg ${frame.msgId} marked failed")
                return@launch
            }
            if (frame.ackReq) ackWait[frame.msgId] = PendingAck(frame, System.currentTimeMillis())
        }
    }

    fun sendHello() {
        val langs = supportedTtsLangs()
        val hello = Wire.Frame(
            type = Wire.TYPE_HELLO,
            langId = 0,
            msgId = msgCounter.getAndIncrement(),
            payload = JSONObject()
                .put("name", android.os.Build.MODEL)
                .put("langs", JSONArrayOf(langs))
                .toString().toByteArray(Charsets.UTF_8),
        )
        link.send(hello)
    }

    private fun JSONArrayOf(l: List<String>) =
        org.json.JSONArray().apply { l.forEach { put(it) } }

    private fun ack(msgId: Long) {
        link.send(Wire.Frame(Wire.TYPE_ACK, msgId = msgId))
    }

    // ---- receive path ----

    private fun onFrame(f: Wire.Frame) {
        when (f.type) {
            Wire.TYPE_HELLO -> {
                val json = runCatching { JSONObject(f.text) }.getOrNull()
                val langs = json?.optJSONArray("langs")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                peerLangs.value = langs ?: emptyList()
                Log.i(TAG, "peer hello: ${json?.optString("name")} langs=$langs")
            }
            Wire.TYPE_TEXT -> onText(f)
            Wire.TYPE_ACK -> {
                ackWait.remove(f.msgId)
                markSent(f.msgId) { it.acked = true; it.failed = false }
            }
            Wire.TYPE_PING -> {
                val t1 = android.os.SystemClock.elapsedRealtime() * 1000
                val payload = clock.pongPayloadFromPing(f.payload, t1)
                if (payload != null) link.send(Wire.Frame(Wire.TYPE_PONG, msgId = f.msgId, payload = payload))
            }
            Wire.TYPE_PONG -> {
                val (t0, t1) = clock.parsePong(f.payload) ?: return
                val t3 = android.os.SystemClock.elapsedRealtime() * 1000
                val sample = clock.add(t0, t1, t3)
                lastRttMs.value = sample.rttUs / 1000
            }
            Wire.TYPE_BYE -> onLinkState(LinkState.DISCONNECTED, "peer said bye")
        }
    }

    private fun onText(f: Wire.Frame) {
        // dedupe by msg id (retransmits + replays)
        if (!seenIds.add(f.msgId to 0L)) {
            ack(f.msgId) // already have it; still ack so the sender stops retrying
            return
        }
        if (f.ackReq) ack(f.msgId)
        val lang = Wire.langCode(f.langId)
        history.value = history.value + Message(f.msgId, f.text, lang, f.alert, fromMe = false)
        if (f.hasTiming && f.tSpeechEndUs != null) {
            val recvUs = android.os.SystemClock.elapsedRealtime() * 1000
            val t = MetricsExporter.fromFrame(f, recvUs, 0)
            if (t != null) timings.value = timings.value + t
            // no clock samples yet? seed one immediately so e2e stays meaningful
            if (clock.best() == null) {
                val t0 = android.os.SystemClock.elapsedRealtime() * 1000
                link.send(Wire.Frame(Wire.TYPE_PING, msgId = 900_000 + (t0 % 100000), payload = java.nio.ByteBuffer.allocate(8).putLong(t0).array()))
            }
        }
        if (!peerLangs.value.contains(lang) && peerLangs.value.isNotEmpty()) {
            Log.w(TAG, "peer lacks TTS pack for $lang (HELLO said ${peerLangs.value})")
        }
        if (f.alert) {
            alertQueue.add(f)
            // real preemption: cut the NORMAL message that is speaking right now at
            // its next clause boundary; speakFrame re-queues it to be finished after
            if (!speakingAlert) speakingNow?.cancel()
        } else {
            normalQueue.add(f)
        }
        pumpTts()
    }

    /** ALERT preempts NORMAL (paused-at-clause-boundary semantics: we simply finish current clause). */
    private fun pumpTts() {
        if (!ttsRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (true) {
                    val f = alertQueue.poll() ?: normalQueue.poll() ?: break
                    speakFrame(f)
                }
            } finally {
                ttsRunning.set(false)
            }
            // a frame enqueued between the last poll and the flag reset would
            // otherwise sit silent until the next message happened to arrive
            if (alertQueue.isNotEmpty() || normalQueue.isNotEmpty()) pumpTts()
        }
    }

    private suspend fun speakFrame(f: Wire.Frame) {
        ttsMutex.withLock {
            val lang = Wire.langCode(f.langId)
            val speaker = ttsFor(lang)
            if (speaker == null) {
                Log.e(TAG, "no TTS pack for '$lang' — message dropped from speech queue")
                return
            }
            val tBefore = android.os.SystemClock.elapsedRealtime()
            val stats = try {
                alertPlaying.value = f.alert
                speaking.value = true // phone mode: gate mic while we speak
                speakingAlert = f.alert
                speakingNow = speaker
                val s = speaker.speak(f.text, alert = f.alert, lang = lang)
                if (f.alert && replayAlerts && !s.interrupted) {
                    delay(1000)
                    speaker.speak(f.text, alert = true, lang = lang) // replay once (setting, default on)
                }
                s
            } finally {
                // a throwing engine used to leave speaking=true forever, which
                // permanently muted the microphone in phone mode
                speakingNow = null
                speakingAlert = false
                speaking.value = false
                alertPlaying.value = false
            }
            if (stats.interrupted) {
                normalQueue.addFirst(f) // finish it once the alert is done
                Log.i(TAG, "msg ${f.msgId} preempted by ALERT — re-queued")
                return
            }
            val tAfter = android.os.SystemClock.elapsedRealtime()
            // update matching history entry with e2e delta when timing was sent
            if (f.hasTiming && f.tSpeechEndUs != null) {
                val corrected = clock.toLocal(f.tSpeechEndUs)
                val e2eMs = corrected?.let { (tBefore * 1000 - it) / 1000 }
                    ?: ((tBefore * 1000 - f.tSpeechEndUs) / 1000) // fallback: unsynced clocks
                updateHistory(f.msgId) { it.e2eDeltaMs = e2eMs }
                timings.value = timings.value.map {
                    if (it.msgId == f.msgId && it.e2eMs == null) {
                        it.copy().apply {
                            this.e2eMs = e2eMs
                            uncertaintyMs = clock.uncertaintyUs() / 1000
                        }
                    } else it
                }
            }
            Log.i(TAG, "spoke msg ${f.msgId} lang=$lang alert=${f.alert} in ${tAfter - tBefore}ms")
        }
    }

    private fun updateHistory(id: Long, edit: (Message) -> Unit) {
        history.value = history.value.map {
            if (it.id == id && !it.fromMe) {
                val copy = it.copy(); edit(copy); copy
            } else it
        }
    }

    /** Same, for messages we sent (delivery ticks). */
    private fun markSent(id: Long, edit: (Message) -> Unit) {
        history.value = history.value.map {
            if (it.id == id && it.fromMe) {
                val copy = it.copy(); edit(copy); copy
            } else it
        }
    }

    /**
     * Local playback through the same cached engines the receive path uses —
     * the Listen tab used to reload the whole model on every press.
     */
    suspend fun speakLocal(text: String, lang: String, alert: Boolean = false): TtsSpeaker.Stats? =
        ttsMutex.withLock {
            val speaker = ttsFor(lang) ?: return@withLock null
            try {
                speaking.value = true
                speakingNow = speaker
                speaker.speak(text, alert = alert, lang = lang)
            } finally {
                speakingNow = null
                speaking.value = false
            }
        }

    private fun ttsFor(lang: String): TtsSpeaker? {
        tts[lang]?.let { return it }
        if (!isro.itantra.packs.PackRegistry.isTtsInstalled(context, lang)) return null
        // ponytail: engines stay loaded for the session; evict LRU if 10 packs of RAM hurts
        return runCatching { TtsSpeaker.load(context, lang) }.getOrNull()?.also { tts[lang] = it }
    }

    fun exportBenchmarksCsv(): String {
        val rows = mutableListOf(MetricsExporter.HEADER)
        val rtt = lastRttMs.value
        timings.value.forEach { rows.add(MetricsExporter.toRow(it, rtt)) }
        return MetricsExporter.exportCsv(context, rows)
    }

    fun supportedTtsLangs(): List<String> =
        Wire.LANGUAGES.filter { isro.itantra.packs.PackRegistry.isTtsInstalled(context, it) }

    fun release() {
        link.close()
        tts.values.forEach { it.close() }
        tts.clear()
    }

    companion object {
        private const val TAG = "ITANTRA"

        @Volatile private var instance: CommApp? = null

        fun get(context: Context): CommApp =
            instance ?: synchronized(this) {
                instance ?: CommApp(context.applicationContext).also { instance = it }
            }
    }
}
