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
 * iTantra's visual system — updated dark military-grade neural transceiver UI.
 */

val Ground = Color(0xFF060709) // Deep black background
val DarkCard = Color(0xFF12161E) // Card surface background
val Panel = Color(0xFF1B212C) // Slightly raised surface / input bg
val Ink = Color(0xFF2E3A4E) // Structural ink-blue
val InkLift = Color(0xFF3D4E68) // Elevated ink
val Signal = Color(0xFFF79E1B) // Warm Amber / Gold primary accent
val PurpleAccent = Color(0xFFA67CFF) // Purple CTA accent for Splash screen
val Slip = Color(0xFFE6ECEF) // Off-white / light text
val Alarm = Color(0xFFD93A2B) // Emergency alerts
val ActiveGreen = Color(0xFF4CD964) // Active status green
val StandbyBlue = Color(0xFF34AADC) // Standby status blue
val Mute = Color(0xFF7E8896) // Secondary muted text

val Mono = FontFamily.Monospace
val Sans = FontFamily.SansSerif

private val scheme = darkColorScheme(
    primary = Signal,
    onPrimary = Ground,
    secondary = InkLift,
    onSecondary = Slip,
    background = Ground,
    onBackground = Slip,
    surface = DarkCard,
    onSurface = Slip,
    surfaceVariant = Panel,
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
        fontFamily = Sans, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
    ),
    // section eyebrows
    labelSmall = TextStyle(
        fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp,
    ),
    // instrument readouts: ids, latency, ACK
    labelMedium = TextStyle(
        fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Mono, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp,
    ),
    // human speech
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontSize = 16.sp, fontWeight = FontWeight.Bold),
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
 * The carrier bar — link state indicator.
 */
@Composable
fun CarrierBar(state: LinkState, live: Boolean, alert: Boolean, modifier: Modifier = Modifier) {
    val target = when {
        alert -> Alarm
        state == LinkState.CONNECTED -> ActiveGreen
        state == LinkState.ERROR -> Alarm
        state == LinkState.HOSTING || state == LinkState.CONNECTING -> Signal
        else -> Ink
    }
    val colour by animateColorAsState(target, label = "carrier")
    val searching = state == LinkState.HOSTING || state == LinkState.CONNECTING
    val sweeping = animationsEnabled() && (searching || live)

    val phase by rememberInfiniteTransition(label = "carrier").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "phase",
    )

    Canvas(modifier.fillMaxWidth().height(2.dp)) {
        val solid = state == LinkState.CONNECTED || live
        drawRect(colour.copy(alpha = if (solid) 0.85f else 0.30f))
        if (sweeping) {
            val w = size.width * 0.22f
            drawRect(colour, topLeft = Offset((size.width + w) * phase - w, 0f), size = Size(w, size.height))
        }
    }
}

/** Small tracked-out label. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, colour: Color = Signal) {
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

