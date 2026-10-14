package com.example.heosdemo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.heos_core.HeosController
import uniffi.heos_core.HeosException
import uniffi.heos_core.HeosSpeaker
import uniffi.heos_core.SpeakerChannel
import uniffi.heos_core.VolumeEventListener

data class UiState(
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val speakers: List<HeosSpeaker> = emptyList(),
    val channels: List<SpeakerChannel> = emptyList(),
    val channelsLoading: Boolean = false,
    val error: String? = null,
)

class HeosViewModel : ViewModel() {
    private val controller = HeosController()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** Envois de volume en attente (regroupés), par clé « pid:… » ou « host:… ». */
    private val pending = HashMap<String, Job>()

    // Appelé depuis un thread du runtime Rust : ne fait que mettre à jour l'état.
    private val listener = object : VolumeEventListener {
        override fun onVolumeChanged(pid: Long, newLevel: UByte, isMuted: Boolean) {
            // Pendant qu'on glisse le curseur, on ignore l'écho du volume qu'on vient d'envoyer.
            val dragging = synchronized(pending) { pending.containsKey("pid:$pid") }
            patchSpeaker(pid) { it.copy(volume = if (dragging) it.volume else newLevel, isMuted = isMuted) }
        }
    }

    fun connect(ip: String) {
        if (_state.value.connecting) return
        _state.update { it.copy(connecting = true, error = null) }
        viewModelScope.launch {
            try {
                val speakers = controller.connect(ip, listener)
                _state.value = UiState(connected = true, speakers = speakers)
                refreshChannels()
            } catch (e: HeosException) {
                _state.value = UiState(error = e.message ?: e.toString())
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            controller.disconnect()
            _state.value = UiState()
        }
    }

    /** Détecte les enceintes du système et relit leur volume individuel. */
    fun refreshChannels() {
        if (_state.value.channelsLoading) return
        _state.update { it.copy(channelsLoading = true) }
        viewModelScope.launch {
            try {
                val channels = controller.discoverChannels()
                _state.update { it.copy(channels = channels, channelsLoading = false) }
            } catch (e: HeosException) {
                _state.update { it.copy(channelsLoading = false, error = e.message) }
            }
        }
    }

    /** Volume général (HEOS CLI). */
    fun setVolume(pid: Long, level: Int) {
        patchSpeaker(pid) { it.copy(volume = level.toUByte()) }
        debounced("pid:$pid") { controller.setVolume(pid, level.toUByte()) }
    }

    /** Volume d'une seule enceinte (UPnP). */
    fun setChannelVolume(host: String, level: Int) {
        _state.update { s ->
            s.copy(channels = s.channels.map { if (it.host == host) it.copy(volume = level.toUByte()) else it })
        }
        debounced("host:$host") { controller.setChannelVolume(host, level.toUByte()) }
    }

    fun toggleMute(pid: Long) {
        viewModelScope.launch {
            try {
                controller.toggleMute(pid) // le nouvel état revient via l'événement
            } catch (e: HeosException) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** Mise à jour optimiste de l'UI, envoi regroupé (~80 ms) pendant le glissement. */
    private fun debounced(key: String, send: suspend () -> Unit) {
        synchronized(pending) {
            pending[key]?.cancel()
            pending[key] = viewModelScope.launch {
                delay(80)
                try {
                    send()
                } catch (e: HeosException) {
                    _state.update { it.copy(error = e.message) }
                } finally {
                    synchronized(pending) { pending.remove(key) }
                }
            }
        }
    }

    private fun patchSpeaker(pid: Long, f: (HeosSpeaker) -> HeosSpeaker) = _state.update { s ->
        s.copy(speakers = s.speakers.map { if (it.pid == pid) f(it) else it })
    }

    override fun onCleared() {
        controller.destroy()
    }
}
