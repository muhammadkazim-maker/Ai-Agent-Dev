package com.example.ui.shell

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.R
import com.example.contract.ConversationState
import com.example.contract.TranscriptEntry
import com.example.coordinator.ConversationUiState

@Composable
fun ConversationShellRoute(
    viewModel: ConversationShellViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isSettingsOpen by viewModel.isSettingsOpen.collectAsStateWithLifecycle()
    val fontScaleOverride by viewModel.fontScaleOverride.collectAsStateWithLifecycle()

    val baseDensity = LocalDensity.current
    val effectiveDensity = if (fontScaleOverride != null) {
        Density(density = baseDensity.density, fontScale = fontScaleOverride!!)
    } else {
        baseDensity
    }

    CompositionLocalProvider(LocalDensity provides effectiveDensity) {
        ConversationShellScreen(
            uiState = uiState,
            isSettingsOpen = isSettingsOpen,
            fontScaleOverride = fontScaleOverride,
            onStartClick = { viewModel.onStartClicked() },
            onPauseClick = { viewModel.onPauseClicked() },
            onStopClick = { viewModel.onStopClicked() },
            onResetIdleClick = { viewModel.onResetIdleClicked() },
            onVerifyPrecedenceClick = { viewModel.onVerifyStopPausePrecedenceClicked() },
            onOpenSettings = viewModel::onOpenSettings,
            onDismissSettings = viewModel::onDismissSettings,
            onSelectFontScale = viewModel::onSetFontScaleOverride,
            modifier = modifier
        )
    }
}

@Composable
fun ConversationShellScreen(
    uiState: ConversationUiState,
    isSettingsOpen: Boolean,
    fontScaleOverride: Float?,
    onStartClick: () -> Unit,
    onPauseClick: () -> Unit,
    onStopClick: () -> Unit,
    onResetIdleClick: () -> Unit,
    onVerifyPrecedenceClick: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissSettings: () -> Unit,
    onSelectFontScale: (Float?) -> Unit,
    modifier: Modifier = Modifier
) {
    if (isSettingsOpen) {
        BackHandler { onDismissSettings() }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("conversation_shell_root"),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Landscape-first primary layout when width >= 600.dp or aspect ratio is landscape.
            // Portrait fallback when compact/vertical.
            val isLandscapeLayout = maxWidth >= 600.dp || maxWidth > maxHeight

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TopTelemetryHeader(
                    uiState = uiState,
                    isLandscapeLayout = isLandscapeLayout,
                    onOpenSettings = onOpenSettings
                )

                if (isLandscapeLayout) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag("landscape_split_container"),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        TranscriptAndTelemetryPane(
                            uiState = uiState,
                            modifier = Modifier
                                .weight(0.60f)
                                .fillMaxHeight()
                        )
                        CommandDeckPane(
                            uiState = uiState,
                            onStartClick = onStartClick,
                            onPauseClick = onPauseClick,
                            onStopClick = onStopClick,
                            onResetIdleClick = onResetIdleClick,
                            onVerifyPrecedenceClick = onVerifyPrecedenceClick,
                            modifier = Modifier
                                .weight(0.40f)
                                .fillMaxHeight()
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag("portrait_fallback_container"),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TranscriptAndTelemetryPane(
                            uiState = uiState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        )
                        CommandDeckPane(
                            uiState = uiState,
                            onStartClick = onStartClick,
                            onPauseClick = onPauseClick,
                            onStopClick = onStopClick,
                            onResetIdleClick = onResetIdleClick,
                            onVerifyPrecedenceClick = onVerifyPrecedenceClick,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        if (isSettingsOpen) {
            SettingsPlaceholderDialog(
                uiState = uiState,
                fontScaleOverride = fontScaleOverride,
                onSelectFontScale = onSelectFontScale,
                onDismiss = onDismissSettings
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopTelemetryHeader(
    uiState: ConversationUiState,
    isLandscapeLayout: Boolean,
    onOpenSettings: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("top_telemetry_bar"),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${stringResource(R.string.shell_subtitle)} • ${if (isLandscapeLayout) "LANDSCAPE DECK" else "PORTRAIT FALLBACK"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RealStateBadge(state = uiState.state)

                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("settings_placeholder_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.cd_settings_button),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TelemetryChip(
                    label = "SESSION: ${uiState.sessionId.value}",
                    tag = "session_id_label"
                )
                TelemetryChip(
                    label = "TURN: ${uiState.currentTurnId.value}",
                    tag = "turn_id_label"
                )
                TelemetryChip(
                    label = "ACTION: ${uiState.lastActionId.value}",
                    tag = "action_id_label"
                )
                HonestMicrophoneBadge(label = uiState.microphoneStatusLabel)
            }
        }
    }
}

@Composable
private fun RealStateBadge(state: ConversationState) {
    val (bgColor, borderColor, textColor) = when (state) {
        ConversationState.IDLE -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.outline,
            MaterialTheme.colorScheme.onSurface
        )
        ConversationState.NOT_CONFIGURED -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.secondary,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
        ConversationState.PAUSED -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        ConversationState.STOPPED -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.onErrorContainer
        )
    }

    Surface(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .testTag("state_label_badge"),
        color = bgColor,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.5.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(borderColor)
            )
            Text(
                text = state.wireLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = textColor,
                modifier = Modifier.testTag("state_label_text")
            )
        }
    }
}

@Composable
private fun TelemetryChip(label: String, tag: String) {
    Surface(
        modifier = Modifier.testTag(tag),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun HonestMicrophoneBadge(label: String) {
    Surface(
        modifier = Modifier.testTag("mic_honest_indicator"),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.MicOff,
                contentDescription = stringResource(R.string.mic_status_honest),
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun TranscriptAndTelemetryPane(
    uiState: ConversationUiState,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.testTag("transcript_pane"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.transcript_header),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "ENTRIES: ${uiState.transcript.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // State summary callout banner
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("state_summary_banner"),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "STATE: ${uiState.state.wireLabel}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = uiState.state.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("transcript_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(
                    items = uiState.transcript.asReversed(),
                    key = { it.id }
                ) { entry ->
                    TranscriptEntryCard(entry = entry)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranscriptEntryCard(entry: TranscriptEntry) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("transcript_item_${entry.turnId.value}"),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "${entry.roleLabel} • ${entry.outcomeCode}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "${entry.turnId.value} | ${entry.actionId.value}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommandDeckPane(
    uiState: ConversationUiState,
    onStartClick: () -> Unit,
    onPauseClick: () -> Unit,
    onStopClick: () -> Unit,
    onResetIdleClick: () -> Unit,
    onVerifyPrecedenceClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.testTag("command_deck_pane"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.controls_header),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            // Large Start Button
            Button(
                onClick = onStartClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("start_button")
                    .semantics { contentDescription = "Start conversation session" },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        text = stringResource(R.string.action_start),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Reports honest NOT_CONFIGURED in Phase 01",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            // Large Pause & Stop Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onPauseClick,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 64.dp)
                        .minimumInteractiveComponentSize()
                        .testTag("pause_button")
                        .semantics { contentDescription = "Pause conversation session" },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_pause),
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                Button(
                    onClick = onStopClick,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 64.dp)
                        .minimumInteractiveComponentSize()
                        .testTag("stop_button")
                        .semantics { contentDescription = "Stop conversation session immediately" },
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
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_stop),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            // Contract diagnostic controls: Reset to IDLE and Verify Stop/Pause Precedence
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onResetIdleClick,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("reset_idle_button"),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_reset_idle),
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                OutlinedButton(
                    onClick = onVerifyPrecedenceClick,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("verify_precedence_button"),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.action_verify_precedence),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            // Contract & Build Identifier Footer
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("contract_build_footer"),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "BUILD ID: ${uiState.sourceBuildId} • APP ID: ${BuildConfig.APPLICATION_ID}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "MODEL STATUS: ${uiState.modelConfigStatus}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsPlaceholderDialog(
    uiState: ConversationUiState,
    fontScaleOverride: Float?,
    onSelectFontScale: (Float?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("settings_placeholder_dialog"),
        title = {
            Text(
                text = stringResource(R.string.settings_placeholder_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_placeholder_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                Text(
                    text = "PHASE 01 ENVIRONMENT & CONTRACT TELEMETRY",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = "• Application ID: ${BuildConfig.APPLICATION_ID}\n" +
                        "• Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                        "• Source/Build ID: ${BuildConfig.SOURCE_BUILD_ID}\n" +
                        "• Model Config: ${BuildConfig.MODEL_CONFIG_STATUS}\n" +
                        "• Runtime API Level: Android SDK ${Build.VERSION.SDK_INT} (minSdk 24, targetSdk 36)\n" +
                        "• Coordinator Actions Executed: ${uiState.executedActionCount}\n" +
                        "• Duplicate Actions Rejected: ${uiState.duplicateRejectedCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                Text(
                    text = "ACCESSIBILITY FONT SCALE TESTER",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Preview layout resilience under accessibility font scaling (or adjust via Android OS Display Settings):",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val options = listOf(
                        null to "System",
                        1.0f to "1.0x",
                        1.25f to "1.25x",
                        1.5f to "1.5x"
                    )
                    options.forEach { (scale, label) ->
                        FilterChip(
                            selected = fontScaleOverride == scale,
                            onClick = { onSelectFontScale(scale) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("font_scale_chip_${label.lowercase()}")
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("settings_close_button")
            ) {
                Text("Close")
            }
        }
    )
}
