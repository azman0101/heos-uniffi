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
import uniffi.heos_core.SpeakerLevel
import uniffi.heos_core.SpeakerRole
import uniffi.heos_core.VolumeEventListener

data class UiState(
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val speakers: List<HeosSpeaker> = emptyList(),
    val levels: List<SpeakerLevel> = emptyList(),
    val error: String? = null,
)

class HeosViewModel : ViewModel() {
    private val controller = HeosController()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    /** Envois en attente (regroupés), par clé « pid:… » ou « level:… ». */
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
                refreshLevels()
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

    /** Relit les niveaux (−12..+12 dB) de la barre, des arrières et du caisson. */
    fun refreshLevels() {
        viewModelScope.launch {
            try {
                _state.update { it.copy(levels = controller.getSpeakerLevels()) }
            } catch (e: HeosException) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    /** Volume général (HEOS CLI). */
    fun setVolume(pid: Long, level: Int) {
        patchSpeaker(pid) { it.copy(volume = level.toUByte()) }
        debounced("pid:$pid") { controller.setVolume(pid, level.toUByte()) }
    }

    /** Niveau d'une enceinte en dB, comme l'écran « Niveaux » de l'app HEOS. */
    fun setLevel(role: SpeakerRole, db: Int) {
        _state.update { s ->
            s.copy(levels = s.levels.map { if (it.role == role) it.copy(db = db.toByte()) else it })
        }
        debounced("level:$role") { controller.setSpeakerLevel(role, db.toByte()) }
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
