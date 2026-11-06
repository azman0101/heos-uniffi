package com.example.heosdemo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private val HandleWidth = 36.dp
private val HandleHeight = 6.dp
private val Inset = 12.dp

/**
 * Fader d'entiers centré sur 0, à la manière d'une table de mixage. Le glissement est relatif :
 * poser le doigt ne change rien. Un appui au-dessus ou en dessous de la poignée déplace d'un cran.
 */
@Composable
fun VerticalFader(
    value: Int,
    onValueChange: (Int) -> Unit,
    label: String,
    min: Int,
    max: Int,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)
    val haptics = LocalHapticFeedback.current
    val set = { v: Int ->
        val next = v.coerceIn(min, max)
        if (next != current) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            change(next)
        }
    }
    val text = "%+d".format(value).replace('-', '−').replace("+0", "0")
    val track = MaterialTheme.colorScheme.outlineVariant
    val tick = MaterialTheme.colorScheme.outline
    val active = MaterialTheme.colorScheme.primary

    Column(modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal))
        Spacer(Modifier.height(4.dp))
        Canvas(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .semantics {
                    contentDescription = label
                    stateDescription = "$text dB"
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        value.toFloat(), min.toFloat()..max.toFloat(), max - min - 1,
                    )
                    setProgress { set(it.roundToInt()); true }
                }
                .pointerInput(min, max) {
                    val inset = Inset.toPx()
                    val step = (size.height - 2 * inset) / (max - min)
                    detectTapGestures { pos ->
                        val handle = inset + (max - current) * step
                        set(current + if (pos.y < handle) 1 else -1)
                    }
                }
                .pointerInput(min, max) {
                    val step = (size.height - 2 * Inset.toPx()) / (max - min)
                    var start = 0
                    var moved = 0f
                    detectVerticalDragGestures(onDragStart = { start = current; moved = 0f }) { _, dy ->
                        moved -= dy
                        set(start + (moved / step).roundToInt())
                    }
                },
        ) {
            val inset = Inset.toPx()
            val cx = size.width / 2
            val y = { v: Int -> inset + (max - v) * (size.height - 2 * inset) / (max - min) }

            drawLine(track, Offset(cx, inset), Offset(cx, size.height - inset), 2.dp.toPx())
            for (v in listOf(max, max / 2, 0, min / 2, min)) {
                val half = (if (v == 0) 14.dp else 7.dp).toPx()
                drawLine(tick, Offset(cx - half, y(v)), Offset(cx + half, y(v)), 1.dp.toPx())
            }
            drawLine(active, Offset(cx, y(0)), Offset(cx, y(value)), 4.dp.toPx())
            val handle = Size(HandleWidth.toPx(), HandleHeight.toPx())
            drawRoundRect(
                active,
                topLeft = Offset(cx - handle.width / 2, y(value) - handle.height / 2),
                size = handle,
                cornerRadius = CornerRadius(handle.height / 2),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
