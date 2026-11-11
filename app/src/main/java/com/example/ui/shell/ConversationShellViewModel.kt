package com.example.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioCaptureState
import com.example.audio.MicPermissionState
import com.example.contract.CoordinatorCommand
import com.example.contract.VerifiedOutcome
import com.example.coordinator.ConversationCoordinator
import com.example.coordinator.ConversationUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ActiveScreen {
    CONVERSATION_SHELL,
    MICROPHONE_SETUP
}

/**
 * ViewModel wrapping the single [ConversationCoordinator] and single [com.example.audio.AudioCapture]
 * owner, managing screen navigation, permission state transitions, and lifecycle hardware release.
 */
class ConversationShellViewModel(
    val coordinator: ConversationCoordinator = ConversationCoordinator()
) : ViewModel() {

    val uiState: StateFlow<ConversationUiState> = coordinator.uiState
    val audioState: StateFlow<AudioCaptureState> = coordinator.audioCapture.state

    private val _activeScreen = MutableStateFlow(ActiveScreen.CONVERSATION_SHELL)
    val activeScreen: StateFlow<ActiveScreen> = _activeScreen.asStateFlow()

    private val _isSettingsOpen = MutableStateFlow(false)
    val isSettingsOpen: StateFlow<Boolean> = _isSettingsOpen.asStateFlow()

    private val _fontScaleOverride = MutableStateFlow<Float?>(null)
    val fontScaleOverride: StateFlow<Float?> = _fontScaleOverride.asStateFlow()

    private val _hasRequestedPermissionOnce = MutableStateFlow(false)
    val hasRequestedPermissionOnce: StateFlow<Boolean> = _hasRequestedPermissionOnce.asStateFlow()

    init {
        viewModelScope.launch {
            coordinator.audioCapture.state.collectLatest {
                coordinator.syncAudioState()
            }
        }
    }

    fun onStartClicked(): VerifiedOutcome =
        coordinator.dispatch(CoordinatorCommand.START)

    fun onPauseClicked(): VerifiedOutcome =
        coordinator.dispatch(CoordinatorCommand.PAUSE)

    fun onStopClicked(): VerifiedOutcome =
        coordinator.dispatch(CoordinatorCommand.STOP)

    fun onResetIdleClicked(): VerifiedOutcome =
        coordinator.dispatch(CoordinatorCommand.RESET_IDLE)

    fun onVerifyStopPausePrecedenceClicked(): VerifiedOutcome =
        coordinator.dispatchWithPrecedence(
            listOf(CoordinatorCommand.START, CoordinatorCommand.PAUSE, CoordinatorCommand.STOP)
        )

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

    fun onOpenMicSetup() {
        _activeScreen.update { ActiveScreen.MICROPHONE_SETUP }
    }

    /**
     * Navigating away from the Microphone Setup screen follows explicit lifecycle rules:
     * any active self-test capture or playback is immediately stopped, hardware is released,
     * and any temporary recording is wiped.
     */
    fun onNavigateBackFromMicSetup() {
        coordinator.onLifecycleReleaseHardware("Navigated away from Microphone Setup screen")
        _activeScreen.update { ActiveScreen.CONVERSATION_SHELL }
    }

    /**
     * Called from Activity/Composable lifecycle when Activity stops.
     * If [isChangingConfigurations] is false (user backgrounded app or navigated away),
     * promptly releases hardware and wipes ephemeral self-test data.
     */
    fun onActivityStopped(isChangingConfigurations: Boolean) {
        if (!isChangingConfigurations) {
            coordinator.onLifecycleReleaseHardware("Activity stopped (backgrounded / navigated away)")
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
        coordinator.onLifecycleReleaseHardware("ViewModel cleared")
        super.onCleared()
    }
}
