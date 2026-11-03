package io.baato.trackme.sample

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** What the locate button shows. */
enum class LocateState {
    /** Location allowed and a fix is in: tap flies to you. */
    Located,
    /** Allowed, but no fix yet (indoors, GPS warming up). */
    Searching,
    /** No permission, or location switched off: tap asks / explains. */
    Disabled,
}

/**
 * Round "show me" button: Material's `near_me` arrow on a blue gradient when your position is known, on gray
 * (gently pulsing) while there's no fix yet, and `near_me_disabled` when location isn't available to the app.
 */
@Composable
fun LocateButton(state: LocateState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val background = when (state) {
        LocateState.Located -> Brush.linearGradient(listOf(Color(0xFF1E88E5), Color(0xFF00ACC1)))
        LocateState.Searching -> Brush.linearGradient(listOf(Color(0xFF9E9E9E), Color(0xFF757575)))
        LocateState.Disabled -> SolidColor(Color.White)
    }
    val tint = if (state == LocateState.Disabled) Color(0xFFD32F2F) else Color.White
    val pulse = if (state == LocateState.Searching) {
        val t = rememberInfiniteTransition(label = "locate")
        t.animateFloat(1f, 0.45f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse").value
    } else 1f

    Box(
        modifier
            .size(52.dp)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = when (state) {
                    LocateState.Located -> "Show my location"
                    LocateState.Searching -> "Looking for your location"
                    LocateState.Disabled -> "Location unavailable"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (state == LocateState.Disabled) NearMeDisabled else NearMe,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(26.dp).alpha(pulse),
        )
    }
}

/** Material `near_me` (24 × 24): the navigation arrow. */
val NearMe: ImageVector by lazy {
    ImageVector.Builder("NearMe", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(21f, 3f)
            lineTo(3f, 10.53f)
            verticalLineToRelative(0.98f)
            lineToRelative(6.84f, 2.65f)
            lineTo(12.48f, 21f)
            horizontalLineToRelative(0.98f)
            close()
        }
    }.build()
}

/** `near_me_disabled`: the same arrow, struck through. */
val NearMeDisabled: ImageVector by lazy {
    ImageVector.Builder("NearMeDisabled", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(21f, 3f)
            lineTo(3f, 10.53f)
            verticalLineToRelative(0.98f)
            lineToRelative(6.84f, 2.65f)
            lineTo(12.48f, 21f)
            horizontalLineToRelative(0.98f)
            close()
        }
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round) {
            moveTo(3.5f, 3.5f)
            lineTo(20.5f, 20.5f)
        }
    }.build()
}
