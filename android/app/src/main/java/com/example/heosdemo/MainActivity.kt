package com.example.heosdemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: HeosViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            HeosTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        val state by vm.state.collectAsStateWithLifecycle()
                        when {
                            state.connected -> SpeakersScreen(
                                speakers = state.speakers,
                                levels = state.levels,
                                error = state.error,
                                onVolume = vm::setVolume,
                                onToggleMute = vm::toggleMute,
                                onLevel = vm::setLevel,
                                onRefreshLevels = vm::refreshLevels,
                                onDisconnect = vm::disconnect,
                                onDismissError = vm::dismissError,
                            )
                            state.probing -> ProbingScreen()
                            else -> ConnectScreen(
                                initialIp = vm.lastIp,
                                connecting = state.connecting,
                                error = state.error,
                                onConnect = vm::connect,
                            )
                        }
                    }
                }
            }
        }
    }
}
