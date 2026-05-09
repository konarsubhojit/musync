package com.musync.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A subtle horizontally-translating shimmer used to indicate that a piece
 * of UI is loading.  Apply to any composable to overlay a moving sheen on
 * top of its current content (#52).
 *
 * The shimmer uses [BlendMode.SrcAtop] so it only paints over pixels that
 * the underlying content already drew — meaning callers can place the
 * modifier on a `Box` filled with a base placeholder colour to get a
 * neat skeleton block, or on text/icons to add a "loading" sheen without
 * changing layout.
 *
 * The animation is suspended automatically when the composable leaves
 * composition because [rememberInfiniteTransition] is lifecycle-scoped.
 *
 * @param shimmerColor base color of the moving highlight; defaults to
 *  the surface variant so the effect is visible in both light and dark
 *  themes.
 */
fun Modifier.shimmer(shimmerColor: Color? = null): Modifier =
    composed {
        val color = shimmerColor ?: MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
        val transition = rememberInfiniteTransition(label = "shimmer")
        val translate by transition.animateFloat(
            initialValue = -2f,
            targetValue = 2f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 1_400, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "shimmer-translate",
        )
        drawWithContent {
            drawContent()
            val width = size.width
            val highlight = width * 0.4f
            val start = translate * width
            val brush =
                Brush.linearGradient(
                    colors =
                        listOf(
                            Color.Transparent,
                            color,
                            Color.Transparent,
                        ),
                    start = Offset(start, 0f),
                    end = Offset(start + highlight, size.height),
                )
            drawRect(brush = brush, blendMode = BlendMode.SrcAtop)
        }
    }

/**
 * Pre-built skeleton block: a rounded, surface-variant-tinted placeholder
 * that pulses with [shimmer].  Useful for "we're still loading text"
 * placements like the recent-room status row or the create-room metadata
 * row (#52).
 *
 * Provide a non-null [contentDescription] for accessibility so TalkBack
 * announces what is loading; pass `null` to mark as decorative when an
 * adjacent element already conveys the state.
 */
@Composable
fun SkeletonLine(
    modifier: Modifier = Modifier,
    height: Dp = 12.dp,
    contentDescription: String? = null,
) {
    val baseColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    Box(
        modifier =
            modifier
                .testTag("skeleton-line")
                .height(height)
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(baseColor)
                .shimmer()
                .then(
                    if (contentDescription != null) {
                        Modifier.semantics { this.contentDescription = contentDescription }
                    } else {
                        Modifier
                    },
                ),
    )
}

/** Square / circular skeleton block for image placeholders. */
@Composable
fun SkeletonBlock(
    size: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
) {
    val baseColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    Box(
        modifier =
            modifier
                .size(size)
                .clip(RoundedCornerShape(cornerRadius))
                .background(baseColor)
                .shimmer(),
    )
}
