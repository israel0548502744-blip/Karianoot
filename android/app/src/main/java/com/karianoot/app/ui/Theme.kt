package com.karianoot.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karianoot.app.R
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Design tokens: near-black night, warm champagne gold, glints of light. */
object C {
    val Bg = Color(0xFF050505)
    val BgTop = Color(0xFF0D0B08)
    val Card = Color(0xFF0F0E0C)
    val Stroke = Color(0x22F3D9A0)
    val Text = Color(0xFFF6EFE2)
    val Sub = Color(0xFFB9AE9A)
    val Faint = Color(0xFF7A7264)
    val Gold = Color(0xFFE3BF6A)
    val GoldLight = Color(0xFFFBE7B0)
    val GoldDeep = Color(0xFF9C7424)
    val Green = Color(0xFF7BD88F)
    val Red = Color(0xFFF08A7E)

    val GoldBrush = Brush.linearGradient(listOf(GoldLight, Gold, GoldDeep, Gold))
    val GoldButton = Brush.horizontalGradient(listOf(Color(0xFFF8E2A4), Color(0xFFDDB45A), Color(0xFFB88A33)))
    val Title = Brush.horizontalGradient(listOf(Color(0xFFFFF4D6), GoldLight, Gold, GoldLight))
    val GlassFill = Brush.verticalGradient(listOf(Color(0x14FFF4DC), Color(0x06FFF4DC)))
    val GlassEdge = Brush.verticalGradient(listOf(Color(0x40F3D9A0), Color(0x0AF3D9A0)))
}

@OptIn(ExperimentalTextApi::class)
private fun rubik(weight: Int) = Font(R.font.rubik, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Rubik = FontFamily(rubik(400), rubik(500), rubik(600), rubik(700), rubik(800))

@Composable
fun KarianootTheme(content: @Composable () -> Unit) {
    val base = TextStyle(fontFamily = Rubik, color = C.Text)
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Gold, secondary = C.GoldLight, background = C.Bg, surface = C.Card,
            onSurface = C.Text, onBackground = C.Text, outline = C.Stroke,
        ),
        typography = Typography().let { t ->
            Typography(
                displayLarge = t.displayLarge.merge(base), headlineLarge = t.headlineLarge.merge(base),
                headlineMedium = t.headlineMedium.merge(base), titleLarge = t.titleLarge.merge(base),
                titleMedium = t.titleMedium.merge(base), bodyLarge = t.bodyLarge.merge(base),
                bodyMedium = t.bodyMedium.merge(base), bodySmall = t.bodySmall.merge(base),
                labelLarge = t.labelLarge.merge(base), labelMedium = t.labelMedium.merge(base),
                labelSmall = t.labelSmall.merge(base),
            )
        },
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, content = content)
    }
}

private class Glint(val x: Float, val y: Float, val r: Float, val phase: Float, val speed: Float, val star: Boolean)

/** Black night with a warm glow at the top and gold dust that twinkles. */
@Composable
fun NightBackground(modifier: Modifier = Modifier) {
    val glints = remember {
        val rnd = Random(7)
        List(46) { Glint(rnd.nextFloat(), rnd.nextFloat(), 0.6f + rnd.nextFloat() * 1.8f, rnd.nextFloat() * 6.28f, 0.5f + rnd.nextFloat(), rnd.nextFloat() < 0.18f) }
    }
    val t by rememberInfiniteTransition(label = "night").animateFloat(0f, 1f,
        infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "t")
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(C.BgTop, C.Bg, C.Bg)))
        val w = size.width; val h = size.height
        drawCircle(Brush.radialGradient(listOf(Color(0x30E3BF6A), Color.Transparent), center = Offset(w * 0.75f, -h * 0.02f), radius = w * 0.95f),
            radius = w * 0.95f, center = Offset(w * 0.75f, -h * 0.02f))
        drawCircle(Brush.radialGradient(listOf(Color(0x149C7424), Color.Transparent), center = Offset(w * 0.1f, h * 0.85f), radius = w * 0.8f),
            radius = w * 0.8f, center = Offset(w * 0.1f, h * 0.85f))
        for (g in glints) {
            val a = (0.5f + 0.5f * sin(t * 2f * PI.toFloat() * g.speed + g.phase)).let { it * it }
            val c = Offset(g.x * w, g.y * h)
            val r = g.r.dp.toPx()
            drawCircle(C.GoldLight.copy(alpha = 0.12f + 0.55f * a), r, c)
            if (g.star && a > 0.55f) sparkle(c, r * 5f * a, C.GoldLight.copy(alpha = a * 0.8f))
        }
    }
}

/** Four-pointed glint. */
fun androidx.compose.ui.graphics.drawscope.DrawScope.sparkle(c: Offset, r: Float, color: Color) {
    val p = Path().apply {
        moveTo(c.x, c.y - r); quadraticTo(c.x, c.y, c.x + r, c.y); quadraticTo(c.x, c.y, c.x, c.y + r)
        quadraticTo(c.x, c.y, c.x - r, c.y); quadraticTo(c.x, c.y, c.x, c.y - r); close()
    }
    drawPath(p, color)
}

fun Modifier.glass(shape: Shape = RoundedCornerShape(24.dp), strong: Boolean = false): Modifier =
    this.clip(shape)
        .background(if (strong) Brush.verticalGradient(listOf(Color(0x22FFF4DC), Color(0x0CFFF4DC))) else C.GlassFill)
        .border(1.dp, C.GlassEdge, shape)

@Composable
fun Modifier.enter(index: Int = 0): Modifier {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(55L * index); p.animateTo(1f, tween(460, easing = FastOutSlowInEasing)) }
    return this.graphicsLayer { alpha = p.value; translationY = (1f - p.value) * 24.dp.toPx() }
}

@Composable
fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.96f else 1f, tween(110), label = "press")
    return this.scale(s).clickable(source, indication = null, enabled = enabled, onClick = onClick)
}

@Composable
fun GoldText(text: String, size: TextUnit, modifier: Modifier = Modifier, weight: FontWeight = FontWeight.ExtraBold) {
    Text(text, modifier = modifier, style = TextStyle(brush = C.Title, fontSize = size, fontWeight = weight, fontFamily = Rubik))
}

@Composable
fun Section(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().glass().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (title != null) Text(title, color = C.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        content()
    }
}

/** The main action: liquid gold with a light sweep and twinkles on both sides. */
@Composable
fun GoldButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "sweep")
    val sweep by inf.animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(2600, delayMillis = 700, easing = LinearEasing)), label = "x")
    val tw by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "tw")
    Box(
        modifier.height(60.dp)
            .shadow(if (enabled) 22.dp else 0.dp, CircleShape, ambientColor = C.Gold, spotColor = C.Gold)
            .clip(CircleShape).background(C.GoldButton)
            .drawWithContent {
                drawContent()
                if (enabled) {
                    val x = size.width * sweep
                    drawRect(Brush.linearGradient(listOf(Color.Transparent, Color(0x88FFFFFF), Color.Transparent),
                        start = Offset(x - 110f, 0f), end = Offset(x + 110f, size.height)))
                    val a = (sin(tw * 2 * PI.toFloat()) * 0.5f + 0.5f)
                    sparkle(Offset(size.width * 0.12f, size.height * 0.3f), 7.dp.toPx() * (0.4f + 0.6f * a), Color.White.copy(alpha = 0.85f * a))
                    sparkle(Offset(size.width * 0.88f, size.height * 0.68f), 6.dp.toPx() * (1f - 0.6f * a), Color.White.copy(alpha = 0.85f * (1f - a)))
                }
            }
            .alpha(if (enabled) 1f else 0.4f)
            .pressable(enabled, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 24.dp)) {
            if (icon != null) { Icon(icon, null, tint = Color(0xFF1A1307), modifier = Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)) }
            Text(text, color = Color(0xFF1A1307), fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, maxLines = 1)
        }
    }
}

@Composable
fun SoftButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true,
               color: Color = C.Text, onClick: () -> Unit) {
    Row(
        modifier.height(48.dp).glass(CircleShape, strong = true).alpha(if (enabled) 1f else 0.4f)
            .pressable(enabled, onClick).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = color, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, color = color, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
fun CircleIcon(icon: ImageVector, label: String, size: Dp = 44.dp, tint: Color = C.Text, gold: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(size).then(if (gold) Modifier.clip(CircleShape).background(C.GoldButton) else Modifier.glass(CircleShape))
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (gold) Color(0xFF1A1307) else tint, modifier = Modifier.size(size * 0.5f)) }
}

/** Thin gold progress line with a moving highlight. */
@Composable
fun GoldProgress(value: Float, modifier: Modifier = Modifier) {
    val v by animateFloatAsState(value.coerceIn(0f, 1f), tween(300), label = "p")
    Box(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Color(0x1FFFF4DC))) {
        Box(Modifier.fillMaxWidth(v).height(6.dp).clip(CircleShape).background(C.GoldButton))
    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1e6)
    else -> "%.0f KB".format(bytes / 1e3)
}

fun formatSeconds(s: Float): String {
    val t = s.toInt()
    return if (t >= 60) "%d:%02d".format(t / 60, t % 60) else "%.1f שנ׳".format(s)
}
