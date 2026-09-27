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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import isro.itantra.comm.CommApp
import isro.itantra.engine.SttEngine
import isro.itantra.nlp.TextNormaliser
import isro.itantra.nlp.UrduToDevanagari
import isro.itantra.packs.PackCatalog
import isro.itantra.packs.PackDownloader
import isro.itantra.packs.PackRegistry
import isro.itantra.protocol.Wire
import isro.itantra.service.CommService
import isro.itantra.session.TalkSession
import isro.itantra.transport.LinkState
import isro.itantra.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import isro.itantra.NotificationHelper

class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var session: TalkSession? = null

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else status = getString(R.string.no_mic)
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var pendingBt: (() -> Unit)? = null

    private val btPermissions =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->

            val connectGranted =
                result[Manifest.permission.BLUETOOTH_CONNECT] == true

            val advertiseGranted =
                result[Manifest.permission.BLUETOOTH_ADVERTISE] == true

            val scanGranted =
                result[Manifest.permission.BLUETOOTH_SCAN] == true

            if (connectGranted && advertiseGranted && scanGranted) {
                pendingBt?.invoke()
            } else {
                status = getString(R.string.no_bt)
            }

            pendingBt = null
        }

    private fun withBt(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < 31) {
            action()
            return
        }

        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        )

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) ==
                    PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            action()
        } else {
            pendingBt = action
            btPermissions.launch(permissions)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationHelper.createChannels(this)
        setContent { ItantraTheme { App() } }
        scope.launch { comm.speaking.collect { session?.setMuted(it) } }
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
        scope.cancel()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
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

    private var status by mutableStateOf("")
    private var lang by mutableStateOf("hi")
    private var alertMode by mutableStateOf(false)
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
                    val romanised = UrduToDevanagari.looksUrdu(r.text)
                    val text = if (romanised) UrduToDevanagari.convert(r.text) else r.text
                    val script = TextNormaliser.detectScriptLang(text)
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
                    comm.sendText(
                        text, tag, alertMode,
                        tSpeechEndUs = android.os.SystemClock.elapsedRealtime() * 1000 - r.decodeMs * 1000,
                    )
                }
            }.also { s ->
                runCatching {
                    s.load(lang)
                    s.start()
                    listening = true
                }.onFailure { status = it.message ?: "could not start the microphone" }
            }
        }
    }

    private fun stopListening() {
        session?.stop()
        CommService.micActive = false
        listening = false
    }

    // ---- Main Shell ----

    @Composable
    private fun App() {
        var showSplash by rememberSaveable { mutableStateOf(true) }
        var tab by rememberSaveable { mutableIntStateOf(0) } // 0: Message, 1: Connection, 2: Models

        if (showSplash) {
            SplashConnectScreen(onConnect = {
                showSplash = false
                tab = 1
            })
        } else {
            Scaffold(
                containerColor = Ground,
                bottomBar = { NavigationBottomBar(tab) { tab = it } },
            ) { pad ->
                Box(Modifier.padding(pad).fillMaxSize()) {
                    when (tab) {
                        0 -> TextMessageScreen(onNavigateToModels = { tab = 2 })
                        1 -> ConnectDeviceScreen()
                        else -> ModelsScreen()
                    }
                }
            }
        }
    }

    // ==========================================
    // 1. SPLASH / INTRO SCREEN (Image 1)
    // ==========================================
    @Composable
    private fun SplashConnectScreen(onConnect: () -> Unit) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Ground)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.weight(1f))

            Text(
                "iTantra",
                style = MaterialTheme.typography.displaySmall.copy(
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.sp
                ),
                color = Color.White,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "Indian Multilingual Neural Transceiver",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = Signal,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(48.dp))

            Text(
                "LOW-BANDWIDTH COMMUNICATION",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 13.sp,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = Color.White.copy(alpha = 0.9f),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "STT • TEXT • BLUETOOTH • WIFI • TTS",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    letterSpacing = 1.sp
                ),
                color = Mute,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.weight(1f))

            Button(
                onClick = onConnect,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PurpleAccent,
                    contentColor = Color(0xFF14161A)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text(
                    "CONNECT DEVICE",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // ==========================================
    // 2. CONNECT TO DEVICE SCREEN (Image 2)
    // ==========================================
    @Composable
    private fun ConnectDeviceScreen() {
        var activeTab by rememberSaveable { mutableIntStateOf(0) } // 0: Wifi, 1: Bluetooth
        val state by comm.state.collectAsState()
        val peers by comm.peerLangs.collectAsState()
        val rtt by comm.lastRttMs.collectAsState()
        var wifiAddr by rememberSaveable { mutableStateOf("10.0.2.2") }
        var btPeer by rememberSaveable { mutableStateOf("") }

        Column(
            Modifier
                .fillMaxSize()
                .background(Ground)
                .statusBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(16.dp))

            Text(
                "iTantra",
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 28.sp),
                color = Color.White,
            )

            Spacer(Modifier.height(4.dp))

            Text(
                "CONNECT TO DEVICE",
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp),
                color = Signal,
            )

            Spacer(Modifier.height(16.dp))

            // Wi-Fi / Bluetooth Toggle Row
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    onClick = { activeTab = 0 },
                    shape = RoundedCornerShape(8.dp),
                    color = if (activeTab == 0) DarkCard else Color.Transparent,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (activeTab == 0) Signal else Ink
                    ),
                    modifier = Modifier.height(42.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(if (activeTab == 0) Signal else Mute, CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Wi-Fi (Active)",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (activeTab == 0) Signal else Mute
                        )
                    }
                }

                Surface(
                    onClick = { activeTab = 1 },
                    shape = RoundedCornerShape(8.dp),
                    color = if (activeTab == 1) DarkCard else Panel,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (activeTab == 1) Signal else Color.Transparent
                    ),
                    modifier = Modifier.height(42.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(if (activeTab == 1) Signal else Mute, CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Bluetooth",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (activeTab == 1) Signal else Mute
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Active Connection Card
            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    when (state.first) {
                        LinkState.CONNECTED -> ActiveGreen
                        LinkState.ERROR -> Alarm
                        LinkState.HOSTING, LinkState.CONNECTING -> Signal
                        else -> Ink
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .background(
                                        when (state.first) {
                                            LinkState.CONNECTED -> ActiveGreen
                                            LinkState.ERROR -> Alarm
                                            LinkState.HOSTING, LinkState.CONNECTING -> Signal
                                            else -> Mute
                                        },
                                        CircleShape
                                    )
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when (state.first) {
                                    LinkState.CONNECTED -> "CONNECTED"
                                    LinkState.HOSTING -> "HOSTING SERVER"
                                    LinkState.CONNECTING -> "CONNECTING…"
                                    LinkState.ERROR -> "LINK ERROR"
                                    else -> "DISCONNECTED"
                                },
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = when (state.first) {
                                    LinkState.CONNECTED -> ActiveGreen
                                    LinkState.ERROR -> Alarm
                                    LinkState.HOSTING, LinkState.CONNECTING -> Signal
                                    else -> Mute
                                }
                            )
                        }
                        state.second?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = Slip)
                        }
                        rtt?.takeIf { state.first == LinkState.CONNECTED }?.let {
                            Text("RTT latency: $it ms", style = MaterialTheme.typography.labelSmall, color = Mute)
                        }
                    }

                    if (state.first != LinkState.DISCONNECTED && state.first != LinkState.ERROR) {
                        OutlinedButton(
                            onClick = { comm.disconnect() },
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Alarm),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Alarm)
                        ) {
                            Text("Disconnect", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // Error notice if applicable
            state.second?.takeIf { state.first == LinkState.ERROR }?.let { err ->
                Spacer(Modifier.height(8.dp))
                NoticeCard(err)
            }

            if (peers.isNotEmpty() && !peers.contains(lang)) {
                Spacer(Modifier.height(8.dp))
                NoticeCard("Peer lacks ${langLabel(lang)} voice — it holds ${peers.joinToString()}")
            }

            Spacer(Modifier.height(16.dp))

            // Sub-header row
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (activeTab == 0) "Wi-Fi Endpoints & Direct Join" else "Bluetooth Pairing & Direct Join",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Slip.copy(alpha = 0.8f),
                )
            }

            Spacer(Modifier.height(12.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (activeTab == 0) {
                    // Wi-Fi Controls Card
                    item {
                        Card(
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = Panel),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Ink),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Wi-Fi IP Network Connect", style = MaterialTheme.typography.titleMedium, color = Color.White)
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = wifiAddr,
                                        onValueChange = { wifiAddr = it },
                                        label = { Text("Peer IP / Host", style = MaterialTheme.typography.labelSmall) },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Signal,
                                            unfocusedBorderColor = Ink,
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedContainerColor = DarkCard,
                                            unfocusedContainerColor = DarkCard
                                        ),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Button(
                                        onClick = { comm.join(wifiAddr) },
                                        enabled = wifiAddr.isNotBlank(),
                                        shape = RoundedCornerShape(6.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Signal, contentColor = Ground),
                                        modifier = Modifier.height(52.dp)
                                    ) {
                                        Text("JOIN", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                Button(
                                    onClick = { comm.host() },
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = DarkCard, contentColor = Signal),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Signal),
                                    modifier = Modifier.fillMaxWidth().height(44.dp)
                                ) {
                                    Text("HOST WI-FI TCP SERVER", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }

                    // Predefined Common Transceiver Targets
                    val wifiPresets = listOf(
                        DeviceItem("Local Emulator Host", "WIFI TCP", "10.0.2.2", "-42 DBM", ActiveGreen),
                        DeviceItem("Wi-Fi Direct P2P Group", "WIFI P2P", "192.168.49.1", "-54 DBM", StandbyBlue),
                        DeviceItem("Field LAN Transceiver", "WIFI TCP", "192.168.1.100", "-61 DBM", StandbyBlue)
                    )

                    items(wifiPresets) { dev ->
                        DeviceCard(device = dev, onConnect = {
                            wifiAddr = dev.address
                            comm.join(dev.address)
                        })
                    }
                } else {
                    // Bluetooth Controls Card
                    item {
                        Card(
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = Panel),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Ink),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Bluetooth RFCOMM Connect", style = MaterialTheme.typography.titleMedium, color = Color.White)
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = btPeer,
                                        onValueChange = { btPeer = it },
                                        label = { Text("Paired Device Name", style = MaterialTheme.typography.labelSmall) },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Signal,
                                            unfocusedBorderColor = Ink,
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedContainerColor = DarkCard,
                                            unfocusedContainerColor = DarkCard
                                        ),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Button(
                                        onClick = { withBt { comm.joinBt(btPeer) } },
                                        enabled = btPeer.isNotBlank(),
                                        shape = RoundedCornerShape(6.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Signal, contentColor = Ground),
                                        modifier = Modifier.height(52.dp)
                                    ) {
                                        Text("JOIN", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                Button(
                                    onClick = { withBt { comm.hostBt() } },
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = DarkCard, contentColor = Signal),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Signal),
                                    modifier = Modifier.fillMaxWidth().height(44.dp)
                                ) {
                                    Text("HOST BLUETOOTH RFCOMM", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }

                    val btPresets = listOf(
                        DeviceItem("Priya's Phone", "BLUETOOTH", "Priya's Phone", "-48 DBM", StandbyBlue),
                        DeviceItem("Field_Unit_Alpha", "BLUETOOTH", "Field_Unit_Alpha", "-55 DBM", ActiveGreen)
                    )

                    items(btPresets) { dev ->
                        DeviceCard(device = dev, onConnect = {
                            btPeer = dev.address
                            withBt { comm.joinBt(dev.address) }
                        })
                    }
                }
            }

            Text(
                "Connect to a nearby device to start transmitting neural audio and text.",
                style = MaterialTheme.typography.bodyMedium,
                color = Mute.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            )
        }
    }

    private data class DeviceItem(
        val name: String,
        val proto: String,
        val address: String,
        val rssi: String,
        val dotColor: Color
    )

    @Composable
    private fun DeviceCard(device: DeviceItem, onConnect: () -> Unit) {
        Card(
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Panel),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(16.dp)
                        .background(device.dotColor, CircleShape)
                )

                Spacer(Modifier.width(16.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        device.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                        color = Color.White
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${device.proto}  •  ${device.address}  •  ${device.rssi}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = Mute
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tap to connect →",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                        color = Signal
                    )
                }

                Button(
                    onClick = onConnect,
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Ink,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        "Connect",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }

    // ==========================================
    // 3. TEXT MESSAGE SCREEN (Image 3)
    // ==========================================
    @Composable
    private fun TextMessageScreen(onNavigateToModels: () -> Unit) {
        val history by comm.history.collectAsState()
        val linkState by comm.state.collectAsState()
        val speaking by comm.speaking.collectAsState()
        val sttInstalled = remember(packsEpoch) { PackRegistry.isSttInstalled(applicationContext) }
        var textInput by rememberSaveable { mutableStateOf("") }
        var dropdownExpanded by remember { mutableStateOf(false) }

        val languagesMap = listOf(
            "hi" to "हिन्दी (Hindi)",
            "en" to "English",
            "mr" to "मराठी (Marathi)",
            "gu" to "ગુજરાતી (Gujarati)",
            "kn" to "ಕನ್ನಡ (Kannada)",
            "ta" to "தமிழ் (Tamil)",
            "te" to "తెలుగు (Telugu)",
            "ml" to "മലയാളം (Malayalam)",
            "bn" to "বাংলা (Bengali)",
            "or" to "ଓડ଼ିଆ (Odia)"
        )

        val listState = rememberLazyListState()
        LaunchedEffect(history.size) {
            if (history.isNotEmpty()) listState.animateScrollToItem(0)
        }

        Column(
            Modifier
                .fillMaxSize()
                .background(Ground)
                .statusBarsPadding()
        ) {
            // --- Header Toolbar ---
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Language Dropdown Box
                    Box {
                        Surface(
                            onClick = { dropdownExpanded = true },
                            shape = RoundedCornerShape(6.dp),
                            color = Panel,
                            border = androidx.compose.foundation.BorderStroke(1.dp, Ink)
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Language: ",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Mute
                                )
                                Text(
                                    languagesMap.firstOrNull { it.first == lang }?.second ?: langLabel(lang),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Color.White
                                )
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = Mute
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false },
                            modifier = Modifier.background(DarkCard)
                        ) {
                            languagesMap.forEach { (code, label) ->
                                DropdownMenuItem(
                                    text = { Text(label, color = Slip) },
                                    onClick = {
                                        lang = code
                                        dropdownExpanded = false
                                        if (listening) {
                                            stopListening()
                                            startListening()
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // Live Connection Status Pill
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = when (linkState.first) {
                            LinkState.CONNECTED -> ActiveGreen.copy(alpha = 0.15f)
                            LinkState.ERROR -> Alarm.copy(alpha = 0.15f)
                            LinkState.HOSTING, LinkState.CONNECTING -> Signal.copy(alpha = 0.15f)
                            else -> DarkCard
                        },
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            when (linkState.first) {
                                LinkState.CONNECTED -> ActiveGreen
                                LinkState.ERROR -> Alarm
                                LinkState.HOSTING, LinkState.CONNECTING -> Signal
                                else -> Ink
                            }
                        )
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(
                                        when (linkState.first) {
                                            LinkState.CONNECTED -> ActiveGreen
                                            LinkState.ERROR -> Alarm
                                            LinkState.HOSTING, LinkState.CONNECTING -> Signal
                                            else -> Mute
                                        },
                                        CircleShape
                                    )
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                when (linkState.first) {
                                    LinkState.CONNECTED -> "LINK : CONNECTED"
                                    LinkState.HOSTING -> "LINK : HOSTING"
                                    LinkState.CONNECTING -> "LINK : CONNECTING"
                                    LinkState.ERROR -> "LINK ERROR"
                                    else -> "LINK : OFFLINE"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                ),
                                color = when (linkState.first) {
                                    LinkState.CONNECTED -> ActiveGreen
                                    LinkState.ERROR -> Alarm
                                    LinkState.HOSTING, LinkState.CONNECTING -> Signal
                                    else -> Mute
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Pipeline Indicator Row
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "STT  ->  TEXT  ->  COMM  ->  TTS",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        ),
                        color = Signal
                    )

                    Text(
                        if (alertMode) "ALERT ACTIVE" else "MODE: DIRECT",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            letterSpacing = 1.sp
                        ),
                        color = if (alertMode) Alarm else Mute
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Session Status Chip
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DarkCard,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .background(if (linkState.first == LinkState.CONNECTED) StandbyBlue else Mute, CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            buildString {
                                append("ITANTRA SESSION • ")
                                if (linkState.first == LinkState.CONNECTED) {
                                    append("LINK ACTIVE • ")
                                    append((linkState.second ?: "PEER").uppercase())
                                } else {
                                    append("STANDALONE TRANSCEIVER")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = Slip.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            // Warning Notice if STT missing or error
            if (!sttInstalled) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Alarm.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Alarm),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "No STT speech recognition pack installed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Slip,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onNavigateToModels) {
                            Text("INSTALL PACK", style = MaterialTheme.typography.labelSmall, color = Signal)
                        }
                    }
                }
            }

            if (status.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Alarm.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Alarm),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = Slip,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // --- Chat Messages Area ---
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (history.isEmpty()) {
                    item {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkCard),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Ink),
                            modifier = Modifier.fillMaxWidth().padding(top = 24.dp)
                        ) {
                            Column(
                                Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("📡", fontSize = 28.sp)
                                Text(
                                    "No Transmissions Yet",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White
                                )
                                Text(
                                    "Tap and hold the microphone below to speak, or type a message to transmit over the neural link.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Mute,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                } else {
                    items(history.asReversed(), key = { "${it.fromMe}-${it.id}" }) { msg ->
                        ChatMessageItem(
                            msg = msg,
                            peerName = linkState.second ?: "Remote Transceiver",
                            isSpeaking = speaking,
                            onReplay = {
                                scope.launch(Dispatchers.IO) {
                                    comm.speakLocal(msg.text, msg.lang)
                                }
                            }
                        )
                    }
                }
            }

            // STT Listening Waveform Banner
            if (listening) {
                Surface(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = DarkCard,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Signal)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .background(Signal, CircleShape)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "STT Engine Listening...",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = Signal
                                )
                            }
                            Text(
                                "16kHz / Mono • Neural STT",
                                style = MaterialTheme.typography.labelSmall,
                                color = Mute
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "❙❚❙❚❙❚❙❚❙❚❙",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = Signal
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Speak now into microphone…",
                                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                                color = Slip
                            )
                        }
                    }
                }
            }

            // --- Bottom Input & Action Panel ---
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Panel)
                    .padding(12.dp)
            ) {
                // Text Input Bar
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        placeholder = {
                            Text(
                                "Type a message to transmit...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Mute
                            )
                        },
                        trailingIcon = {
                            Text("⌨", fontSize = 18.sp, color = Mute)
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Signal,
                            unfocusedBorderColor = Ink,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = DarkCard,
                            unfocusedContainerColor = DarkCard
                        ),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )

                    Button(
                        onClick = {
                            if (textInput.isNotBlank()) {
                                comm.sendText(textInput, lang, alertMode)
                                textInput = ""
                            }
                        },
                        enabled = textInput.isNotBlank(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (alertMode) Alarm else Signal,
                            contentColor = Ground
                        ),
                        modifier = Modifier.size(52.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = Ground
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Action Controls Row
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Headphone icon (preview TTS locally)
                    IconButton(
                        onClick = {
                            if (textInput.isNotBlank()) {
                                scope.launch(Dispatchers.IO) { comm.speakLocal(textInput, lang) }
                            }
                        }
                    ) {
                        Text("🎧", fontSize = 18.sp)
                    }

                    // Language toggle icon
                    IconButton(onClick = { dropdownExpanded = true }) {
                        Text("文A", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Signal)
                    }

                    // Center Big Mic Button
                    val pulse by rememberInfiniteTransition(label = "mic").animateFloat(
                        initialValue = 0.5f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                        label = "pulse"
                    )
                    val micRingAlpha = if (listening) pulse else 1f

                    Surface(
                        onClick = {
                            status = ""
                            when {
                                listening -> stopListening()
                                hasMic() -> startListening()
                                else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = if (listening) Signal.copy(alpha = 0.2f) else Signal,
                        border = androidx.compose.foundation.BorderStroke(
                            2.dp,
                            if (listening) Signal.copy(alpha = micRingAlpha) else Color.Transparent
                        ),
                        modifier = Modifier.height(44.dp)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🎤", fontSize = 16.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (listening) "LISTENING…" else "TAP / HOLD MIC",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = if (listening) Signal else Ground
                            )
                        }
                    }

                    // Clear Input Icon
                    IconButton(onClick = { textInput = "" }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Clear",
                            tint = Mute
                        )
                    }

                    // Alert Mode Toggle
                    Surface(
                        onClick = { alertMode = !alertMode },
                        shape = RoundedCornerShape(10.dp),
                        color = if (alertMode) Alarm.copy(alpha = 0.18f) else DarkCard,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (alertMode) Alarm else Ink
                        ),
                        modifier = Modifier.height(44.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (alertMode) "🚨" else "📡",
                                fontSize = 17.sp
                            )

                            Spacer(Modifier.width(6.dp))

                            Text(
                                if (alertMode) "SEND ALERT" else "NORMAL",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = if (alertMode) Alarm else Mute
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ChatMessageItem(
        msg: CommApp.Message,
        peerName: String,
        isSpeaking: Boolean,
        onReplay: () -> Unit
    ) {
        val time = remember(msg.t) { SimpleDateFormat("HH:mm", Locale.US).format(Date(msg.t)) }

        if (msg.fromMe) {
            // Sent message
            Column(
                Modifier
                    .fillMaxWidth()
                    .wrapContentWidth(Alignment.End),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(time, style = MaterialTheme.typography.labelSmall, color = Mute)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "You (Transcribed)",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = Signal
                    )
                }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Panel,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (msg.alert) Alarm else Signal.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth(0.88f)
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (msg.alert) {
                            Text("EMERGENCY ALERT", style = MaterialTheme.typography.labelSmall, color = Alarm)
                        }
                        Text(
                            msg.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                buildString {
                                    append(msg.lang.uppercase()).append("  •  ")
                                    append(
                                        when {
                                            msg.acked -> "⚡ SENT & ACKED"
                                            msg.failed -> "✕ NOT DELIVERED"
                                            else -> "· TRANSMITTING"
                                        }
                                    )
                                },
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = when {
                                    msg.failed -> Alarm
                                    msg.acked -> Signal
                                    else -> Mute
                                }
                            )
                        }
                    }
                }
            }
        } else {
            // Received message
            Column(
                Modifier.fillMaxWidth(0.88f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(StandbyBlue, CircleShape)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        peerName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(time, style = MaterialTheme.typography.labelSmall, color = Mute)
                }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = DarkCard,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (msg.alert) Alarm else Ink
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (msg.alert) {
                            Text("EMERGENCY ALERT", style = MaterialTheme.typography.labelSmall, color = Alarm)
                        }
                        Text(
                            msg.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { onReplay() }
                            ) {
                                Text("🔊", fontSize = 14.sp)
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    if (isSpeaking) "TTS Playing…" else "TTS Synced (Tap to Replay)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StandbyBlue
                                )
                            }
                            Text(
                                buildString {
                                    append(msg.lang.uppercase())
                                    msg.e2eDeltaMs?.let { append(" • ${it}ms") }
                                },
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = ActiveGreen
                            )
                        }
                    }
                }
            }
        }
    }

    // ==========================================
    // 4. MODELS & LOCAL ENGINES SCREEN (Image 4)
    // ==========================================
    @Composable
    private fun ModelsScreen() {
        val installedPacks = remember(packsEpoch) { PackRegistry.installed(applicationContext) }
        val sttInstalled = remember(packsEpoch) { PackRegistry.isSttInstalled(applicationContext) }
        val downloader = remember { PackDownloader(applicationContext) }
        val dlStatus = remember { mutableStateMapOf<String, String>() }
        val dlProgress = remember { mutableStateMapOf<String, Pair<Long, Long>>() }

        val usableSpaceGb = remember(packsEpoch) {
            applicationContext.filesDir.usableSpace / (1024 * 1024 * 1024L)
        }
        val loadedMb = remember(packsEpoch) {
            installedPacks.sumOf { it.sizeMb }
        }

        Column(
            Modifier
                .fillMaxSize()
                .background(Ground)
                .statusBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(16.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "iTantra",
                        style = MaterialTheme.typography.displaySmall.copy(fontSize = 28.sp),
                        color = Color.White,
                    )
                    Text(
                        "MODELS & LOCAL ENGINES",
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                        color = Signal,
                    )
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Panel
                ) {
                    Text(
                        "V1.4-ENG",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = Mute,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Text(
                "Offline Speech (STT) & Synthesis (TTS) Neural Pipeline",
                style = MaterialTheme.typography.bodyMedium,
                color = Slip.copy(alpha = 0.7f),
            )

            Spacer(Modifier.height(16.dp))

            // Storage Status Bar
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = DarkCard,
                border = androidx.compose.foundation.BorderStroke(1.dp, Ink),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("💾", fontSize = 18.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Storage: $usableSpaceGb GB Free",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
                    }

                    Text(
                        "$loadedMb MB Loaded",
                        style = MaterialTheme.typography.labelSmall,
                        color = Signal
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f)
            ) {
                // STT Section
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "SPEECH RECOGNITION (STT)",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = Signal
                            )
                            Text(
                                if (sttInstalled) "1 Active" else "0 Active",
                                style = MaterialTheme.typography.labelSmall,
                                color = Mute
                            )
                        }

                        // Speech Recognition Model Cards from catalog
                        val sttEntries = PackCatalog.entries.filter { it.kind == "stt" }
                        sttEntries.forEach { e ->
                            val isThisInstalled = installedPacks.any { it.id == e.id || it.dir.name == e.destDir }
                            val st = dlStatus[e.id]
                            val busy = st == "downloading" || st == "verifying"
                            val pr = dlProgress[e.id]

                            Card(
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = DarkCard),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isThisInstalled) ActiveGreen else Ink
                                ),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                Modifier
                                                    .size(10.dp)
                                                    .background(if (isThisInstalled) ActiveGreen else Mute, CircleShape)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                e.title,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = Color.White
                                            )
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (isThisInstalled) ActiveGreen.copy(alpha = 0.2f) else Panel
                                        ) {
                                            Text(
                                                if (isThisInstalled) "ACTIVE" else "AVAILABLE",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = if (isThisInstalled) ActiveGreen else Mute,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "${e.modelType.uppercase()} • ${e.approxMb} MB",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Mute
                                    )

                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        e.license,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Slip.copy(alpha = 0.8f)
                                    )

                                    if (st != null) {
                                        Spacer(Modifier.height(8.dp))
                                        if (busy && pr != null && pr.second > 0) {
                                            LinearProgressIndicator(
                                                progress = { (pr.first.toFloat() / pr.second).coerceIn(0f, 1f) },
                                                color = Signal,
                                                trackColor = Ink,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                "${pr.first / 1_000_000} / ${pr.second / 1_000_000} MB",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Mute
                                            )
                                        } else {
                                            Text(
                                                when (st) {
                                                    "downloading" -> "STARTING DOWNLOAD…"
                                                    "verifying" -> "VERIFYING CHECKSUM…"
                                                    "done" -> "INSTALLED SUCCESSFULLY"
                                                    else -> st.uppercase()
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (st == "done") ActiveGreen else Alarm
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(12.dp))
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (isThisInstalled) {
                                            OutlinedButton(
                                                onClick = {
                                                    scope.launch(Dispatchers.IO) {
                                                        installedPacks.firstOrNull { it.id == e.id || it.dir.name == e.destDir }
                                                            ?.dir?.deleteRecursively()
                                                        packsEpoch++
                                                    }
                                                },
                                                shape = RoundedCornerShape(6.dp),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, Alarm),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Alarm)
                                            ) {
                                                Text("DELETE", style = MaterialTheme.typography.labelSmall)
                                            }
                                        } else {
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
                                                shape = RoundedCornerShape(6.dp),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = Signal,
                                                    contentColor = Ground
                                                )
                                            ) {
                                                Text("INSTALL PACK", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // TTS Section
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "TEXT TO SPEECH (TTS)",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = Signal
                            )
                            val ttsInstalledCount = PackCatalog.entries.filter { it.kind == "tts" }
                                .count { e -> installedPacks.any { it.id == e.id || it.dir.name == e.destDir } }
                            Text(
                                "$ttsInstalledCount Engines Installed",
                                style = MaterialTheme.typography.labelSmall,
                                color = Mute
                            )
                        }

                        val ttsEntries = PackCatalog.entries.filter { it.kind == "tts" }
                        ttsEntries.forEach { e ->
                            val isThisInstalled = installedPacks.any { it.id == e.id || it.dir.name == e.destDir }
                            val st = dlStatus[e.id]
                            val busy = st == "downloading" || st == "verifying"
                            val pr = dlProgress[e.id]

                            Card(
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = DarkCard),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isThisInstalled) ActiveGreen else Ink
                                ),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                Modifier
                                                    .size(10.dp)
                                                    .background(if (isThisInstalled) ActiveGreen else Mute, CircleShape)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                e.title,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = Color.White
                                            )
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (isThisInstalled) ActiveGreen.copy(alpha = 0.2f) else Panel
                                        ) {
                                            Text(
                                                if (isThisInstalled) "INSTALLED" else "AVAILABLE",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = if (isThisInstalled) ActiveGreen else Mute,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "${e.lang.uppercase()} • ${e.modelType.uppercase()} • ${e.approxMb} MB",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Mute
                                    )

                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        e.license,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Slip.copy(alpha = 0.8f)
                                    )

                                    if (st != null) {
                                        Spacer(Modifier.height(8.dp))
                                        if (busy && pr != null && pr.second > 0) {
                                            LinearProgressIndicator(
                                                progress = { (pr.first.toFloat() / pr.second).coerceIn(0f, 1f) },
                                                color = Signal,
                                                trackColor = Ink,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                "${pr.first / 1_000_000} / ${pr.second / 1_000_000} MB",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Mute
                                            )
                                        } else {
                                            Text(
                                                when (st) {
                                                    "downloading" -> "STARTING DOWNLOAD…"
                                                    "verifying" -> "VERIFYING CHECKSUM…"
                                                    "done" -> "INSTALLED SUCCESSFULLY"
                                                    else -> st.uppercase()
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (st == "done") ActiveGreen else Alarm
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(12.dp))
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (isThisInstalled) {
                                            OutlinedButton(
                                                onClick = {
                                                    scope.launch(Dispatchers.IO) {
                                                        comm.speakLocal("Testing neural voice synthesis output.", e.lang)
                                                    }
                                                },
                                                shape = RoundedCornerShape(6.dp),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, Signal),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Signal),
                                                modifier = Modifier.padding(end = 8.dp)
                                            ) {
                                                Text("TEST OUTPUT", style = MaterialTheme.typography.labelSmall)
                                            }

                                            OutlinedButton(
                                                onClick = {
                                                    scope.launch(Dispatchers.IO) {
                                                        installedPacks.firstOrNull { it.id == e.id || it.dir.name == e.destDir }
                                                            ?.dir?.deleteRecursively()
                                                        packsEpoch++
                                                    }
                                                },
                                                shape = RoundedCornerShape(6.dp),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, Alarm),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Alarm)
                                            ) {
                                                Text("DELETE", style = MaterialTheme.typography.labelSmall)
                                            }
                                        } else {
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
                                                shape = RoundedCornerShape(6.dp),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = Signal,
                                                    contentColor = Ground
                                                )
                                            ) {
                                                Text("INSTALL", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    // ==========================================
    // 5. SHARED NAVIGATION & UTILITY COMPONENTS
    // ==========================================
    @Composable
    private fun NavigationBottomBar(currentTab: Int, onSelect: (Int) -> Unit) {
        val items = listOf(
            Triple("💬", "Message", 0),
            Triple("📡", "Connection", 1),
            Triple("🧠", "Models", 2)
        )

        NavigationBar(
            containerColor = Panel,
            tonalElevation = 0.dp,
            modifier = Modifier.height(72.dp)
        ) {
            items.forEach { (emoji, label, index) ->
                val selected = currentTab == index
                NavigationBarItem(
                    selected = selected,
                    onClick = { onSelect(index) },
                    icon = {
                        Text(
                            emoji,
                            fontSize = 18.sp
                        )
                    },
                    label = {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 11.sp
                            )
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Signal,
                        selectedTextColor = Signal,
                        indicatorColor = Signal.copy(alpha = 0.15f),
                        unselectedIconColor = Mute,
                        unselectedTextColor = Mute
                    )
                )
            }
        }
    }

    @Composable
    private fun NoticeCard(text: String) {
        Card(
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Alarm.copy(alpha = 0.12f)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Alarm.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = Slip,
                modifier = Modifier.padding(12.dp)
            )
        }
    }

    private fun langLabel(code: String): String = when (code) {
        "hi" -> "हिन्दी (Hindi)"
        "gu" -> "ગુજરાતી (Gujarati)"
        "mr" -> "मराठी (Marathi)"
        "kn" -> "ಕನ್ನಡ (Kannada)"
        "ml" -> "മലയാളം (Malayalam)"
        "ta" -> "தமிழ் (Tamil)"
        "te" -> "తెలుగు (Telugu)"
        "or" -> "ଓଡ଼ିଆ (Odia)"
        "bn" -> "বাংলা (Bengali)"
        else -> "English"
    }
}
