package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.delay

private const val VOLUME_INDICATOR_TIMEOUT_MS = 1_500L

/**
 * Handles the remote's volume keys inside the player so the system volume bar (which covers the
 * subtitles at the bottom) is not shown; [PlayerVolumeIndicator] is drawn at the top instead.
 *
 * Devices whose volume is controlled by the TV / soundbar (fixed volume, HDMI-CEC) are left
 * untouched: their keys keep their normal behaviour.
 */
class PlayerVolumeController(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    var level by mutableIntStateOf(0)
        private set
    var maxLevel by mutableIntStateOf(1)
        private set
    var muted by mutableStateOf(false)
        private set
    /** Bumped on every change so the indicator restarts its hide timer. */
    var revision by mutableIntStateOf(0)
        private set

    /** Returns true when the key was handled (and must be consumed). */
    fun handleKey(event: KeyEvent): Boolean {
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> AudioManager.ADJUST_RAISE
            KeyEvent.KEYCODE_VOLUME_DOWN -> AudioManager.ADJUST_LOWER
            KeyEvent.KEYCODE_VOLUME_MUTE -> AudioManager.ADJUST_TOGGLE_MUTE
            else -> return false
        }
        val manager = audioManager ?: return false
        if (manager.isVolumeFixed) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            val adjust = if (direction == AudioManager.ADJUST_RAISE && manager.isStreamMute(AudioManager.STREAM_MUSIC)) {
                AudioManager.ADJUST_UNMUTE
            } else {
                direction
            }
            runCatching {
                // Flags = 0: no system volume UI.
                manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, adjust, 0)
            }
            level = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
            maxLevel = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            muted = manager.isStreamMute(AudioManager.STREAM_MUSIC) || level == 0
            revision++
        }
        return true
    }
}

/** Compact volume pill shown at the top of the player, away from the subtitles. */
@Composable
fun PlayerVolumeIndicator(controller: PlayerVolumeController, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(controller.revision) {
        if (controller.revision == 0) return@LaunchedEffect
        visible = true
        delay(VOLUME_INDICATOR_TIMEOUT_MS)
        visible = false
    }
    val fraction by animateFloatAsState(
        targetValue = if (controller.muted) 0f else controller.level.toFloat() / controller.maxLevel,
        label = "volumeFraction"
    )
    val accent = NuvioColors.Secondary

    AnimatedVisibility(
        visible = visible && controller.revision > 0,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(top = 28.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 18.dp, vertical = 10.dp)
        ) {
            Icon(
                imageVector = when {
                    controller.muted -> Icons.AutoMirrored.Filled.VolumeOff
                    fraction < 0.5f -> Icons.AutoMirrored.Filled.VolumeDown
                    else -> Icons.AutoMirrored.Filled.VolumeUp
                },
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 14.dp)
                    .width(220.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.22f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .clip(RoundedCornerShape(50))
                        .background(accent)
                )
            }
            Text(
                text = if (controller.muted) "0" else "${(fraction * 100).toInt()}",
                color = Color.White,
                fontSize = 15.sp,
                modifier = Modifier.width(34.dp)
            )
        }
    }
}
