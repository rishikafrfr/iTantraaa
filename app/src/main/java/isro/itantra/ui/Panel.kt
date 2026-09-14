package isro.itantra.ui

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import isro.itantra.transport.LinkState

/**
 * iTantra's visual system — a field instrument, not a chat app.
 *
 * The whole product thesis is "text crosses the wire, voice happens at both
 * ends", so the interface splits those two worlds typographically: machine
 * state (link, ids, latency, acknowledgements) is monospace, small and tracked
 * out; human speech is large sans, because that is the thing a person in the
 * field has to read at arm's length in bad light.
 *
 * Dark only, on purpose: this is used at night in the field, and a white screen
 * destroys night vision. Graphite rather than black so the panel has depth.
 */

val Ground = Color(0xFF14161A) // graphite panel body
val Panel = Color(0xFF1D2127) // raised surface
val Ink = Color(0xFF2E3A4E) // structural ink-blue: dividers, sent messages
val InkLift = Color(0xFF3D4E68) // ink, one step up
val Signal = Color(0xFFF2A22C) // signal amber — live carrier, transmitting. Nothing else.
val Slip = Color(0xFFE8E2D4) // telegram paper — the received message slip
val Alarm = Color(0xFFD93A2B) // alerts only, never decorative
val Mute = Color(0xFF8A93A3) // secondary readouts

val Mono = FontFamily.Monospace
val Sans = FontFamily.SansSerif

private val scheme = darkColorScheme(
    primary = Signal,
    onPrimary = Ground,
    secondary = InkLift,
    onSecondary = Slip,
    background = Ground,
    onBackground = Slip,
    surface = Panel,
    onSurface = Slip,
    surfaceVariant = Ink,
    onSurfaceVariant = Mute,
    error = Alarm,
    onError = Slip,
    errorContainer = Alarm,
    onErrorContainer = Slip,
    outline = Ink,
    outlineVariant = Ink,
)

private val typography = Typography(
    // wordmark
    displaySmall = TextStyle(
        fontFamily = Mono, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp,
    ),
    // section eyebrows: RECEIVED / LANGUAGE / LINK
    labelSmall = TextStyle(
        fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.8.sp,
    ),
    // instrument readouts: ids, latency, ACK
    labelMedium = TextStyle(
        fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Mono, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp,
    ),
    // human speech — deliberately large, Indic scripts need the room
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 19.sp, lineHeight = 27.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 15.sp, lineHeight = 21.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun ItantraTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)

/** Honours the system "remove animations" accessibility setting. */
@Composable
fun animationsEnabled(): Boolean {
    val cr = LocalContext.current.contentResolver
    return remember {
        Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
}

/**
 * The carrier bar — this app's one instrument, and the only place motion lives.
 * It sits under the header on every screen and *is* the link: dim when there is
 * none, a travelling pulse while reaching for a peer, solid amber when the
 * carrier is up, and red while an alert is playing.
 */
@Composable
fun CarrierBar(state: LinkState, live: Boolean, alert: Boolean, modifier: Modifier = Modifier) {
    val target = when {
        alert -> Alarm
        state == LinkState.CONNECTED -> Signal
        state == LinkState.ERROR -> Alarm
        state == LinkState.HOSTING || state == LinkState.CONNECTING -> InkLift
        else -> Ink
    }
    val colour by animateColorAsState(target, label = "carrier")
    val searching = state == LinkState.HOSTING || state == LinkState.CONNECTING
    val sweeping = animationsEnabled() && (searching || live)

    // the transition is always created (composition must be unconditional); only
    // the drawing is gated, so a still bar costs nothing visually
    val phase by rememberInfiniteTransition(label = "carrier").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "phase",
    )

    Canvas(modifier.fillMaxWidth().height(3.dp)) {
        val solid = state == LinkState.CONNECTED || live
        drawRect(colour.copy(alpha = if (solid) 0.85f else 0.30f))
        if (sweeping) {
            val w = size.width * 0.22f
            drawRect(colour, topLeft = Offset((size.width + w) * phase - w, 0f), size = Size(w, size.height))
        }
    }
}

/** Small tracked-out monospace label. Names a region; never decorates one. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, colour: Color = Mute) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = colour,
        modifier = modifier,
    )
}

/** Instrument readout: ids, latency, ACK state. */
@Composable
fun Readout(text: String, modifier: Modifier = Modifier, colour: Color = Mute) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = colour, modifier = modifier)
}
