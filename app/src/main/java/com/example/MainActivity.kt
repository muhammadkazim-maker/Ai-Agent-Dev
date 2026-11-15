package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.audio.AndroidAudioHardwareDriver
import com.example.audio.AndroidStreamingAudioTrackDriver
import com.example.audio.AudioCapture
import com.example.audio.LiveAudioPlaybackAdapter
import com.example.audio.SileroVadDetector
import com.example.coordinator.ConversationCoordinator
import com.example.live.AndroidLiveNetworkMonitor
import com.example.live.FirebaseAiLiveTransportDriver
import com.example.live.GeminiLiveConnectionAdapter
import com.example.live.configureAppCheck
import com.example.ui.shell.ConversationShellRoute
import com.example.ui.shell.ConversationShellViewModel
import com.example.ui.theme.OmniVoiceRebuildTheme
import kotlinx.coroutines.Dispatchers

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
                val isMicGrantedAtLaunch = androidx.core.content.ContextCompat.checkSelfPermission(
                    appContext,
                    android.Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (isMicGrantedAtLaunch) {
                    audioCapture.updatePermissionState(com.example.audio.MicPermissionState.GRANTED)
                }
                val networkMonitor = AndroidLiveNetworkMonitor(appContext)
                val liveAdapter = GeminiLiveConnectionAdapter(
                    driver = FirebaseAiLiveTransportDriver(networkMonitor = networkMonitor),
                    networkMonitor = networkMonitor,
                    backgroundDispatcher = Dispatchers.IO
                )
                val playbackAdapter = LiveAudioPlaybackAdapter(
                    driver = AndroidStreamingAudioTrackDriver()
                )
                val sileroDetector = SileroVadDetector.fromAndroidContext(appContext)
                val coordinator = ConversationCoordinator(
                    audioCapture = audioCapture,
                    liveAdapter = liveAdapter,
                    playbackAdapter = playbackAdapter,
                    preferencesStore = com.example.coordinator.SharedPreferencesConversationPreferencesStore(appContext),
                    backgroundDispatcher = Dispatchers.IO,
                    speechDetector = sileroDetector
                )
                return ConversationShellViewModel(
                    coordinator = coordinator,
                    workDispatcher = Dispatchers.IO
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)
        enableEdgeToEdge()
        setContent {
            OmniVoiceRebuildTheme {
                ConversationShellRoute(viewModel = viewModel)
            }
        }
    }
}
