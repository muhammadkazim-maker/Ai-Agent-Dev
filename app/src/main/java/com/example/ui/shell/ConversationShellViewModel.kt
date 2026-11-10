package com.example.ui.shell

import androidx.lifecycle.ViewModel
import com.example.contract.CoordinatorCommand
import com.example.contract.VerifiedOutcome
import com.example.coordinator.ConversationCoordinator
import com.example.coordinator.ConversationUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * ViewModel wrapping the single [ConversationCoordinator] and shell-level UI preferences
 * (such as the Settings placeholder visibility and in-app accessibility font scale preview).
 */
class ConversationShellViewModel(
    private val coordinator: ConversationCoordinator = ConversationCoordinator()
) : ViewModel() {

    val uiState: StateFlow<ConversationUiState> = coordinator.uiState

    private val _isSettingsOpen = MutableStateFlow(false)
    val isSettingsOpen: StateFlow<Boolean> = _isSettingsOpen.asStateFlow()

    private val _fontScaleOverride = MutableStateFlow<Float?>(null)
    val fontScaleOverride: StateFlow<Float?> = _fontScaleOverride.asStateFlow()

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

    fun onOpenSettings() {
        _isSettingsOpen.update { true }
    }

    fun onDismissSettings() {
        _isSettingsOpen.update { false }
    }

    fun onSetFontScaleOverride(scale: Float?) {
        _fontScaleOverride.update { scale }
    }
}
