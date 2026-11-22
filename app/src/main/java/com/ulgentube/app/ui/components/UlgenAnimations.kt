package com.ulgentube.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import com.ulgentube.app.ui.theme.CoralPink
import com.ulgentube.app.ui.theme.ElectricPurple

/**
 * Tıklanabilir kart, çip ve butonlara yaylı (spring) basma küçülmesi efekti ekler.
 */
fun Modifier.bounceClick(
    pressedScale: Float = 0.96f
): Modifier = composed {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "bounce_scale"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(Unit) {
            while (true) {
                awaitPointerEventScope {
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    waitForUpOrCancellation()
                    isPressed = false
                }
            }
        }
}

/**
 * Yaylı ölçekleme, yumuşak renk geçişi, iç ikon animasyonu ve dokunsal geri bildirimli
 * özel Material 3 Switch bileşeni.
 */
@Composable
fun UlgenSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val haptic = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        targetValue = if (checked) 1.04f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "switch_scale"
    )
    val trackColor by animateColorAsState(
        targetValue = if (checked) ElectricPurple else MaterialTheme.colorScheme.surfaceContainerHighest,
        animationSpec = tween(220),
        label = "switch_track_color"
    )
    val thumbColor by animateColorAsState(
        targetValue = if (checked) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(220),
        label = "switch_thumb_color"
    )

    Switch(
        checked = checked,
        onCheckedChange = { newValue ->
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onCheckedChange(newValue)
        },
        enabled = enabled,
        modifier = modifier
            .bounceClick(pressedScale = 0.92f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        thumbContent = {
            Icon(
                imageVector = if (checked) Icons.Rounded.Check else Icons.Rounded.Close,
                contentDescription = null,
                modifier = Modifier.size(SwitchDefaults.IconSize),
                tint = if (checked) CoralPink else MaterialTheme.colorScheme.surfaceContainerHighest
            )
        },
        colors = SwitchDefaults.colors(
            checkedThumbColor = thumbColor,
            checkedTrackColor = trackColor,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = thumbColor,
            uncheckedTrackColor = trackColor,
            uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    )
}
