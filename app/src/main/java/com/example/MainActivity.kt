package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.audio.AndroidAudioHardwareDriver
import com.example.audio.AudioCapture
import com.example.coordinator.ConversationCoordinator
import com.example.ui.shell.ConversationShellRoute
import com.example.ui.shell.ConversationShellViewModel
import com.example.ui.theme.OmniVoiceRebuildTheme

class MainActivity : ComponentActivity() {
    private val viewModel: ConversationShellViewModel by viewModels {
        val appContext = applicationContext
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val hardwareDriver = AndroidAudioHardwareDriver(appContext)
                val audioCapture = AudioCapture(
                    driver = hardwareDriver,
                    ephemeralCacheDir = appContext.cacheDir
                )
                val coordinator = ConversationCoordinator(audioCapture = audioCapture)
                return ConversationShellViewModel(coordinator = coordinator) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OmniVoiceRebuildTheme {
                ConversationShellRoute(viewModel = viewModel)
            }
        }
    }
}
