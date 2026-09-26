package dev.dertyp.synara.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.dertyp.data.LikeLevel
import dev.dertyp.data.UserSong
import dev.dertyp.synara.ui.SynaraIcons
import org.jetbrains.compose.resources.stringResource
import synara.synara.generated.resources.*

val UserSong.effectiveLikeLevel: LikeLevel
    get() = likeLevel ?: if (isFavourite == true) LikeLevel.LIKE else LikeLevel.NONE

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LikeButton(
    likeLevel: LikeLevel,
    onClick: () -> Unit,
    onSuperClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
    neutralTint: Color = LocalContentColor.current,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnSuperClick by rememberUpdatedState(onSuperClick)
    val favoriteLabel = stringResource(Res.string.favorite)

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(stringResource(Res.string.super_like_hint)) } },
        state = rememberTooltipState(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .size(40.dp)
                .clip(CircleShape)
                .hoverable(interactionSource)
                .indication(interactionSource, ripple())
                .onClick(
                    matcher = PointerMatcher.mouse(PointerButton.Secondary),
                    onClick = { currentOnSuperClick() }
                )
                .pointerInput(interactionSource) {
                    detectTapGestures(
                        onPress = { offset ->
                            val press = PressInteraction.Press(offset)
                            interactionSource.emit(press)
                            if (tryAwaitRelease()) {
                                interactionSource.emit(PressInteraction.Release(press))
                            } else {
                                interactionSource.emit(PressInteraction.Cancel(press))
                            }
                        },
                        onTap = { currentOnClick() },
                        onLongPress = { currentOnSuperClick() },
                    )
                }
                .semantics {
                    role = Role.Button
                    onClick { currentOnClick(); true }
                    onLongClick { currentOnSuperClick(); true }
                },
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = likeLevel,
                transitionSpec = {
                    if (targetState == LikeLevel.SUPER) {
                        (fadeIn() + scaleIn(
                            initialScale = 0.4f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        )) togetherWith fadeOut()
                    } else {
                        fadeIn() togetherWith fadeOut()
                    }
                },
                label = "likeLevelTransition"
            ) { level ->
                when (level) {
                    LikeLevel.NONE -> Icon(
                        SynaraIcons.IsNotFavorite.get(),
                        contentDescription = favoriteLabel,
                        modifier = Modifier.size(iconSize),
                        tint = neutralTint
                    )

                    LikeLevel.LIKE -> Icon(
                        SynaraIcons.IsFavorite.get(),
                        contentDescription = favoriteLabel,
                        modifier = Modifier.size(iconSize),
                        tint = MaterialTheme.colorScheme.primary
                    )

                    LikeLevel.SUPER -> {
                        val glowColor = MaterialTheme.colorScheme.tertiary
                        val transition = rememberInfiniteTransition(label = "superLikeGlow")
                        val glowAlpha by transition.animateFloat(
                            initialValue = 0.5f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "superLikeGlowAlpha"
                        )
                        Icon(
                            SynaraIcons.SuperLiked.get(),
                            contentDescription = stringResource(Res.string.super_like),
                            modifier = Modifier
                                .size(iconSize)
                                .drawBehind {
                                    val radius = size.minDimension * 0.85f
                                    drawCircle(
                                        brush = Brush.radialGradient(
                                            colors = listOf(glowColor.copy(alpha = 0.35f), Color.Transparent),
                                            center = Offset(size.width / 2f, size.height / 2f),
                                            radius = radius
                                        ),
                                        radius = radius,
                                        alpha = glowAlpha
                                    )
                                },
                            tint = glowColor
                        )
                    }
                }
            }
        }
    }
}
