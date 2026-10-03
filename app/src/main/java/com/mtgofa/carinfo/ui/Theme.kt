package com.mtgofa.carinfo.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.mtgofa.carinfo.R

@Immutable
data class Palette(
    val dark: Boolean,
    val cyan: Color, val magenta: Color, val green: Color, val amber: Color, val red: Color,
    val violet: Color, val blue: Color,
    val bgTop: Color, val bgMid: Color, val bgBottom: Color,
    val card: Color, val cardBorder: Color, val inset: Color, val text: Color, val dim: Color,
)

val LightPalette = Palette(
    dark = false,
    cyan = Color(0xFF0A8DB5), magenta = Color(0xFFC0248E), green = Color(0xFF12A058), amber = Color(0xFFCC8400),
    red = Color(0xFFDD2F3F), violet = Color(0xFF6A4BD4), blue = Color(0xFF2C6BE0),
    bgTop = Color(0xFFF6F7FB), bgMid = Color(0xFFF2F3F8), bgBottom = Color(0xFFECEEF5),
    card = Color.White, cardBorder = Color(0xFFE3E5EF), inset = Color(0xFFF3F4F9),
    text = Color(0xFF1A1C2A), dim = Color(0xFF6C7088),
)

/** Night palette for the dashboard: deep purple-navy with lit-up digits. */
val DarkPalette = Palette(
    dark = true,
    cyan = Color(0xFF22E5FF), magenta = Color(0xFFFF3DCB), green = Color(0xFF39FF88), amber = Color(0xFFFFC23D),
    red = Color(0xFFFF4D5E), violet = Color(0xFFA98BFF), blue = Color(0xFF4D8DFF),
    bgTop = Color(0xFF2B1747), bgMid = Color(0xFF1B1238), bgBottom = Color(0xFF0D1430),
    card = Color(0xCC1D1838), cardBorder = Color(0x26FFFFFF), inset = Color(0x40000000),
    text = Color(0xFFEFEDFF), dim = Color(0xFF9C96C4),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

/** Current palette colours; light everywhere except inside [DarkSection]. */
object AppColors {
    val cyan: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.cyan
    val magenta: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.magenta
    val green: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.green
    val amber: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.amber
    val red: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.red
    val violet: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.violet
    val blue: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.blue
    val card: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.card
    val cardBorder: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.cardBorder
    val inset: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.inset
    val text: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.text
    val dim: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.dim
}

val Sora = FontFamily(
    Font(R.font.sora_semibold, FontWeight.SemiBold),
    Font(R.font.sora_bold, FontWeight.Bold),
)
val Body = FontFamily(Font(R.font.plex_regular, FontWeight.Normal))
val Digits = FontFamily(Font(R.font.plexmono_medium, FontWeight.Medium))

private fun scheme(p: Palette) = if (p.dark) darkColorScheme(
    primary = p.cyan, onPrimary = Color(0xFF041218), secondary = p.magenta, background = p.bgMid,
    surface = Color(0xFF1D1838), surfaceVariant = Color(0xFF2A2450), onSurface = p.text,
    onSurfaceVariant = p.dim, onBackground = p.text, error = p.red,
) else lightColorScheme(
    primary = p.cyan, onPrimary = Color.White, secondary = p.magenta, background = p.bgMid,
    surface = Color.White, surfaceVariant = p.inset, onSurface = p.text,
    onSurfaceVariant = p.dim, onBackground = p.text, error = p.red,
)

@Composable
private fun ProvidePalette(p: Palette, content: @Composable () -> Unit) {
    val view = LocalView.current
    DisposableEffect(p.dark) {
        val window = (view.context as? Activity)?.window
        val ctl = window?.let { WindowCompat.getInsetsController(it, view) }
        ctl?.isAppearanceLightStatusBars = !p.dark
        ctl?.isAppearanceLightNavigationBars = !p.dark
        onDispose {
            ctl?.isAppearanceLightStatusBars = true
            ctl?.isAppearanceLightNavigationBars = true
        }
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme(p), content = content)
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) = ProvidePalette(LightPalette, content)

/** Wraps a screen in the dark dashboard palette. */
@Composable
fun DarkSection(content: @Composable () -> Unit) = ProvidePalette(DarkPalette, content)

@Composable
fun AppBackground(content: @Composable () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(p.bgTop, p.bgMid, p.bgBottom)))
    ) { content() }
}

/**
 * Big readout digits. In the dark palette they're lit: a wide soft halo, a tighter glow,
 * then a near-white hot core. In the light palette they're plain solid colour.
 */
@Composable
fun GlowText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontFamily: FontFamily = Digits,
    fontWeight: FontWeight = FontWeight.Medium,
) {
    if (!LocalPalette.current.dark) {
        Text(
            text, modifier, color = color, maxLines = 1,
            style = TextStyle(fontSize = fontSize, fontFamily = fontFamily, fontWeight = fontWeight),
        )
        return
    }
    val core = lerp(color, Color.White, 0.55f)
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            text,
            style = TextStyle(
                color = color.copy(alpha = 0.55f), fontSize = fontSize, fontFamily = fontFamily, fontWeight = fontWeight,
                shadow = Shadow(color, blurRadius = fontSize.value * 1.1f),
            ),
            maxLines = 1,
        )
        Text(
            text,
            style = TextStyle(
                color = core, fontSize = fontSize, fontFamily = fontFamily, fontWeight = fontWeight,
                shadow = Shadow(color.copy(alpha = 0.9f), blurRadius = fontSize.value * 0.35f),
            ),
            maxLines = 1,
        )
    }
}

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val p = LocalPalette.current
    Column(
        modifier
            .then(if (p.dark) Modifier else Modifier.shadow(3.dp, shape, ambientColor = Color(0x22303060), spotColor = Color(0x22303060)))
            .clip(shape)
            .background(p.card)
            .border(1.dp, accent?.copy(alpha = if (p.dark) 0.55f else 0.35f) ?: p.cardBorder, shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(14.dp),
        content = content,
    )
}

@Composable
fun Logo(fontSize: TextUnit = 24.sp) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = AppColors.text)) { append("CAR") }
            withStyle(SpanStyle(color = AppColors.cyan)) { append("INFO") }
        },
        style = TextStyle(fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = fontSize, letterSpacing = 3.sp),
    )
}

@Composable
fun ScreenScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    AppBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = AppColors.text)
                }
                Text(
                    title, color = AppColors.text, fontFamily = Sora, fontWeight = FontWeight.SemiBold,
                    fontSize = 19.sp, modifier = Modifier.weight(1f),
                )
                actions()
            }
            content()
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 3.dp, height = 14.dp).background(AppColors.cyan, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), color = AppColors.dim, fontSize = 12.sp, letterSpacing = 1.5.sp, fontFamily = Sora, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = AppColors.cyan,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(14.dp)
    val dark = LocalPalette.current.dark
    Box(
        modifier
            .clip(shape)
            .background(color.copy(alpha = if (enabled) (if (dark) 0.16f else 0.10f) else 0.05f))
            .border(1.dp, color.copy(alpha = if (enabled) 0.8f else 0.25f), shape)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, color = if (!enabled) AppColors.dim else if (dark) lerp(color, Color.White, 0.4f) else color,
            fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
        )
    }
}

/** Colour for a temperature by sensor: blue = cold, green = normal, amber/red = hot. */
@Composable
fun tempColor(pid: Int, c: Double?): Color {
    val p = LocalPalette.current
    if (c == null) return p.violet
    val (cold, warn, hot) = when (pid) {
        0x05, 0x67 -> Triple(60.0, 104.0, 110.0)
        0x5C -> Triple(70.0, 120.0, 135.0)
        0x0F, 0x68 -> Triple(-100.0, 55.0, 70.0)
        0x46 -> Triple(5.0, 40.0, 48.0)
        0x3C, 0x3D, 0x3E, 0x3F -> Triple(250.0, 800.0, 900.0)
        0x78, 0x79 -> Triple(150.0, 750.0, 850.0)
        else -> Triple(40.0, 100.0, 120.0)
    }
    return when {
        c < cold -> p.cyan
        c >= hot -> p.red
        c >= warn -> p.amber
        else -> p.green
    }
}
