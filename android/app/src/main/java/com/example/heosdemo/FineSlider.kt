package com.example.heosdemo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private const val FINE_SPAN = 20 // en mode fin, toute la largeur couvre 20 crans

/**
 * Curseur d'entiers (0..100 par défaut) pensé pour l'ajustement précis :
 * piste épaisse sur toute la largeur, crans entiers, boutons −/+ (±1) et
 * « mode fin » où la largeur du curseur ne couvre que [FINE_SPAN] crans autour de la valeur.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FineSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    fine: Boolean,
    modifier: Modifier = Modifier,
    min: Int = 0,
    max: Int = 100,
) {
    // Centre de la fenêtre fine : recalé quand on relâche le doigt ou qu'on active le mode.
    var anchor by remember(fine) { mutableIntStateOf(value) }
    // Le mode fin n'a de sens que si l'étendue dépasse la fenêtre.
    val windowed = fine && max - min > FINE_SPAN
    val low = (anchor - FINE_SPAN / 2).coerceIn(min, max - FINE_SPAN)
    val range = if (windowed) low.toFloat()..(low + FINE_SPAN).toFloat() else min.toFloat()..max.toFloat()
    val steps = (range.endInclusive - range.start).toInt() - 1

    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StepButton("−", enabled = value > min) { onValueChange(value - 1) }
        Slider(
            value = value.toFloat().coerceIn(range),
            onValueChange = { onValueChange(it.toInt()) },
            onValueChangeFinished = { anchor = value },
            valueRange = range,
            steps = steps,
            modifier = Modifier.weight(1f).height(56.dp),
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    thumbSize = androidx.compose.ui.unit.DpSize(8.dp, 40.dp),
                )
            },
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    modifier = Modifier.height(20.dp),
                    drawStopIndicator = null,
                    drawTick = { _, _ -> },
                )
            },
        )
        StepButton("+", enabled = value < max) { onValueChange(value + 1) }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(44.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge)
    }
}
