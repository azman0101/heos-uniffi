package com.example.heosdemo

import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private const val FULL_SCALE = 100
private const val FINE_SPAN = 20 // en mode fin, toute la largeur couvre 20 crans

/**
 * Curseur de volume 0..100 par crans entiers, avec un « mode fin » où la largeur
 * ne couvre que [FINE_SPAN] crans autour de la valeur.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FineSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    fine: Boolean,
    modifier: Modifier = Modifier,
) {
    // Centre de la fenêtre fine : recalé quand on relâche le doigt ou qu'on active le mode.
    var anchor by remember(fine) { mutableIntStateOf(value) }
    val low = if (fine) (anchor - FINE_SPAN / 2).coerceIn(0, FULL_SCALE - FINE_SPAN) else 0
    val high = if (fine) low + FINE_SPAN else FULL_SCALE
    val range = low.toFloat()..high.toFloat()

    Slider(
        value = value.toFloat().coerceIn(range),
        onValueChange = { onValueChange(it.toInt()) },
        onValueChangeFinished = { anchor = value },
        valueRange = range,
        steps = high - low - 1,
        modifier = modifier.fillMaxWidth().height(56.dp),
        track = { state ->
            // Sans points : 100 crans feraient une ligne de pointillés.
            SliderDefaults.Track(
                sliderState = state,
                modifier = Modifier.height(8.dp),
                drawStopIndicator = null,
                drawTick = { _, _ -> },
            )
        },
    )
}

@Composable
fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(48.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge)
    }
}
