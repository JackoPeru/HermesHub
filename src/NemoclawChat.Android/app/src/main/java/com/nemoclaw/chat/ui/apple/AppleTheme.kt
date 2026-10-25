package com.nemoclaw.chat.ui.apple

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nemoclaw.chat.AppColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sistema visivo Apple condiviso da tutta l'app (riferimento: home iOS
 * scura premium). Solo stile: niente testi nuovi qui dentro (i testi
 * restano nei chiamanti), niente dipendenze oltre Compose Material3,
 * niente API sopra minSdk (niente blur/RenderEffect: vetro = gradienti).
 */
object AppleType {
    val HeroTitle = 30.sp
    val HeroSubtitle = 15.sp
    val ScreenTitle = 20.sp
    val RowTitle = 15.sp
    val RowTitleSmall = 13.sp
    val Footnote = 12.sp
    val Caption = 11.sp
    val Eyebrow = 10.sp
}

object AppleMotion {
    fun <T> tweened(durationMillis: Int = 240): androidx.compose.animation.core.FiniteAnimationSpec<T> =
        tween(durationMillis, easing = androidx.compose.animation.core.FastOutSlowInEasing)

    fun <T> springy(): androidx.compose.animation.core.FiniteAnimationSpec<T> =
        spring(stiffness = Spring.StiffnessMediumLow)

    fun enter(): EnterTransition =
        fadeIn(tween(220)) + slideInVertically(tween(240)) { it / 8 }

    fun exit(): ExitTransition =
        fadeOut(tween(180)) + slideOutVertically(tween(200)) { it / 10 }
}

/** Sfondo aurora: curve ambrate morbide su nero. Statico (costo zero a regime). */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, intensity: Float = 1f) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        // Alone caldo in alto a destra.
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to Color(0xFFF5A524).copy(alpha = 0.10f * intensity),
                0.55f to Color(0xFFF5A524).copy(alpha = 0.03f * intensity),
                1.0f to Color.Transparent,
                center = Offset(w * 0.92f, -h * 0.06f),
                radius = w * 0.85f
            ),
            radius = w * 0.85f,
            center = Offset(w * 0.92f, -h * 0.06f)
        )
        // Arco luce che attraversa in alto.
        drawArc(
            color = Color(0xFFF5A524).copy(alpha = 0.16f * intensity),
            startAngle = 200f,
            sweepAngle = 70f,
            useCenter = false,
            topLeft = Offset(-w * 0.55f, -h * 0.42f),
            size = androidx.compose.ui.geometry.Size(w * 1.5f, h * 0.75f),
            style = Stroke(width = h * 0.012f)
        )
        // Arco basso di rientro.
        drawArc(
            color = Color.White.copy(alpha = 0.05f * intensity),
            startAngle = 290f,
            sweepAngle = 80f,
            useCenter = false,
            topLeft = Offset(-w * 0.35f, h * 0.52f),
            size = androidx.compose.ui.geometry.Size(w * 1.7f, h * 0.8f),
            style = Stroke(width = h * 0.02f)
        )
    }
}

/**
 * Bolle del riferimento: due sfere scure con bordo oro rim-light e alone
 * volumetrico, in deriva lenta con respiro della luce. Ogni elemento di luce
 * deriva dagli stessi centri animati, quindi la luce segue le bolle. Solo
 * Canvas e gradienti: niente PNG, niente blur/RenderEffect (minSdk 26).
 * Solo stile.
 */
@Composable
fun BubbleBackground(modifier: Modifier = Modifier) {
    val drift = rememberInfiniteTransition(label = "bubbleDrift")
    val topPhase by drift.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(16000, easing = LinearEasing), RepeatMode.Restart),
        label = "topPhase"
    )
    val bottomPhase by drift.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(21000, easing = LinearEasing), RepeatMode.Restart),
        label = "bottomPhase"
    )
    val glowPhase by drift.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "glowPhase"
    )
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val breathe = 0.88f + 0.12f * sin(glowPhase)
        val amber = Color(0xFFF5A524)
        val rimLight = Color(0xFFFFD694)
        val body = Color(0xFF0E0E11)

        // --- Bolla alta: il bordo entra in alto a 28% ed esce a destra a 24% ---
        // Curva quadratica in frazioni: identica su ogni aspect (niente cerchi).
        val dxTop = 0.025f * sin(topPhase)
        val dyTop = 0.012f * cos(topPhase * 0.8f)
        val topCx = w * (0.30f + dxTop)
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to body,
                0.8f to body.copy(alpha = 0.6f),
                1.0f to Color.Transparent,
                center = Offset(topCx, -h * 0.12f),
                radius = w * 0.95f
            ),
            radius = w * 0.95f,
            center = Offset(topCx, -h * 0.12f)
        )
        // volume appena sopra il bordo
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to Color.White.copy(alpha = 0.009f * breathe),
                1.0f to Color.Transparent,
                center = Offset(w * (0.55f + dxTop), h * 0.10f),
                radius = w * 0.45f
            ),
            radius = w * 0.45f,
            center = Offset(w * (0.55f + dxTop), h * 0.10f)
        )
        val topPath = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * (0.28f + dxTop), -4f)
            quadraticBezierTo(
                w * (0.76f + dxTop), h * 0.18f,
                w + 4f, h * (0.24f + dyTop)
            )
        }
        // alone a strati (finto blur), poi anima luminosa
        drawPath(path = topPath, color = amber.copy(alpha = 0.03f * breathe), style = Stroke(width = h * 0.008f))
        drawPath(path = topPath, color = amber.copy(alpha = 0.08f * breathe), style = Stroke(width = h * 0.0035f))
        drawPath(path = topPath, color = amber.copy(alpha = 0.25f * breathe), style = Stroke(width = h * 0.0016f))
        drawPath(path = topPath, color = rimLight.copy(alpha = 0.85f * breathe), style = Stroke(width = (1.6.dp).toPx()))
        // bacio di luce dove il bordo entra in alto
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to amber.copy(alpha = 0.06f * breathe),
                1.0f to Color.Transparent,
                center = Offset(w * (0.28f + dxTop), 0f),
                radius = w * 0.24f
            ),
            radius = w * 0.24f,
            center = Offset(w * (0.28f + dxTop), 0f)
        )

        // --- Bolla bassa: il bordo entra a sx a 62% ed esce a dx a 52% ---
        val dxBottom = 0.022f * sin(bottomPhase + 1.3f)
        val dyBottom = 0.010f * cos(bottomPhase * 0.7f)
        val botCx = w * (0.45f + dxBottom)
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to body,
                0.8f to body.copy(alpha = 0.6f),
                1.0f to Color.Transparent,
                center = Offset(botCx, h * 1.05f),
                radius = w * 0.95f
            ),
            radius = w * 0.95f,
            center = Offset(botCx, h * 1.05f)
        )
        // luce diffusa sopra il bordo, segue la curva
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to amber.copy(alpha = 0.020f * breathe),
                1.0f to Color.Transparent,
                center = Offset(w * (0.55f + dxBottom), h * 0.60f),
                radius = w * 0.45f
            ),
            radius = w * 0.45f,
            center = Offset(w * (0.55f + dxBottom), h * 0.60f)
        )
        val botPath = androidx.compose.ui.graphics.Path().apply {
            moveTo(-4f, h * (0.616f + dyBottom))
            quadraticBezierTo(
                w * (0.20f + dxBottom), h * 0.71f,
                w + 4f, h * (0.525f + dyBottom)
            )
        }
        drawPath(path = botPath, color = amber.copy(alpha = 0.04f * breathe), style = Stroke(width = h * 0.008f))
        drawPath(path = botPath, color = amber.copy(alpha = 0.10f * breathe), style = Stroke(width = h * 0.0035f))
        drawPath(path = botPath, color = amber.copy(alpha = 0.30f * breathe), style = Stroke(width = h * 0.0016f))
        drawPath(path = botPath, color = rimLight.copy(alpha = 0.90f * breathe), style = Stroke(width = (1.6.dp).toPx()))
        // alone caldo dove il bordo esce a destra
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to amber.copy(alpha = 0.07f * breathe),
                1.0f to Color.Transparent,
                center = Offset(w * (0.97f + dxBottom), h * 0.53f),
                radius = w * 0.35f
            ),
            radius = w * 0.35f,
            center = Offset(w * (0.97f + dxBottom), h * 0.53f)
        )
    }
}

/** Contenitore vetro: gradiente traslucido + hairline, senza blur nativo. */
@Composable
fun AppleGlass(
    modifier: Modifier = Modifier,
    shape: androidx.compose.foundation.shape.CornerBasedShape = RoundedCornerShape(28.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val base = Modifier
        .clip(shape)
        .background(
            Brush.verticalGradient(
                0.0f to Color.White.copy(alpha = 0.10f),
                0.5f to Color.White.copy(alpha = 0.05f),
                1.0f to Color.White.copy(alpha = 0.07f)
            )
        )
        .border(BorderStroke(0.5.dp, Color.White.copy(alpha = 0.14f)), shape)
        .then(modifier)
    if (onClick != null) {
        Box(modifier = base.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )) { content() }
    } else {
        Box(modifier = base) { content() }
    }
}

/** Avatar circolare con anello ambra (profilo). */
@Composable
fun AvatarRing(
    initial: String,
    onClick: () -> Unit,
    size: Dp = 40.dp,
    contentDescription: String? = null
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFF1A1D23))
            .border(BorderStroke(1.5.dp, AppColors.Accent.copy(alpha = 0.75f)), CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .then(
                if (contentDescription != null) {
                    Modifier.semantics(mergeDescendants = true) {
                        this.contentDescription = contentDescription
                    }
                } else Modifier
            )
    ) {
        Text(
            text = initial.take(1).uppercase(),
            color = Color.White,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** Riga stato server: puntino + testo grigio (come il riferimento). */
@Composable
fun ServerStatusLine(text: String, online: Boolean, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (online) AppColors.Success else AppColors.Error)
        )
        Text(
            text = text,
            color = AppColors.Muted,
            fontSize = AppleType.Footnote,
            maxLines = 1,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}
