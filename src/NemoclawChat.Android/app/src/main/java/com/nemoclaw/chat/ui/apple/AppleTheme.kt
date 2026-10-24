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
