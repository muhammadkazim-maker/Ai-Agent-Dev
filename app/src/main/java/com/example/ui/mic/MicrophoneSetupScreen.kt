package com.example.ui.mic

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.audio.AudioAnomaly
import com.example.audio.AudioCaptureState
import com.example.audio.MicPermissionState
import com.example.coordinator.ConversationUiState

/**
 * Phase 02 Microphone Setup & Local Capture Self-Test Screen.
 *
 * Provides:
 * 1. Explicit rationale explaining why RECORD_AUDIO is needed.
 * 2. Permission request button + refusal/permanent-denial handling + Open System Settings button.
 * 3. Live local capture self-test displaying actual linear/dBFS RMS, captured frame count,
 *    active CaptureGenerationId, hardware/resampled sample rate, and stale callback counter.
 * 4. Temporary recording/playback check with retention OFF and immediate deletion after playback
 *    to verify intelligible sound beyond a moving meter.
 * 5. Large Stop button that immediately halts capture/playback and releases hardware.
 */
@Composable
fun MicrophoneSetupScreen(
    uiState: ConversationUiState,
    audioState: AudioCaptureState,
    onRequestMicPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onStartSelfTest: () -> Unit,
    onStopSelfTest: () -> Unit,
    onPlayAndDeleteEphemeral: () -> Unit,
    onWipeEphemeral: () -> Unit,
    onToggleEphemeralArmed: (Boolean) -> Unit,
    onLargeStopAll: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        onNavigateBack()
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("microphone_setup_screen")
    ) {
        val isLandscape = maxWidth >= 600.dp || maxWidth > maxHeight

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MicSetupTopBar(
                audioState = audioState,
                onNavigateBack = onNavigateBack
            )

            if (isLandscape) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("mic_setup_landscape_container"),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(0.52f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        PermissionAndRationaleCard(
                            audioState = audioState,
                            onRequestMicPermission = onRequestMicPermission,
                            onOpenAppSettings = onOpenAppSettings
                        )
                        LiveCaptureMetricsCard(audioState = audioState)
                    }

                    Column(
                        modifier = Modifier
                            .weight(0.48f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SelfTestAndEphemeralControlsCard(
                            audioState = audioState,
                            onStartSelfTest = onStartSelfTest,
                            onStopSelfTest = onStopSelfTest,
                            onPlayAndDeleteEphemeral = onPlayAndDeleteEphemeral,
                            onWipeEphemeral = onWipeEphemeral,
                            onToggleEphemeralArmed = onToggleEphemeralArmed,
                            onLargeStopAll = onLargeStopAll
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .testTag("mic_setup_portrait_container"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PermissionAndRationaleCard(
                        audioState = audioState,
                        onRequestMicPermission = onRequestMicPermission,
                        onOpenAppSettings = onOpenAppSettings
                    )
                    LiveCaptureMetricsCard(audioState = audioState)
                    SelfTestAndEphemeralControlsCard(
                        audioState = audioState,
                        onStartSelfTest = onStartSelfTest,
                        onStopSelfTest = onStopSelfTest,
                        onPlayAndDeleteEphemeral = onPlayAndDeleteEphemeral,
                        onWipeEphemeral = onWipeEphemeral,
                        onToggleEphemeralArmed = onToggleEphemeralArmed,
                        onLargeStopAll = onLargeStopAll
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MicSetupTopBar(
    audioState: AudioCaptureState,
    onNavigateBack: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("mic_setup_top_bar"),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("mic_setup_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Return to Conversation Shell and release hardware",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.mic_setup_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "OWNER: ${audioState.owner.wireLabel} • GEN: ${audioState.activeGeneration?.wireId ?: "NONE"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("mic_owner_gen_label")
                        )
                    }
                }

                PermissionStateBadge(permissionState = audioState.permissionState)
            }

            // Explicit honesty boundary notice
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("mic_honesty_boundary_banner"),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.mic_honest_disclaimer),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionStateBadge(permissionState: MicPermissionState) {
    val (bg, border, fg) = when (permissionState) {
        MicPermissionState.GRANTED -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        MicPermissionState.REFUSED_CAN_RETRY -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.secondary,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
        MicPermissionState.PERMANENTLY_DENIED -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.onErrorContainer
        )
        MicPermissionState.NOT_REQUESTED -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.outline,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Surface(
        modifier = Modifier.testTag("mic_permission_badge"),
        color = bg,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, border)
    ) {
        Text(
            text = permissionState.wireLabel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = fg,
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 5.dp)
                .testTag("mic_permission_status_text")
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionAndRationaleCard(
    audioState: AudioCaptureState,
    onRequestMicPermission: () -> Unit,
    onOpenAppSettings: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("permission_rationale_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
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
                    imageVector = if (audioState.permissionState == MicPermissionState.GRANTED) {
                        Icons.Outlined.CheckCircle
                    } else {
                        Icons.Outlined.Lock
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.mic_rationale_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Text(
                text = stringResource(R.string.mic_rationale_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("mic_rationale_explanation_text")
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("permission_guidance_box"),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = audioState.permissionState.userSummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(10.dp)
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (audioState.permissionState != MicPermissionState.GRANTED) {
                    Button(
                        onClick = onRequestMicPermission,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .minimumInteractiveComponentSize()
                            .testTag("request_mic_permission_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (audioState.permissionState == MicPermissionState.REFUSED_CAN_RETRY) {
                                "Retry RECORD_AUDIO Permission"
                            } else {
                                "Grant RECORD_AUDIO Permission"
                            },
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                OutlinedButton(
                    onClick = onOpenAppSettings,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .minimumInteractiveComponentSize()
                        .testTag("open_app_settings_button"),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(
                        1.dp,
                        if (audioState.permissionState == MicPermissionState.PERMANENTLY_DENIED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.outline
                        }
                    )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Open System App Settings",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveCaptureMetricsCard(audioState: AudioCaptureState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("live_capture_metrics_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "LIVE CAPTURE TELEMETRY (16 kHz PCM-16 MONO)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Text(
                    text = if (audioState.isCapturing) "CAPTURING" else "RELEASED",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (audioState.isCapturing) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.testTag("capture_active_status_text")
                )
            }

            // Actual RMS Meter + Numerical Readout
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "ACTUAL RMS: ${"%.4f".format(audioState.currentRmsLinear)} (${"%.1f".format(audioState.currentRmsDbfs)} dBFS)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("rms_value_text")
                    )
                    Text(
                        text = "PEAK: ${"%.4f".format(audioState.peakRmsLinear)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("peak_rms_text")
                    )
                }

                LinearProgressIndicator(
                    progress = { (audioState.currentRmsLinear * 3.0f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .testTag("rms_progress_bar"),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MetricPill(
                    label = "FRAMES: ${audioState.capturedFrameCount}",
                    tag = "captured_frames_text"
                )
                MetricPill(
                    label = "SAMPLES: ${audioState.capturedSampleCount} (${audioState.capturedDurationMs}ms)",
                    tag = "captured_samples_text"
                )
                MetricPill(
                    label = "FORMAT: ${audioState.hardwareSampleRateHz}Hz -> ${audioState.targetSampleRateHz}Hz",
                    tag = "sample_rate_text"
                )
                MetricPill(
                    label = "STALE DROPPED: ${audioState.staleCallbacksDropped}",
                    tag = "stale_callbacks_text"
                )
                MetricPill(
                    label = "RELEASES: ${audioState.hardwareReleaseCount}",
                    tag = "hardware_release_count_text"
                )
            }

            // Anomaly / Diagnostic Banner
            val hasAnomaly = audioState.anomaly != AudioAnomaly.NONE
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("anomaly_status_banner"),
                color = if (hasAnomaly) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(
                    1.dp,
                    if (hasAnomaly) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                )
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (hasAnomaly) {
                        Icon(
                            imageVector = Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ANOMALY CHECK: ${audioState.anomaly.wireCode}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (hasAnomaly) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.testTag("anomaly_status_text")
                        )
                        Text(
                            text = audioState.statusDetail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (hasAnomaly) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricPill(label: String, tag: String) {
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelfTestAndEphemeralControlsCard(
    audioState: AudioCaptureState,
    onStartSelfTest: () -> Unit,
    onStopSelfTest: () -> Unit,
    onPlayAndDeleteEphemeral: () -> Unit,
    onWipeEphemeral: () -> Unit,
    onToggleEphemeralArmed: (Boolean) -> Unit,
    onLargeStopAll: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("self_test_controls_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "LOCAL CAPTURE SELF-TEST & INTELLIGIBILITY CHECK",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "1. Start Capture to verify real 16kHz PCM frame count & RMS.\n" +
                    "2. Stop Capture to immediately release AudioRecord.\n" +
                    "3. Because a moving meter alone is not proof of intelligible sound, press 'Play & Delete Ephemeral Check' to hear the temporary clip and automatically wipe it (Retention: OFF).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Start & Stop Self-Test Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onStartSelfTest,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp)
                        .minimumInteractiveComponentSize()
                        .testTag("start_self_test_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Start Self-Test",
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                Button(
                    onClick = onStopSelfTest,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp)
                        .minimumInteractiveComponentSize()
                        .testTag("stop_self_test_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Stop & Release",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            // Ephemeral Recording / Playback Check (Retention OFF)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "EPHEMERAL RECORD/PLAYBACK CHECK (RETENTION: OFF)",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                FilterChip(
                    selected = audioState.ephemeralCheckArmed,
                    onClick = { onToggleEphemeralArmed(!audioState.ephemeralCheckArmed) },
                    label = {
                        Text(
                            if (audioState.ephemeralCheckArmed) "Buffer Armed (Max 5s)" else "Buffer Disarmed",
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    modifier = Modifier.testTag("ephemeral_armed_chip")
                )
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ephemeral_status_box"),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = audioState.ephemeralLastWipedStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("ephemeral_wipe_status_text")
                    )
                    Text(
                        text = "Buffered Samples: ${audioState.ephemeralRecordedSamples} (${audioState.ephemeralDurationMs}ms) • Playbacks Verified: ${audioState.ephemeralPlaybackCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("ephemeral_samples_count_text")
                    )
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPlayAndDeleteEphemeral,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .minimumInteractiveComponentSize()
                        .testTag("play_ephemeral_check_button"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Play & Delete Ephemeral Check",
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                OutlinedButton(
                    onClick = onWipeEphemeral,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .minimumInteractiveComponentSize()
                        .testTag("wipe_ephemeral_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteForever,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Delete Buffer Now",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            // Large Stop Control (Highest Precedence — Prompt Hardware Release)
            Button(
                onClick = onLargeStopAll,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("mic_setup_large_stop_button"),
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
                    text = "STOP (IMMEDIATE HARDWARE RELEASE & WIPE)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
