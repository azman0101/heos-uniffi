package com.example.heosdemo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import uniffi.heos_core.HeosSpeaker
import uniffi.heos_core.SpeakerLevel
import uniffi.heos_core.SpeakerRole

private const val MIN_DB = -12
private const val MAX_DB = 12

@Composable
fun SpeakersScreen(
    speakers: List<HeosSpeaker>,
    levels: List<SpeakerLevel>,
    error: String?,
    onVolume: (pid: Long, level: Int) -> Unit,
    onToggleMute: (pid: Long) -> Unit,
    onLevel: (role: SpeakerRole, db: Int) -> Unit,
    onRefreshLevels: () -> Unit,
    onDisconnect: () -> Unit,
    onDismissError: () -> Unit,
) {
    // `connect` garantit la présence de la barre ; les arrières et le caisson n'ont pas de pid.
    val bar = speakers.firstOrNull { it.model.contains("bar", ignoreCase = true) } ?: return
    val snackbar = remember { SnackbarHostState() }

    // L'app HEOS a pu changer les niveaux pendant qu'on était en arrière-plan.
    LifecycleResumeEffect(Unit) {
        onRefreshLevels()
        onPauseOrDispose { }
    }
    LaunchedEffect(error) {
        if (error != null) {
            snackbar.showSnackbar(error)
            onDismissError()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(bar.name, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onDisconnect) { Text("Changer d'IP") }
            }
            if (landscape) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    VolumeSection(bar, onVolume, onToggleMute, Modifier.weight(1f))
                    LevelsSection(levels, onLevel, Modifier.weight(1f))
                }
            } else {
                VolumeSection(bar, onVolume, onToggleMute)
                Spacer(Modifier.height(32.dp))
                LevelsSection(levels, onLevel, Modifier.weight(1f))
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun VolumeSection(
    bar: HeosSpeaker,
    onVolume: (pid: Long, level: Int) -> Unit,
    onToggleMute: (pid: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var fine by rememberSaveable { mutableStateOf(false) }
    val volume = bar.volume.toInt()

    Column(modifier) {
        Text(
            "$volume",
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = 112.sp,
                lineHeight = 120.sp,
                fontWeight = FontWeight.Light,
            ),
            color = if (bar.isMuted) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        FineSlider(volume, { onVolume(bar.pid, it) }, fine)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepButton("−", enabled = volume > 0) { onVolume(bar.pid, volume - 1) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(fine, onClick = { fine = !fine }, label = { Text("Fin") })
                FilterChip(bar.isMuted, onClick = { onToggleMute(bar.pid) }, label = { Text("Muet") })
            }
            StepButton("+", enabled = volume < 100) { onVolume(bar.pid, volume + 1) }
        }
    }
}

@Composable
private fun LevelsSection(
    levels: List<SpeakerLevel>,
    onLevel: (role: SpeakerRole, db: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(bottom = 16.dp)) {
        Text("Niveaux (dB)", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            levels.forEach { level ->
                VerticalFader(
                    value = level.db.toInt(),
                    onValueChange = { onLevel(level.role, it) },
                    label = level.role.label(),
                    min = MIN_DB,
                    max = MAX_DB,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun SpeakerRole.label() = when (this) {
    SpeakerRole.MAIN -> "Barre"
    SpeakerRole.REAR_LEFT -> "Surround G"
    SpeakerRole.REAR_RIGHT -> "Surround D"
    SpeakerRole.SUBWOOFER -> "Caisson"
}
