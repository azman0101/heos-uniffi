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
import androidx.compose.material3.CircularProgressIndicator
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
import uniffi.heos_core.SpeakerChannel
import uniffi.heos_core.SpeakerRole

@Composable
fun SpeakersScreen(
    speakers: List<HeosSpeaker>,
    channels: List<SpeakerChannel>,
    channelsLoading: Boolean,
    error: String?,
    onVolume: (pid: Long, level: Int) -> Unit,
    onToggleMute: (pid: Long) -> Unit,
    onChannelVolume: (host: String, level: Int) -> Unit,
    onRefreshChannels: () -> Unit,
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
            item(key = "channels-header") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Volume par enceinte", style = MaterialTheme.typography.titleMedium)
                    if (channelsLoading) {
                        CircularProgressIndicator(Modifier.padding(end = 12.dp).height(24.dp))
                    } else {
                        TextButton(onClick = onRefreshChannels) { Text("Actualiser") }
                    }
                }
            }
            if (channels.isEmpty() && !channelsLoading) {
                item(key = "channels-empty") {
                    Text(
                        "Aucune enceinte détectée sur le réseau.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(channels, key = { it.host }) { channel ->
                ChannelCard(channel, fine, onChannelVolume)
            }
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
private fun ChannelCard(
    channel: SpeakerChannel,
    fine: Boolean,
    onChannelVolume: (host: String, level: Int) -> Unit,
) {
    Card(Modifier.fillMaxWidth(), colors = cardColors()) {
        Column(Modifier.padding(16.dp)) {
            Text(channel.name, style = MaterialTheme.typography.titleMedium)
            Text(
                channel.role.label(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LevelRow(channel.volume.toInt(), fine) { onChannelVolume(channel.host, it) }
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
    SpeakerRole.MAIN -> "Barre de son (avant)"
    SpeakerRole.SUBWOOFER -> "Caisson de basses"
    SpeakerRole.REAR_LEFT -> "Surround arrière gauche"
    SpeakerRole.REAR_RIGHT -> "Surround arrière droite"
    SpeakerRole.OTHER -> "Enceinte"
}
