package com.example.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioCaptureState
import com.example.audio.MicPermissionState
import com.example.contract.CoordinatorCommand
import com.example.contract.VerifiedOutcome
import com.example.coordinator.ConversationCoordinator
import com.example.coordinator.ConversationUiState
import com.example.live.LiveConnectionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ActiveScreen {
    CONVERSATION_SHELL,
    MICROPHONE_SETUP,
    LIVE_DIAGNOSTICS
}

/**
 * ViewModel wrapping the single [ConversationCoordinator], single [com.example.audio.AudioCapture]
 * owner, and single [com.example.live.GeminiLiveConnectionAdapter], managing screen navigation,
 * permission state transitions, live handshake verification, and lifecycle resource release.
 */
class ConversationShellViewModel(
    val coordinator: ConversationCoordinator = ConversationCoordinator()
) : ViewModel() {

    val uiState: StateFlow<ConversationUiState> = coordinator.uiState
    val audioState: StateFlow<AudioCaptureState> = coordinator.audioCapture.state
    val liveState: StateFlow<LiveConnectionState> = coordinator.liveAdapter.state

    private val _activeScreen = MutableStateFlow(ActiveScreen.CONVERSATION_SHELL)
    val activeScreen: StateFlow<ActiveScreen> = _activeScreen.asStateFlow()

    private val _isSettingsOpen = MutableStateFlow(false)
    val isSettingsOpen: StateFlow<Boolean> = _isSettingsOpen.asStateFlow()

    private val _fontScaleOverride = MutableStateFlow<Float?>(null)
    val fontScaleOverride: StateFlow<Float?> = _fontScaleOverride.asStateFlow()

    private val _hasRequestedPermissionOnce = MutableStateFlow(false)
    val hasRequestedPermissionOnce: StateFlow<Boolean> = _hasRequestedPermissionOnce.asStateFlow()

    private var activeLiveConnectJob: Job? = null

    init {
        viewModelScope.launch {
            coordinator.audioCapture.state.collectLatest {
                coordinator.syncAudioState()
            }
        }
        viewModelScope.launch {
            coordinator.liveAdapter.state.collectLatest {
                coordinator.syncLiveState()
            }
        }
    }

    fun onStartClicked(): VerifiedOutcome =
        coordinator.dispatch(CoordinatorCommand.START)

    fun onPauseClicked(): VerifiedOutcome {
        activeLiveConnectJob?.cancel()
        activeLiveConnectJob = null
        return coordinator.dispatch(CoordinatorCommand.PAUSE)
    }

    fun onStopClicked(): VerifiedOutcome {
        activeLiveConnectJob?.cancel()
        activeLiveConnectJob = null
        return coordinator.dispatch(CoordinatorCommand.STOP)
    }

    fun onResetIdleClicked(): VerifiedOutcome {
        activeLiveConnectJob?.cancel()
        activeLiveConnectJob = null
        return coordinator.dispatch(CoordinatorCommand.RESET_IDLE)
    }

    fun onVerifyStopPausePrecedenceClicked(): VerifiedOutcome {
        activeLiveConnectJob?.cancel()
        activeLiveConnectJob = null
        return coordinator.dispatchWithPrecedence(
            listOf(CoordinatorCommand.START, CoordinatorCommand.PAUSE, CoordinatorCommand.STOP)
        )
    }

    fun onStartMicSelfTestClicked(): VerifiedOutcome =
        coordinator.dispatch(CoordinatorCommand.START_MIC_SELF_TEST)

    fun onStopMicSelfTestClicked(): VerifiedOutcome =
        coordinator.dispatch(CoordinatorCommand.STOP_MIC_SELF_TEST)

    fun onPlayAndDeleteEphemeralCheckClicked(): VerifiedOutcome =
        coordinator.dispatch(CoordinatorCommand.PLAY_EPHEMERAL_CHECK)

    fun onWipeEphemeralAudioClicked() {
        coordinator.audioCapture.wipeEphemeralAudio(
            "Retention OFF — Ephemeral buffer manually wiped by operator (0 bytes)"
        )
        coordinator.syncAudioState()
    }

    fun onSetEphemeralCheckArmed(armed: Boolean) {
        coordinator.audioCapture.setEphemeralCheckArmed(armed)
        coordinator.syncAudioState()
    }

    fun onConnectLiveHandshakeClicked() {
        activeLiveConnectJob?.cancel()
        activeLiveConnectJob = viewModelScope.launch {
            coordinator.connectLiveSessionWithHandshake()
        }
    }

    fun onDisconnectLiveSessionClicked(): VerifiedOutcome {
        activeLiveConnectJob?.cancel()
        activeLiveConnectJob = null
        return coordinator.dispatch(CoordinatorCommand.DISCONNECT_LIVE_SESSION)
    }

    fun onOpenMicSetup() {
        _activeScreen.update { ActiveScreen.MICROPHONE_SETUP }
    }

    fun onOpenLiveDiagnostics() {
        coordinator.startLiveNetworkMonitoring()
        _activeScreen.update { ActiveScreen.LIVE_DIAGNOSTICS }
    }

    fun onLiveDiagnosticsScreenActive() {
        coordinator.startLiveNetworkMonitoring()
    }

    fun onLiveDiagnosticsScreenDisposed() {
        activeLiveConnectJob?.cancel()
        activeLiveConnectJob = null
        coordinator.onLiveDiagnosticsDisposed("Live Diagnostics screen disposed")
    }

    /**
     * Navigating away from the Microphone Setup screen follows explicit lifecycle rules:
     * any active self-test capture or playback is immediately stopped, hardware is released,
     * and any temporary recording is wiped.
     */
    fun onNavigateBackFromMicSetup() {
        coordinator.onLifecycleReleaseHardware(
            reason = "Navigated away from Microphone Setup screen",
            disconnectLive = false
        )
        _activeScreen.update { ActiveScreen.CONVERSATION_SHELL }
    }

    /**
     * Navigating away from the Live Diagnostics screen cancels any scheduled retries,
     * unregisters network monitoring if no session is active, and returns to the Conversation Shell.
     */
    fun onNavigateBackFromLiveDiagnostics() {
        activeLiveConnectJob?.cancel()
        activeLiveConnectJob = null
        coordinator.onLiveDiagnosticsDisposed("Navigated back from Live Diagnostics screen")
        _activeScreen.update { ActiveScreen.CONVERSATION_SHELL }
    }

    /**
     * Called from Activity/Composable lifecycle when Activity stops.
     * If [isChangingConfigurations] is false (user backgrounded app or navigated away),
     * promptly releases hardware, wipes ephemeral self-test data, and disconnects live sockets.
     */
    fun onActivityStopped(isChangingConfigurations: Boolean) {
        if (!isChangingConfigurations) {
            activeLiveConnectJob?.cancel()
            activeLiveConnectJob = null
            coordinator.onLifecycleReleaseHardware(
                reason = "Activity stopped (backgrounded / navigated away)",
                disconnectLive = true
            )
        }
    }

    /**
     * Evaluates the OS permission result after the system permission dialog returns.
     */
    fun onPermissionRequestResult(granted: Boolean, shouldShowRationale: Boolean) {
        _hasRequestedPermissionOnce.update { true }
        val resolved = when {
            granted -> MicPermissionState.GRANTED
            shouldShowRationale -> MicPermissionState.REFUSED_CAN_RETRY
            else -> MicPermissionState.PERMANENTLY_DENIED
        }
        coordinator.onMicPermissionUpdated(resolved)
    }

    /**
     * Re-checks permission when returning from Android System Settings (ON_RESUME) or startup.
     */
    fun onReCheckPermissionFromOs(isGranted: Boolean, shouldShowRationale: Boolean) {
        val currentPerm = coordinator.audioCapture.state.value.permissionState
        val requestedBefore = _hasRequestedPermissionOnce.value
        val resolved = when {
            isGranted -> MicPermissionState.GRANTED
            shouldShowRationale -> MicPermissionState.REFUSED_CAN_RETRY
            requestedBefore || currentPerm == MicPermissionState.PERMANENTLY_DENIED ||
                currentPerm == MicPermissionState.REFUSED_CAN_RETRY ->
                MicPermissionState.PERMANENTLY_DENIED
            else -> MicPermissionState.NOT_REQUESTED
        }
        coordinator.onMicPermissionUpdated(resolved)
    }

    fun onOpenSettings() {
        _isSettingsOpen.update { true }
    }

    fun onDismissSettings() {
        _isSettingsOpen.update { false }
    }

    fun onSetFontScaleOverride(scale: Float?) {
        _fontScaleOverride.update { scale }
    }

    override fun onCleared() {
        activeLiveConnectJob?.cancel()
        coordinator.onLifecycleReleaseHardware(
            reason = "ViewModel cleared",
            disconnectLive = true
        )
        super.onCleared()
    }
}
