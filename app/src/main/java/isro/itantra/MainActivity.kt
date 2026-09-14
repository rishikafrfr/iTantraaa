package isro.itantra

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import isro.itantra.comm.CommApp
import isro.itantra.engine.SttEngine
import isro.itantra.packs.PackCatalog
import isro.itantra.packs.PackDownloader
import isro.itantra.packs.PackRegistry
import isro.itantra.protocol.Wire
import isro.itantra.service.CommService
import isro.itantra.session.TalkSession
import isro.itantra.transport.LinkState
import isro.itantra.ui.Alarm
import isro.itantra.ui.CarrierBar
import isro.itantra.ui.Eyebrow
import isro.itantra.ui.Ground
import isro.itantra.ui.Ink
import isro.itantra.ui.ItantraTheme
import isro.itantra.ui.Mono
import isro.itantra.ui.Mute
import isro.itantra.ui.Panel
import isro.itantra.ui.Readout
import isro.itantra.ui.Signal
import isro.itantra.ui.Slip
import isro.itantra.ui.animationsEnabled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The whole interface. Four destinations: Talk (mic → STT → peer), Listen
 * (text → TTS on this phone), Connect (the link and the conversation), Packs
 * (voice/recognition models).
 *
 * Visual system lives in [isro.itantra.ui] — see Panel.kt for why it looks the
 * way it does.
 */
class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var session: TalkSession? = null

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else status = getString(R.string.no_mic)
        }

    /** The relay's foreground notification is invisible on Android 13+ without this. */
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var pendingBt: (() -> Unit)? = null
    private val btPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pendingBt?.invoke() else status = getString(R.string.no_bt)
            pendingBt = null
        }

    /**
     * BLUETOOTH_CONNECT became a runtime permission in API 31. It was only ever
     * declared in the manifest, so every Bluetooth action threw SecurityException
     * on a modern phone.
     */
    private fun withBt(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingBt = action
            btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ItantraTheme { App() } }
        // phone mode: gate mic whenever our TTS is speaking (duplex echo guard)
        scope.launch { comm.speaking.collect { session?.setMuted(it) } }
        // receiver-side background resilience: keep the comm stack foregrounded
        CommService.start(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onDestroy() {
        session?.release()
        scope.cancel() // the speaking-collector otherwise keeps this Activity alive forever
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        // ALERT playback is non-interruptible: volume keys must not lower it
        if (comm.alertPlaying.value &&
            (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == android.view.KeyEvent.KEYCODE_VOLUME_MUTE)
        ) {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            am.setStreamVolume(
                android.media.AudioManager.STREAM_ALARM,
                am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM),
                0,
            )
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---- state ----

    private var transcripts by mutableStateOf(listOf<SttEngine.Result>())
    private var listening by mutableStateOf(false)

    /** Talk-screen errors only. Other screens keep their own local state — a
     *  shared status string leaked "exported to …" into the Talk error banner. */
    private var status by mutableStateOf("")
    private var lang by mutableStateOf("en")
    private var alertMode by mutableStateOf(false)

    /** bumped after an install/delete so every screen re-reads the installed set */
    private var packsEpoch by mutableIntStateOf(0)

    private val comm by lazy { CommApp.get(applicationContext) }

    private fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startListening() {
        if (!PackRegistry.isSttInstalled(applicationContext)) {
            status = getString(R.string.no_stt)
            return
        }
        CommService.micActive = true
        CommService.start(this)
        scope.launch {
            session?.release()
            session = TalkSession(applicationContext, scope) { r ->
                scope.launch {
                    transcripts = transcripts + r
                    // The tag must describe the transcript, not a selection made
                    // before anyone spoke: the STT model emits whatever script it
                    // heard, so a Hindi sentence tagged "en" got read aloud by the
                    // English voice. Devanagari serves both hi and mr, so an
                    // explicit Marathi choice still wins.
                    // The recogniser writes Hindustani in Urdu script about half the
                    // time — heard correctly, but in a script no voice pack can
                    // pronounce. Transliterate instead of dropping it: the abjad
                    // maps onto the abugida without restoring short vowels.
                    val romanised = isro.itantra.nlp.UrduToDevanagari.looksUrdu(r.text)
                    val text = if (romanised) isro.itantra.nlp.UrduToDevanagari.convert(r.text) else r.text
                    val script = isro.itantra.nlp.TextNormaliser.detectScriptLang(text)
                    val tag = if (script == "hi" && lang == "mr") "mr" else script
                    android.util.Log.i(
                        "ITANTRA",
                        "heard '${r.text}' (${r.decodeMs}ms, rtf ${"%.2f".format(r.rtf)})" +
                            (if (romanised) " -> urdu->devanagari '$text'" else "") +
                            " script=$script sending as ${tag ?: "<dropped>"}",
                    )
                    if (tag == null) {
                        status = getString(R.string.unsupported_script)
                        return@launch
                    }
                    status = ""
                    // timestamps use the monotonic clock (µs) for latency tracing
                    comm.sendText(
                        text, tag, alertMode,
                        tSpeechEndUs = android.os.SystemClock.elapsedRealtime() * 1000 - r.decodeMs * 1000,
                    )
                }
            }.also { s ->
                runCatching {
                    // the selected language directs the recogniser so the transcript
                    // comes back in the right script
                    s.load(lang)
                    s.start()
                    listening = true
                }.onFailure { status = it.message ?: "could not start the microphone" }
            }
        }
    }

    private fun stopListening() {
        session?.stop()
        CommService.micActive = false // the relay kept claiming the mic FGS type
        listening = false
    }

    // ---- shell ----

    @Composable
    private fun App() {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        val state by comm.state.collectAsState()
        val speaking by comm.speaking.collectAsState()
        val alertPlaying by comm.alertPlaying.collectAsState()

        Scaffold(
            containerColor = Ground,
            topBar = { Header(state.first, speaking || listening, alertPlaying) },
            bottomBar = { Nav(tab) { tab = it } },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    0 -> TalkScreen()
                    1 -> ListenScreen()
                    2 -> LinkScreen()
                    else -> PacksScreen()
                }
            }
        }
    }

    @Composable
    private fun Header(state: LinkState, live: Boolean, alert: Boolean) {
        val rtt by comm.lastRttMs.collectAsState()
        Column(Modifier.background(Ground)) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "iTANTRA",
                    style = MaterialTheme.typography.displaySmall,
                    color = Slip,
                    modifier = Modifier.weight(1f),
                )
                Readout(
                    linkLabel(state).uppercase() + (rtt?.let { " · $it MS" }.takeIf { state == LinkState.CONNECTED } ?: ""),
                    colour = when (state) {
                        LinkState.CONNECTED -> Signal
                        LinkState.ERROR -> Alarm
                        else -> Mute
                    },
                )
            }
            CarrierBar(state, live, alert)
        }
    }

    private fun linkLabel(s: LinkState): String = getString(
        when (s) {
            LinkState.CONNECTED -> R.string.link_up
            LinkState.HOSTING -> R.string.link_hosting
            LinkState.CONNECTING -> R.string.link_connecting
            LinkState.ERROR -> R.string.link_error
            else -> R.string.link_none
        }
    )

    @Composable
    private fun Nav(tab: Int, onSelect: (Int) -> Unit) {
        val items = listOf(
            "◉" to stringResource(R.string.talk),
            "♪" to stringResource(R.string.listen),
            "⇄" to stringResource(R.string.connect),
            "▤" to stringResource(R.string.packs),
        )
        NavigationBar(containerColor = Panel, tonalElevation = 0.dp) {
            items.forEachIndexed { i, (glyph, label) ->
                NavigationBarItem(
                    selected = tab == i,
                    onClick = { onSelect(i) },
                    icon = { Text(glyph, fontFamily = Mono, fontSize = 17.sp) },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Signal,
                        selectedTextColor = Signal,
                        // a solid amber pill read as a blob and swallowed the glyph
                        indicatorColor = Signal.copy(alpha = 0.14f),
                        unselectedIconColor = Mute,
                        unselectedTextColor = Mute,
                    ),
                )
            }
        }
    }

    // ---- talk ----

    @Composable
    private fun TalkScreen() {
        val sttInstalled = remember(packsEpoch) { PackRegistry.isSttInstalled(applicationContext) }
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Spacer(Modifier.height(6.dp))
            // every language is speakable: the recogniser is not limited to the
            // voice packs this handset happens to hold
            LanguageRow(langs = Wire.LANGUAGES, onPick = { picked ->
                lang = picked
                // the language is baked into the recogniser config, so switching it
                // mid-session has to rebuild the engine or you keep transcribing
                // with the previous language's model
                if (listening) { stopListening(); startListening() }
            })
            TransmitKey()
            if (!sttInstalled) Notice(stringResource(R.string.no_stt))
            if (status.isNotBlank()) Notice(status)

            Eyebrow(stringResource(R.string.recognised).removeSuffix(":"))
            if (transcripts.isEmpty()) {
                Empty(stringResource(R.string.empty_transcripts))
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(transcripts.asReversed()) { r ->
                        Column(
                            Modifier.fillMaxWidth()
                                .background(Panel, RoundedCornerShape(3.dp))
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(r.text, style = MaterialTheme.typography.bodyLarge, color = Slip)
                            Readout("${r.decodeMs} MS · RTF ${"%.2f".format(r.rtf)}")
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }

    /**
     * The transmit key. A wide slab rather than a round FAB — it is the one
     * control that matters and it should be findable without looking, the way a
     * radio's PTT is. The amber ring breathes only while the mic is open.
     */
    @Composable
    private fun TransmitKey() {
        val on = listening
        val pulse by rememberInfiniteTransition(label = "ptt").animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "pulse",
        )
        val ring by animateColorAsState(if (on) Signal else Ink, label = "ring")
        val alpha = if (on && animationsEnabled()) pulse else 1f

        Surface(
            onClick = {
                status = ""
                when {
                    listening -> stopListening()
                    hasMic() -> startListening()
                    else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            shape = RoundedCornerShape(4.dp),
            color = if (on) Signal.copy(alpha = 0.10f) else Panel,
            modifier = Modifier.fillMaxWidth().height(132.dp)
                .border(if (on) 2.dp else 1.dp, ring.copy(alpha = alpha), RoundedCornerShape(4.dp)),
        ) {
            Column(
                Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (on) "◉" else "○",
                    fontFamily = Mono,
                    fontSize = 34.sp,
                    color = if (on) Signal else Mute,
                    modifier = Modifier.alpha(alpha),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (on) stringResource(R.string.listening) else stringResource(R.string.start_listening),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) Signal else Slip,
                )
            }
        }
    }

    // ---- listen ----

    @Composable
    private fun ListenScreen() {
        var text by rememberSaveable { mutableStateOf("Attention please. This is an emergency alert message.") }
        var result by remember { mutableStateOf("") }
        val speaking by comm.speaking.collectAsState()
        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // this screen plays audio HERE, so it is limited to locally installed voices
            LanguageRow(langs = remember(packsEpoch) { PackRegistry.ttsLanguages(applicationContext) })
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().weight(1f),
                label = { Text(stringResource(R.string.text_to_speak), style = MaterialTheme.typography.labelMedium) },
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = fieldColours(),
            )
            Button(
                onClick = {
                    val t = text
                    val l = lang
                    result = ""
                    scope.launch(Dispatchers.IO) {
                        // shared cached engine — this screen used to reload the whole
                        // model on every press, seconds per tap on Indic packs
                        val s = runCatching { comm.speakLocal(t, l) }
                        result = s.fold(
                            onSuccess = { st ->
                                st?.let { "${it.synthMs} ms · first clause ${it.firstClauseMs} ms · RTF ${"%.2f".format(it.rtf)}" }
                                    ?: getString(R.string.no_tts)
                            },
                            onFailure = { it.message ?: "playback failed" },
                        )
                    }
                },
                enabled = !speaking,
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Signal, contentColor = Ground),
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) {
                Text(
                    if (speaking) "▶ ▶ ▶" else "▶  " + stringResource(R.string.speak).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            if (result.isNotBlank()) Readout(result.uppercase())
        }
    }

    /**
     * Language chips + the alert latch.
     *
     * [langs] differs per screen on purpose. What you can *speak* is bounded by
     * the recogniser (all of them), not by which voice packs this handset holds
     * — those matter on the receiver. Driving every screen off the installed TTS
     * list is why a phone with three voice packs offered only three languages to
     * talk in, silently transcribing Kannada with the English model.
     */
    @Composable
    private fun LanguageRow(
        langs: List<String>,
        showAlert: Boolean = false,
        onPick: (String) -> Unit = { lang = it },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // the chip row scrolls, and the default (en) sorts last in Wire.LANGUAGES,
            // so the selection was off-screen with nothing appearing selected
            Eyebrow(stringResource(R.string.language) + " · " + langLabel(lang))
            if (langs.isEmpty()) {
                Notice(stringResource(R.string.no_tts))
            } else {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    langs.forEach { l ->
                        Chip(langLabel(l), selected = lang == l, accent = Signal) { onPick(l) }
                    }
                    if (showAlert) {
                        Chip(stringResource(R.string.alert), selected = alertMode, accent = Alarm) {
                            alertMode = !alertMode
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Chip(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(2.dp),
            color = if (selected) accent else Color.Transparent,
            modifier = Modifier.border(1.dp, if (selected) accent else Ink, RoundedCornerShape(2.dp)),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) Ground else Mute,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            )
        }
    }

    private fun langLabel(code: String): String = when (code) {
        "hi" -> "हिन्दी"; "gu" -> "ગુજરાતી"; "mr" -> "मराठी"; "kn" -> "ಕನ್ನಡ"
        "ml" -> "മലയാളം"; "ta" -> "தமிழ்"; "te" -> "తెలుగు"; "or" -> "ଓଡ଼ିଆ"
        "bn" -> "বাংলা"; else -> "English"
    }

    // ---- link ----

    @Composable
    private fun LinkScreen() {
        val state by comm.state.collectAsState()
        val history by comm.history.collectAsState()
        val peers by comm.peerLangs.collectAsState()
        val down = state.first == LinkState.DISCONNECTED || state.first == LinkState.ERROR
        val listState = rememberLazyListState()

        LaunchedEffect(history.size) {
            if (history.isNotEmpty()) listState.animateScrollToItem(0)
        }

        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.height(6.dp))
            if (down) Dialer() else LiveStrip(state.second)

            state.second?.takeIf { state.first == LinkState.ERROR }?.let { Notice(it) }
            if (peers.isNotEmpty() && !peers.contains(lang)) {
                Notice("Peer has no ${langLabel(lang)} voice — it has ${peers.joinToString()}")
            }

            LanguageRow(langs = Wire.LANGUAGES, showAlert = true)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var quick by rememberSaveable { mutableStateOf("") }
                OutlinedTextField(
                    value = quick,
                    onValueChange = { quick = it },
                    label = { Text(stringResource(R.string.quick_send), style = MaterialTheme.typography.labelMedium) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    singleLine = true,
                    colors = fieldColours(),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        if (quick.isNotBlank()) {
                            comm.sendText(quick, lang, alertMode)
                            quick = ""
                        }
                    },
                    enabled = quick.isNotBlank(),
                    shape = RoundedCornerShape(3.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (alertMode) Alarm else Signal,
                        contentColor = Ground,
                    ),
                    modifier = Modifier.height(56.dp),
                ) { Text(stringResource(R.string.send).uppercase(), style = MaterialTheme.typography.labelMedium) }
            }

            Eyebrow(stringResource(R.string.messages).removeSuffix(":"))
            if (history.isEmpty()) {
                Empty(stringResource(R.string.empty_messages))
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(history.asReversed(), key = { "${it.fromMe}-${it.id}" }) { m -> MessageRow(m) }
                }
            }
            Metrics()
            Spacer(Modifier.height(6.dp))
        }
    }

    /** Host / join controls. Collapses away entirely once the carrier is up. */
    @Composable
    private fun Dialer() {
        var addr by rememberSaveable { mutableStateOf("10.0.2.2") }
        var peer by rememberSaveable { mutableStateOf("") }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Eyebrow("Wi-Fi")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { comm.host() },
                    shape = RoundedCornerShape(3.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Signal),
                    modifier = Modifier.height(56.dp),
                ) { Text(stringResource(R.string.host_wifi).uppercase(), style = MaterialTheme.typography.labelMedium) }
                OutlinedTextField(
                    value = addr,
                    onValueChange = { addr = it },
                    label = { Text(stringResource(R.string.peer_ip), style = MaterialTheme.typography.labelMedium) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = fieldColours(),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { comm.join(addr) },
                    enabled = addr.isNotBlank(),
                    shape = RoundedCornerShape(3.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Signal, contentColor = Ground),
                    modifier = Modifier.height(56.dp),
                ) { Text(stringResource(R.string.join_wifi).uppercase(), style = MaterialTheme.typography.labelMedium) }
            }
            Eyebrow("Bluetooth", modifier = Modifier.padding(top = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { withBt { comm.hostBt() } },
                    shape = RoundedCornerShape(3.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Mute),
                    modifier = Modifier.height(52.dp),
                ) {
                    Text(
                        stringResource(R.string.host_wifi).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                OutlinedTextField(
                    value = peer,
                    onValueChange = { peer = it },
                    label = { Text(stringResource(R.string.paired_device), style = MaterialTheme.typography.labelMedium) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = fieldColours(),
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { withBt { comm.joinBt(peer) } },
                    enabled = peer.isNotBlank(),
                    shape = RoundedCornerShape(3.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Mute),
                    modifier = Modifier.height(52.dp),
                ) { Text(stringResource(R.string.join_wifi).uppercase(), style = MaterialTheme.typography.labelMedium) }
            }
        }
    }

    @Composable
    private fun LiveStrip(detail: String?) {
        Row(
            Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(3.dp)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Eyebrow(stringResource(R.string.link_up), colour = Signal)
                detail?.let { Readout(it, colour = Slip) }
            }
            TextButton(onClick = { comm.disconnect() }) {
                Text(
                    stringResource(R.string.disconnect).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Mute,
                )
            }
        }
    }

    /**
     * Received messages are paper slips coming off the receiver; sent messages
     * are ink on the panel. The asymmetry is the point — one side of this
     * conversation was spoken into a phone, the other side came off a wire.
     */
    @Composable
    private fun MessageRow(m: CommApp.Message) {
        val time = remember(m.t) { SimpleDateFormat("HH:mm", Locale.US).format(Date(m.t)) }
        Row(Modifier.fillMaxWidth()) {
            if (m.fromMe) Spacer(Modifier.weight(0.12f))
            Column(
                Modifier.weight(0.88f)
                    .background(
                        if (m.fromMe) Ink else Slip,
                        if (m.fromMe) {
                            RoundedCornerShape(14.dp, 2.dp, 14.dp, 14.dp)
                        } else {
                            RoundedCornerShape(2.dp, 14.dp, 14.dp, 14.dp)
                        },
                    )
                    .then(
                        if (m.alert) Modifier.border(2.dp, Alarm, RoundedCornerShape(8.dp)) else Modifier
                    )
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                if (m.alert) Eyebrow(stringResource(R.string.alert), colour = Alarm)
                Text(
                    m.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (m.fromMe) Slip else Ground,
                )
                Readout(
                    buildString {
                        append(m.lang.uppercase()).append(" · ").append(time)
                        if (m.fromMe) {
                            append(" · ")
                            append(
                                when {
                                    m.acked -> "✓ " + getString(R.string.ack)
                                    m.failed -> "✕ " + getString(R.string.not_sent)
                                    else -> "· " + getString(R.string.sending)
                                }.uppercase()
                            )
                        } else {
                            m.e2eDeltaMs?.let { append(" · E2E ").append(it).append(" MS") }
                        }
                    },
                    colour = when {
                        m.fromMe && m.failed -> Alarm
                        m.fromMe && m.acked -> Signal
                        m.fromMe -> Mute
                        else -> Ground.copy(alpha = 0.55f)
                    },
                )
            }
            if (!m.fromMe) Spacer(Modifier.weight(0.12f))
        }
    }

    @Composable
    private fun Metrics() {
        val rtt by comm.lastRttMs.collectAsState()
        val timings by comm.timings.collectAsState()
        val last = timings.lastOrNull { it.e2eMs != null }
        var exported by remember { mutableStateOf("") }
        Column(
            Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(3.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Live metrics", modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        exported = "saved to " + comm.exportBenchmarksCsv()
                    }
                }) {
                    Text(
                        stringResource(R.string.export_csv).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Signal,
                    )
                }
            }
            Readout(
                "RTT ${rtt ?: "—"} MS · CLOCK ±${comm.clock.uncertaintyUs() / 1000} MS · ${timings.size} MSGS"
            )
            last?.let {
                Text(
                    "Last end-to-end ${it.e2eMs} ms (±${it.uncertaintyMs ?: 0} ms)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Slip,
                )
            }
            if (exported.isNotBlank()) Readout(exported.uppercase(), colour = Signal)
        }
    }

    // ---- packs ----

    @Composable
    private fun PacksScreen() {
        // keyed on packsEpoch, which install/delete bumps. The old code held this in
        // a mutableStateOf with no remember{}, so it was rebuilt on every
        // recomposition and the post-install refresh never reached the screen.
        val packs = remember(packsEpoch) { PackRegistry.installed(applicationContext) }
        val downloader = remember { PackDownloader(applicationContext) }
        val dlStatus = remember { mutableStateMapOf<String, String>() }
        val dlProgress = remember { mutableStateMapOf<String, Pair<Long, Long>>() }

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Spacer(Modifier.height(6.dp)); Eyebrow(stringResource(R.string.installed_voices)) }

            if (packs.isEmpty()) {
                item { Empty(stringResource(R.string.empty_packs)) }
            }
            items(packs, key = { it.id }) { p ->
                Row(
                    Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(3.dp)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(p.id, style = MaterialTheme.typography.titleMedium, color = Slip)
                        Readout("${p.kind.uppercase()} · ${p.lang.uppercase()} · ${p.sizeMb} MB")
                        Text(
                            p.license,
                            style = MaterialTheme.typography.labelSmall,
                            color = Mute,
                            maxLines = 2,
                        )
                    }
                    TextButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            p.dir.deleteRecursively() // was running on the main thread
                            packsEpoch++
                        }
                    }) {
                        Text(
                            stringResource(R.string.delete).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = Alarm,
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(10.dp))
                Eyebrow(stringResource(R.string.available_voices))
                Text(
                    stringResource(R.string.download_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Mute,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            val installed = packs.map { it.id }.toSet()
            items(PackCatalog.entries.filter { it.id !in installed }, key = { it.id }) { e ->
                val st = dlStatus[e.id]
                val busy = st == "downloading" || st == "verifying"
                Column(
                    Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(3.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(e.title, style = MaterialTheme.typography.titleMedium, color = Slip)
                            Readout("${e.approxMb} MB")
                            Text(e.license, style = MaterialTheme.typography.labelSmall, color = Mute, maxLines = 2)
                        }
                        Button(
                            onClick = {
                                dlStatus[e.id] = "downloading"
                                scope.launch(Dispatchers.IO) {
                                    val ok = downloader.download(e) { p ->
                                        when (p) {
                                            is PackDownloader.Progress.Downloading -> {
                                                dlStatus[e.id] = "downloading"
                                                dlProgress[e.id] = p.doneBytes to p.totalBytes
                                            }
                                            is PackDownloader.Progress.Verifying -> dlStatus[e.id] = "verifying"
                                            is PackDownloader.Progress.Done -> dlStatus[e.id] = "done"
                                            is PackDownloader.Progress.Failed -> dlStatus[e.id] = p.message
                                        }
                                    }
                                    if (ok) packsEpoch++
                                }
                            },
                            enabled = !busy,
                            shape = RoundedCornerShape(3.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Signal, contentColor = Ground),
                        ) {
                            Text(
                                stringResource(R.string.install).uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    if (st != null) {
                        val pr = dlProgress[e.id]
                        if (busy && pr != null && pr.second > 0) {
                            LinearProgressIndicator(
                                progress = { (pr.first.toFloat() / pr.second).coerceIn(0f, 1f) },
                                color = Signal,
                                trackColor = Ink,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Readout("${pr.first / 1_000_000} / ${pr.second / 1_000_000} MB")
                        } else {
                            Readout(
                                when (st) {
                                    "downloading" -> "STARTING…"
                                    "verifying" -> "VERIFYING CHECKSUM…"
                                    "done" -> "INSTALLED"
                                    else -> st.uppercase()
                                },
                                colour = if (st == "done") Signal else if (busy) Mute else Alarm,
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(10.dp)) }
        }
    }

    // ---- shared bits ----

    /** Something is wrong and here is what to do about it. */
    @Composable
    private fun Notice(text: String) {
        Row(
            Modifier.fillMaxWidth()
                .background(Alarm.copy(alpha = 0.12f), RoundedCornerShape(3.dp))
                .border(1.dp, Alarm.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
                .padding(12.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Slip)
        }
    }

    /** An empty screen is an invitation to act, not a shrug. */
    @Composable
    private fun ColumnScope.Empty(text: String) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = Mute,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }

    @Composable
    private fun Empty(text: String) {
        Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Mute)
        }
    }

    @Composable
    private fun fieldColours() = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Signal,
        unfocusedBorderColor = Ink,
        focusedLabelColor = Signal,
        unfocusedLabelColor = Mute,
        cursorColor = Signal,
        focusedTextColor = Slip,
        unfocusedTextColor = Slip,
    )
}
