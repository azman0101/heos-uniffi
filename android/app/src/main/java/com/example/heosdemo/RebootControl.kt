package com.example.heosdemo

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.heos_core.RebootDevice
import uniffi.heos_core.discoverRebootDevices
import uniffi.heos_core.rebootDevices

internal data class RebootUiState(
    val devices: List<RebootDevice> = emptyList(),
    val scanning: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
)

class RebootViewModel(application: Application) : AndroidViewModel(application) {
    internal val state = MutableStateFlow(RebootUiState())

    fun scan() {
        if (state.value.scanning || state.value.busy) return
        state.update { it.copy(scanning = true, devices = emptyList(), message = null) }
        viewModelScope.launch {
            val wifi = getApplication<Application>().getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("heos-discovery").apply { setReferenceCounted(false) }
            try {
                lock.acquire()
                val host = getApplication<Application>().getSharedPreferences("heos", Context.MODE_PRIVATE)
                    .getString("ip", "192.168.1.12") ?: "192.168.1.12"
                val devices = discoverRebootDevices(host)
                state.update { it.copy(devices = devices, message = if (devices.isEmpty()) "Aucun appareil trouvé. Vérifiez le Wi-Fi puis actualisez." else null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(message = "Recherche impossible : ${e.message}") }
            } finally {
                if (lock.isHeld) lock.release()
                state.update { it.copy(scanning = false) }
            }
        }
    }

    fun reboot(devices: List<RebootDevice>) {
        if (state.value.busy || state.value.scanning || devices.isEmpty()) return
        state.update { it.copy(busy = true, message = "Redémarrage demandé. Vérification du retour en ligne (jusqu’à 2 minutes)…") }
        viewModelScope.launch {
            try {
                val results = rebootDevices(devices)
                state.update { it.copy(message = results.joinToString("\n\n") { r -> "${r.name} (${r.host})\n${r.detail}" }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(message = "Redémarrage non confirmé : ${e.message}") }
            } finally {
                state.update { it.copy(busy = false) }
            }
        }
    }
}

@Composable
fun RebootControl(vm: RebootViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var opened by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<List<RebootDevice>?>(null) }

    TextButton(onClick = { opened = true; if (!state.busy) vm.scan() }) {
        Text(if (state.busy) "↻ Redémarrage…" else "↻ Redémarrer")
    }
    if (opened) {
        AlertDialog(
            onDismissRequest = { opened = false; selection = null },
            title = { Text("Redémarrer les appareils") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Appareils HEOS détectés sur ce réseau. La lecture sera interrompue.")
                    if (state.scanning) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Recherche des appareils…")
                    }
                    state.devices.forEach { device ->
                        OutlinedButton(
                            onClick = { selection = listOf(device) },
                            enabled = !state.scanning && !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column {
                                Text(device.name)
                                Text("${device.model} · ${device.host}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (state.devices.isNotEmpty()) {
                        FilledTonalButton(
                            onClick = { selection = state.devices.toList() },
                            enabled = !state.scanning && !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Tous les appareils (${state.devices.size})") }
                    }
                    state.message?.let { Text(it) }
                    if (state.busy) Text("Vérification en cours. Les commandes restent bloquées jusqu’au résultat, pendant 2 minutes maximum.")
                }
            },
            confirmButton = { TextButton(onClick = { opened = false }) { Text("Fermer") } },
            dismissButton = {
                TextButton(onClick = vm::scan, enabled = !state.scanning && !state.busy) { Text("Actualiser") }
            },
        )
    }
    selection?.let { devices ->
        AlertDialog(
            onDismissRequest = { selection = null },
            title = { Text("Confirmer le redémarrage") },
            text = { Text(devices.joinToString("\n") { "${it.name} · ${it.host}" } + "\n\nLa lecture sera interrompue. Les réglages seront conservés.") },
            confirmButton = {
                TextButton(onClick = { selection = null; vm.reboot(devices) }) { Text("Redémarrer") }
            },
            dismissButton = { TextButton(onClick = { selection = null }) { Text("Annuler") } },
        )
    }
}
