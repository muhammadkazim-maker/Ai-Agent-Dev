package com.example.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioCaptureState
import com.example.audio.LiveAudioPlaybackState
import com.example.audio.MicPermissionState
import com.example.contract.CoordinatorCommand
import com.example.contract.VerifiedOutcome
import com.example.coordinator.ConversationCoordinator
import com.example.coordinator.ConversationUiState
import com.example.live.LiveConnectionState
import kotlinx.coroutines.CoroutineDispatcher
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
 * owner, single [com.example.live.GeminiLiveConnectionAdapter], and single
 * [com.example.audio.LiveAudioPlaybackAdapter], managing Phase 04 one-turn voice conversation,
 * screen navigation, permission state transitions, live handshake verification, and lifecycle resource release.
 */
class ConversationShellViewModel(
    val coordinator: ConversationCoordinator = ConversationCoordinator(),
    val workDispatcher: CoroutineDispatcher = coordinator.backgroundDispatcher
) : ViewModel() {

    val uiState: StateFlow<ConversationUiState> = coordinator.uiState
    val audioState: StateFlow<AudioCaptureState> = coordinator.audioCapture.state
    val liveState: StateFlow<LiveConnectionState> = coordinator.liveAdapter.state
    val playbackState: StateFlow<LiveAudioPlaybackState> = coordinator.playbackAdapter.state

    private val _activeScreen = MutableStateFlow(ActiveScreen.CONVERSATION_SHELL)
    val activeScreen: StateFlow<ActiveScreen> = _activeScreen.asStateFlow()

    private val _isSettingsOpen = MutableStateFlow(false)
    val isSettingsOpen: StateFlow<Boolean> = _isSettingsOpen.asStateFlow()

    private val _fontScaleOverride = MutableStateFlow<Float?>(null)
    val fontScaleOverride: StateFlow<Float?> = _fontScaleOverride.asStateFlow()

    private val _hasRequestedPermissionOnce = MutableStateFlow(false)
    val hasRequestedPermissionOnce: StateFlow<Boolean> = _hasRequestedPermissionOnce.asStateFlow()

    private val jobLock = Any()
    private var activeLiveConnectJob: Job? = null
    private var activeVoiceTurnJob: Job? = null
    private var activeFinishTurnJob: Job? = null

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
        viewModelScope.launch {
            coordinator.playbackAdapter.state.collectLatest {
                coordinator.syncPlaybackState()
            }
        }
    }

    private fun isCoordinatorTurnInFlight(): Boolean {
        val currentState = coordinator.uiState.value.state
        return currentState == com.example.contract.ConversationState.PREPARING ||
            currentState == com.example.contract.ConversationState.CONNECTING ||
            currentState == com.example.contract.ConversationState.LISTENING ||
            currentState == com.example.contract.ConversationState.THINKING ||
            currentState == com.example.contract.ConversationState.PROCESSING ||
            currentState == com.example.contract.ConversationState.SPEAKING ||
            coordinator.isVoiceTurnInProgress()
    }

    fun onStartClicked() {
        synchronized(jobLock) {
            if (isCoordinatorTurnInFlight() || activeVoiceTurnJob?.isActive == true) {
                viewModelScope.launch(workDispatcher) {
                    coordinator.startVoiceTurn()
                }
                return
            }
            activeFinishTurnJob?.cancel()
            activeFinishTurnJob = null
            activeVoiceTurnJob = viewModelScope.launch(workDispatcher) {
                coordinator.startActiveConversation()
            }
        }
    }

    fun onResumeClicked() {
        synchronized(jobLock) {
            if (isCoordinatorTurnInFlight() || activeVoiceTurnJob?.isActive == true) {
                return
            }
            activeFinishTurnJob?.cancel()
            activeFinishTurnJob = null
            activeVoiceTurnJob = viewModelScope.launch(workDispatcher) {
                coordinator.resumeActiveConversation()
            }
        }
    }

    fun onToggleActiveConversationMode(enabled: Boolean) {
        coordinator.setActiveConversationEnabled(enabled)
    }

    fun onToggleAutoResumeOnReopen(enabled: Boolean) {
        coordinator.setAutoResumeOnScreenReopen(enabled)
    }

    fun onSelectInactivityTimeout(option: com.example.contract.InactivityTimeoutOption) {
        coordinator.setInactivityTimeoutOption(option)
    }

    fun onConversationScreenVisible() {
        if (_activeScreen.value != ActiveScreen.CONVERSATION_SHELL) return
        synchronized(jobLock) {
            if (isCoordinatorTurnInFlight() ||
                activeVoiceTurnJob?.isActive == true ||
                activeLiveConnectJob?.isActive == true
            ) {
                return
            }
            activeVoiceTurnJob = viewModelScope.launch(workDispatcher) {
                coordinator.onConversationScreenVisible()
            }
        }
    }

    fun onFinishSpokenTurnClicked() {
        synchronized(jobLock) {
            activeFinishTurnJob?.cancel()
            activeFinishTurnJob = viewModelScope.launch(workDispatcher) {
                coordinator.finishSpokenTurnAndAwaitResponse(
                    reason = "Operator tapped Send Spoken Question"
                )
            }
        }
    }

    private fun cancelAllJobsLocked() {
        activeFinishTurnJob?.cancel()
        activeFinishTurnJob = null
        activeVoiceTurnJob?.cancel()
        activeVoiceTurnJob = null
        activeLiveConnectJob?.cancel()
        activeLiveConnectJob = null
    }

    fun onPauseClicked(): VerifiedOutcome {
        synchronized(jobLock) {
            cancelAllJobsLocked()
        }
        return coordinator.dispatch(CoordinatorCommand.PAUSE)
    }

    fun onStopClicked(): VerifiedOutcome {
        synchronized(jobLock) {
            cancelAllJobsLocked()
        }
        return coordinator.dispatch(CoordinatorCommand.STOP)
    }

    fun onResetIdleClicked(): VerifiedOutcome {
        synchronized(jobLock) {
            cancelAllJobsLocked()
        }
        return coordinator.dispatch(CoordinatorCommand.RESET_IDLE)
    }

    fun onVerifyStopPausePrecedenceClicked(): VerifiedOutcome {
        synchronized(jobLock) {
            cancelAllJobsLocked()
        }
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
        synchronized(jobLock) {
            if (activeLiveConnectJob?.isActive == true) {
                return
            }
            activeLiveConnectJob = viewModelScope.launch(workDispatcher) {
                coordinator.connectLiveSessionWithHandshake()
            }
        }
    }

    fun onDisconnectLiveSessionClicked(): VerifiedOutcome {
        synchronized(jobLock) {
            activeLiveConnectJob?.cancel()
            activeLiveConnectJob = null
        }
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

    fun getRedactedDiagnosticsSnapshot(): String = coordinator.generateRedactedDiagnosticSnapshot()

    fun onLiveDiagnosticsScreenDisposed() {
        synchronized(jobLock) {
            activeLiveConnectJob?.cancel()
            activeLiveConnectJob = null
        }
        coordinator.onLiveDiagnosticsDisposed("Live Diagnostics screen disposed")
    }

    /**
     * Navigating away from the Microphone Setup screen follows explicit lifecycle rules:
     * any active self-test capture or playback is immediately stopped, hardware is released,
     * and any temporary recording is wiped. Reopening the visible conversation screen may
     * auto-resume according to remembered user preference (never overriding Pause or Stop).
     */
    fun onNavigateBackFromMicSetup() {
        coordinator.onLifecycleReleaseHardware(
            reason = "Navigated away from Microphone Setup screen",
            disconnectLive = false
        )
        _activeScreen.update { ActiveScreen.CONVERSATION_SHELL }
        onConversationScreenVisible()
    }

    /**
     * Navigating away from the Live Diagnostics screen cancels any scheduled retries,
     * unregisters network monitoring if no session is active, returns to the Conversation Shell,
     * and checks remembered auto-resume preference (never overriding Pause or Stop).
     */
    fun onNavigateBackFromLiveDiagnostics() {
        synchronized(jobLock) {
            activeLiveConnectJob?.cancel()
            activeLiveConnectJob = null
        }
        coordinator.onLiveDiagnosticsDisposed("Navigated back from Live Diagnostics screen")
        _activeScreen.update { ActiveScreen.CONVERSATION_SHELL }
        onConversationScreenVisible()
    }

    /**
     * Called from Activity/Composable lifecycle when Activity stops.
     * If [isChangingConfigurations] is true (e.g., orientation rotation), preserves the single
     * ViewModel-scoped coordinator and active session without releasing hardware or duplicating state.
     * If [isChangingConfigurations] is false (user backgrounded app or navigated away),
     * promptly releases hardware, wipes ephemeral self-test data, and disconnects live sockets.
     */
    fun onActivityStopped(isChangingConfigurations: Boolean) {
        if (isChangingConfigurations) {
            coordinator.onConfigurationChangePreserved()
        } else {
            synchronized(jobLock) {
                cancelAllJobsLocked()
            }
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
        synchronized(jobLock) {
            cancelAllJobsLocked()
        }
        coordinator.onLifecycleReleaseHardware(
            reason = "ViewModel cleared",
            disconnectLive = true
        )
        coordinator.speechDetector.close()
        super.onCleared()
    }
}
