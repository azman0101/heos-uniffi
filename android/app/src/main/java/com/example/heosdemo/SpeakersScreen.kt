package com.example.heosdemo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import uniffi.heos_core.HeosSpeaker
import uniffi.heos_core.SpeakerLevel
import uniffi.heos_core.SpeakerRole

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
    var fine by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("HEOS", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onDisconnect) { Text("Déconnecter") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Réglage fin", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Le curseur ne couvre que ±10 crans autour du volume actuel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = fine, onCheckedChange = { fine = it })
        }
        if (error != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismissError) { Text("OK") }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(speakers, key = { "pid${it.pid}" }) { speaker ->
                SpeakerCard(speaker, fine, onVolume, onToggleMute)
            }
            item(key = "levels-header") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Niveaux par enceinte", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Équilibrage de −12 à +12 dB (comme l'écran « Niveaux » de l'app HEOS)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onRefreshLevels) { Text("Actualiser") }
                }
            }
            items(levels, key = { it.role }) { level -> LevelCard(level, onLevel) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SpeakerCard(
    speaker: HeosSpeaker,
    fine: Boolean,
    onVolume: (pid: Long, level: Int) -> Unit,
    onToggleMute: (pid: Long) -> Unit,
) {
    Card(Modifier.fillMaxWidth(), colors = cardColors()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Volume général", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${speaker.name} · ${speaker.model}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (speaker.isMuted) {
                    FilledTonalButton(onClick = { onToggleMute(speaker.pid) }) { Text("Muet") }
                } else {
                    OutlinedButton(onClick = { onToggleMute(speaker.pid) }) { Text("Mute") }
                }
            }
            LevelRow(speaker.volume.toInt(), fine) { onVolume(speaker.pid, it) }
        }
    }
}

@Composable
private fun LevelCard(level: SpeakerLevel, onLevel: (role: SpeakerRole, db: Int) -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = cardColors()) {
        Column(Modifier.padding(16.dp)) {
            Text(level.role.label(), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                FineSlider(
                    value = level.db.toInt(),
                    onValueChange = { onLevel(level.role, it) },
                    fine = false,
                    modifier = Modifier.weight(1f),
                    min = -12,
                    max = 12,
                )
                Text(
                    "%+d dB".format(level.db.toInt()),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun LevelRow(value: Int, fine: Boolean, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FineSlider(value, onChange, fine, Modifier.weight(1f))
        Text(
            "$value",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun cardColors() =
    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)

private fun SpeakerRole.label() = when (this) {
    SpeakerRole.MAIN -> "HEOS Bar"
    SpeakerRole.REAR_LEFT -> "Surround gauche"
    SpeakerRole.REAR_RIGHT -> "Surround droit"
    SpeakerRole.SUBWOOFER -> "Caisson de basses"
}
