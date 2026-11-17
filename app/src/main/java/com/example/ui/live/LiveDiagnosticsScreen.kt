package com.example.ui.live

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.coordinator.ConversationUiState
import com.example.live.LiveConfigConstants
import com.example.live.LiveConnectionPhase
import com.example.live.LiveConnectionState
import com.example.live.LiveFailureClassification

@Composable
fun LiveDiagnosticsScreen(
    uiState: ConversationUiState,
    liveState: LiveConnectionState,
    onConnectLiveHandshake: () -> Unit,
    onDisconnectLiveSession: () -> Unit,
    onLargeStopAll: () -> Unit,
    onNavigateBack: () -> Unit,
    onCopyDiagnostics: () -> Unit = {},
    onScreenActive: () -> Unit = {},
    onScreenDisposed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    DisposableEffect(Unit) {
        onScreenActive()
        onDispose {
            onScreenDisposed()
        }
    }

    BackHandler {
        onNavigateBack()
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("live_diagnostics_screen_root"),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            val isLandscape = maxWidth >= 600.dp || maxWidth > maxHeight

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LiveDiagnosticsTopBar(
                    liveState = liveState,
                    onNavigateBack = onNavigateBack,
                    onCopyDiagnostics = onCopyDiagnostics
                )

                if (isLandscape) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag("live_diag_landscape_split"),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(0.55f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                        ) {
                            RedactedDiagnosticsPane(
                                uiState = uiState,
                                liveState = liveState,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Column(
                            modifier = Modifier
                                .weight(0.45f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                        ) {
                            LiveConnectionControlsPane(
                                liveState = liveState,
                                onConnectLiveHandshake = onConnectLiveHandshake,
                                onDisconnectLiveSession = onDisconnectLiveSession,
                                onLargeStopAll = onLargeStopAll,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .testTag("live_diag_portrait_column"),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        RedactedDiagnosticsPane(
                            uiState = uiState,
                            liveState = liveState,
                            modifier = Modifier.fillMaxWidth()
                        )
                        LiveConnectionControlsPane(
                            liveState = liveState,
                            onConnectLiveHandshake = onConnectLiveHandshake,
                            onDisconnectLiveSession = onDisconnectLiveSession,
                            onLargeStopAll = onLargeStopAll,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveDiagnosticsTopBar(
    liveState: LiveConnectionState,
    onNavigateBack: () -> Unit,
    onCopyDiagnostics: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("live_diag_top_bar"),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("live_diag_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Return to Conversation Shell",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Gemini Live Connection & Configuration Diagnostics",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "PHASE 03 // ONE LIVE ADAPTER • FIREBASE AI LOGIC APP CHECK • ZERO TOOLS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedButton(
                    onClick = onCopyDiagnostics,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("live_diag_copy_button")
                ) {
                    Text(
                        text = "COPY DIAG",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LivePhaseBadge(phase = liveState.phase)
            }
        }
    }
}

@Composable
private fun LivePhaseBadge(phase: LiveConnectionPhase) {
    val (bgColor, borderColor, textColor) = when (phase) {
        LiveConnectionPhase.SETUP_ACCEPTED,
        LiveConnectionPhase.ACTIVE -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        LiveConnectionPhase.CONNECTING,
        LiveConnectionPhase.RECONNECTING,
        LiveConnectionPhase.RETRY_BACKOFF_WAITING,
        LiveConnectionPhase.NETWORK_RESTORED -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        LiveConnectionPhase.NETWORK_UNAVAILABLE,
        LiveConnectionPhase.FAILED -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.onErrorContainer
        )
        LiveConnectionPhase.DISCONNECTED -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.outline,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Surface(
        modifier = Modifier.testTag("live_diag_phase_badge"),
        color = bgColor,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.5.dp, borderColor)
    ) {
        Text(
            text = phase.wireCode,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = textColor,
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .testTag("live_diag_phase_text")
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RedactedDiagnosticsPane(
    uiState: ConversationUiState,
    liveState: LiveConnectionState,
    modifier: Modifier = Modifier
) {
    val diag = liveState.diagnostics
    val configStatusText = when {
        liveState.handshakeVerified && liveState.isInternetValidated -> "CONFIG_VERIFIED"
        liveState.phase == LiveConnectionPhase.NETWORK_UNAVAILABLE ||
            liveState.failureClassification == LiveFailureClassification.NETWORK_UNAVAILABLE ->
            "NETWORK_UNAVAILABLE"
        liveState.phase == LiveConnectionPhase.NETWORK_RESTORED ->
            "NETWORK_RESTORED / READY_TO_RECONNECT"
        liveState.failureClassification == LiveFailureClassification.NETWORK_ERROR ||
            liveState.failureClassification == LiveFailureClassification.TRANSIENT_NETWORK_OR_SOCKET ->
            "NETWORK_ERROR"
        liveState.failureClassification == LiveFailureClassification.BAD_CREDENTIALS_OR_APP_CHECK ->
            "NEEDS_SETUP / CONFIG_ERROR"
        else -> "READY_OR_NEEDS_SETUP"
    }

    val networkStatusText = if (liveState.isInternetValidated) {
        "VALIDATED"
    } else {
        "NETWORK_UNAVAILABLE"
    }

    Card(
        modifier = modifier.testTag("live_redacted_diagnostics_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        // NOTE: Do NOT apply Modifier.verticalScroll here; parent container (landscape column or
        // portrait column) owns vertical scrolling so nested verticalScroll never occurs.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "REDACTED LIVE CONFIGURATION DIAGNOSTICS",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Honest Phase 03 Scope Banner: No Audio Playback Claimed, Zero Action Tools
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("live_diag_honest_boundary_banner"),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "PHASE 03 HONESTY BOUNDARY",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = "• Audio Playback Claimed: ${if (diag.audioPlaybackClaimed) "YES" else "NO (Handshake Verification Only)"}\n" +
                                "• Action Tools Registered: ${diag.registeredActionToolsCount} (Zero tools in Phase 03)\n" +
                                "• Coding-Model Picker Separation: Runtime model ID is explicitly pinned to ${diag.modelId} (never inferred from IDE model picker).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DiagnosticChip(
                    label = "MODEL: ${diag.modelId}",
                    tag = "live_diag_model_label"
                )
                DiagnosticChip(
                    label = "API: ${diag.apiVersion}",
                    tag = "live_diag_api_version_label"
                )
                DiagnosticChip(
                    label = "ENDPOINT: ${diag.endpointFamily}",
                    tag = "live_diag_endpoint_family_label"
                )
                DiagnosticChip(
                    label = "AUTH: ${diag.authRoute}",
                    tag = "live_diag_auth_route_label"
                )
                DiagnosticChip(
                    label = "NETWORK: $networkStatusText",
                    tag = "live_diag_network_status_label"
                )
                DiagnosticChip(
                    label = "CONFIG: $configStatusText",
                    tag = "live_diag_config_status_label"
                )
                DiagnosticChip(
                    label = "GEN: ${diag.sessionGenerationWireId}",
                    tag = "live_diag_generation_label"
                )
                DiagnosticChip(
                    label = "FAILURE CODE: ${diag.failureCode}",
                    tag = "live_diag_failure_code_label"
                )
                DiagnosticChip(
                    label = "ATTEMPTS: ${diag.attemptCount} (RETRIES: ${diag.retryCount})",
                    tag = "live_diag_attempt_label"
                )
                DiagnosticChip(
                    label = "AUTO-RECONNECTS: ${diag.autoReconnectCount}",
                    tag = "live_diag_auto_reconnect_label"
                )
                DiagnosticChip(
                    label = "STALE DROPPED: ${diag.staleCallbacksDropped}",
                    tag = "live_diag_stale_callbacks_label"
                )
                val vadStatus = when {
                    uiState.vadModelLoaded -> "LOADED"
                    !uiState.vadModelLoadError.isNullOrBlank() -> "LOAD_FAILED"
                    else -> "NOT_LOADED"
                }
                DiagnosticChip(
                    label = "VAD: ${uiState.vadDetectorName} v${uiState.vadDetectorVersion} ($vadStatus)",
                    tag = "live_diag_vad_model_label"
                )
                DiagnosticChip(
                    label = "GATE: ${uiState.speechGateState.wireCode} • ${if (uiState.vadSpeechDecision) "SPEECH" else "NOISE"} (p=${"%.2f".format(uiState.vadSpeechProbability)})",
                    tag = "live_diag_vad_gate_label"
                )
                DiagnosticChip(
                    label = "CAND: ${uiState.consecutiveSpeechDurationMs}ms (${uiState.vadCandidateConfirmationCount}w) • ACT_START: ${uiState.activityStartCount}",
                    tag = "live_diag_vad_candidate_label"
                )
                DiagnosticChip(
                    label = "RESUMPTION: ${liveState.sessionResumptionStatus.wireCode} • RESTORE: ${liveState.contextRestorationStatus.wireCode} • GOAWAY: ${liveState.serverGoAwayCount}",
                    tag = "live_diag_resumption_label"
                )
                DiagnosticChip(
                    label = "STALLS: server=${uiState.serverOutputStallCount}, missingComplete=${uiState.missingTurnCompleteCount}, hw=${uiState.hardwarePlaybackStallCount}",
                    tag = "live_diag_stall_counts_label"
                )
                val perf = uiState.performanceSummary
                val estMedianText = perf.outputStartLatencyEstimateDistribution.medianValue?.let { "${it}ms" } ?: "UNAVAILABLE"
                DiagnosticChip(
                    label = "PERF (n=${perf.recordedWindowTurnCount}/${perf.maxBoundedTurnWindow}): ${perf.roadmapTargetStatus} • estMedian=$estMedianText",
                    tag = "live_diag_perf_summary_label"
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Text(
                text = liveState.statusHeadline,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("live_diag_status_headline")
            )

            Text(
                text = diag.redactedSummary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("live_diag_redacted_summary")
            )

            if (liveState.failureClassification != LiveFailureClassification.NONE) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("live_diag_failure_card"),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "CLASSIFIED FAILURE: ${liveState.failureClassification.wireCode}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = liveState.failureClassification.guidance,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            liveState.autoRetryStoppedReason?.let { stopReason ->
                                Text(
                                    text = "Retry Policy: $stopReason",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.testTag("live_diag_retry_stopped_label")
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = "COORDINATOR SESSION: ${uiState.sessionId.value} • APK KEY EMBEDDED: FALSE • MODEL FALLBACK: PROHIBITED",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DiagnosticChip(label: String, tag: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag(tag)
        )
    }
}

@Composable
private fun LiveConnectionControlsPane(
    liveState: LiveConnectionState,
    onConnectLiveHandshake: () -> Unit,
    onDisconnectLiveSession: () -> Unit,
    onLargeStopAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isConnectingOrBackoff =
        liveState.phase == LiveConnectionPhase.CONNECTING ||
            liveState.phase == LiveConnectionPhase.RECONNECTING ||
            liveState.phase == LiveConnectionPhase.RETRY_BACKOFF_WAITING

    Card(
        modifier = modifier.testTag("live_controls_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        // NOTE: Do NOT apply Modifier.verticalScroll here; parent container owns scrolling.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "HANDSHAKE & SESSION CONTROLS",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "Verify the authenticated WebSocket setup handshake against ${LiveConfigConstants.SUPPORTED_LIVE_MODEL_ID} using Firebase AI Logic managed credentials and App Check.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = onConnectLiveHandshake,
                enabled = !isConnectingOrBackoff,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("connect_live_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    imageVector = if (liveState.handshakeVerified) Icons.Filled.CloudDone else Icons.Filled.CloudSync,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (liveState.handshakeVerified) {
                        "Re-Verify Live Handshake"
                    } else {
                        "Verify Live Handshake (Connect)"
                    },
                    style = MaterialTheme.typography.labelLarge
                )
            }

            OutlinedButton(
                onClick = onDisconnectLiveSession,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("disconnect_live_button"),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Icon(
                    imageVector = Icons.Filled.CloudOff,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Disconnect Live Socket",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            Button(
                onClick = onLargeStopAll,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("live_diag_large_stop_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "STOP ALL & RELEASE SOCKET/HARDWARE",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}
