package com.example

import com.example.contract.ActionId
import com.example.contract.ConversationState
import com.example.contract.CoordinatorCommand
import com.example.contract.VerifiedOutcome
import com.example.coordinator.ConversationCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun `initial coordinator state is IDLE with inactive microphone and valid IDs`() {
        val coordinator = ConversationCoordinator()
        val state = coordinator.uiState.value

        assertEquals(ConversationState.IDLE, state.state)
        assertFalse("Phase 01 must never fake an active microphone", state.isMicrophoneActive)
        assertTrue(state.sessionId.value.startsWith("ov01-sess-"))
        assertTrue(state.currentTurnId.value.startsWith("ov01-turn-"))
        assertTrue(state.lastActionId.value.startsWith("ov01-act-init-"))
        assertEquals(1, state.transcript.size)
    }

    @Test
    fun `start command transitions honestly to NOT_CONFIGURED without activating mic`() {
        val coordinator = ConversationCoordinator()
        val outcome = coordinator.dispatch(CoordinatorCommand.START)
        val state = coordinator.uiState.value

        assertTrue(outcome is VerifiedOutcome.HonestNotConfiguredOutcome)
        assertEquals(ConversationState.NOT_CONFIGURED, state.state)
        assertFalse("Microphone must remain inactive in Phase 01", state.isMicrophoneActive)
        assertEquals("HONEST_NOT_CONFIGURED", outcome.code)
    }

    @Test
    fun `stop and pause precedence enforces STOP over PAUSE and START`() {
        val coordinator = ConversationCoordinator()

        // Transition to NOT_CONFIGURED via START
        coordinator.dispatch(CoordinatorCommand.START)
        assertEquals(ConversationState.NOT_CONFIGURED, coordinator.uiState.value.state)

        // PAUSE transitions to PAUSED
        val pauseOutcome = coordinator.dispatch(CoordinatorCommand.PAUSE)
        assertTrue(pauseOutcome is VerifiedOutcome.StateTransitionVerified)
        assertEquals(ConversationState.PAUSED, coordinator.uiState.value.state)

        // STOP transitions to STOPPED
        val stopOutcome = coordinator.dispatch(CoordinatorCommand.STOP)
        assertTrue(stopOutcome is VerifiedOutcome.StateTransitionVerified)
        assertEquals(ConversationState.STOPPED, coordinator.uiState.value.state)

        // Attempting PAUSE while STOPPED is preempted by STOP precedence
        val pauseAfterStop = coordinator.dispatch(CoordinatorCommand.PAUSE)
        assertTrue(pauseAfterStop is VerifiedOutcome.PreemptedByPrecedence)
        assertEquals(ConversationState.STOPPED, coordinator.uiState.value.state)
        assertEquals("PREEMPTED_BY_STOP", pauseAfterStop.code)
    }

    @Test
    fun `concurrent batch dispatch enforces STOP over PAUSE over START`() {
        val coordinator = ConversationCoordinator()

        val pauseOverStart = coordinator.dispatchWithPrecedence(
            listOf(CoordinatorCommand.START, CoordinatorCommand.PAUSE)
        )
        assertTrue(pauseOverStart is VerifiedOutcome.PreemptedByPrecedence)
        assertEquals(ConversationState.PAUSED, coordinator.uiState.value.state)

        val stopOverAll = coordinator.dispatchWithPrecedence(
            listOf(CoordinatorCommand.START, CoordinatorCommand.PAUSE, CoordinatorCommand.STOP)
        )
        assertTrue(stopOverAll is VerifiedOutcome.PreemptedByPrecedence)
        assertEquals(ConversationState.STOPPED, coordinator.uiState.value.state)
    }

    @Test
    fun `duplicate ActionId is rejected to guarantee exactly-once effects`() {
        val coordinator = ConversationCoordinator()
        val fixedActionId = ActionId("ov01-act-custom-0099")

        val firstOutcome = coordinator.dispatch(CoordinatorCommand.START, fixedActionId)
        assertTrue(firstOutcome is VerifiedOutcome.HonestNotConfiguredOutcome)
        val countAfterFirst = coordinator.uiState.value.executedActionCount

        // Attempt to reuse the exact same ActionId for a STOP command
        val duplicateOutcome = coordinator.dispatch(CoordinatorCommand.STOP, fixedActionId)
        assertTrue(duplicateOutcome is VerifiedOutcome.DuplicateActionRejected)
        assertEquals(ConversationState.NOT_CONFIGURED, coordinator.uiState.value.state)
        assertEquals(countAfterFirst, coordinator.uiState.value.executedActionCount)
        assertEquals(1, coordinator.uiState.value.duplicateRejectedCount)
    }

    @Test
    fun `reset to idle allocates a new SessionId`() {
        val coordinator = ConversationCoordinator()
        val initialSession = coordinator.uiState.value.sessionId

        coordinator.dispatch(CoordinatorCommand.STOP)
        assertEquals(ConversationState.STOPPED, coordinator.uiState.value.state)

        coordinator.dispatch(CoordinatorCommand.RESET_IDLE)
        assertEquals(ConversationState.IDLE, coordinator.uiState.value.state)
        assertNotEquals(initialSession, coordinator.uiState.value.sessionId)
    }
}
