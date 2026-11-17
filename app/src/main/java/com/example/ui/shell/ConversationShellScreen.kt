package com.example.ui.shell

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MicNone
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.R
import com.example.audio.MicPermissionState
import com.example.contract.ActiveConversationPhase
import com.example.contract.ConversationState
import com.example.contract.InactivityTimeoutOption
import com.example.contract.TranscriptEntry
import com.example.coordinator.ConversationUiState
import com.example.ui.live.LiveDiagnosticsScreen
import com.example.ui.mic.MicrophoneSetupScreen

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
fun ConversationShellRoute(
    viewModel: ConversationShellViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val audioState by viewModel.audioState.collectAsStateWithLifecycle()
    val liveState by viewModel.liveState.collectAsStateWithLifecycle()
    val activeScreen by viewModel.activeScreen.collectAsStateWithLifecycle()
    val isSettingsOpen by viewModel.isSettingsOpen.collectAsStateWithLifecycle()
    val fontScaleOverride by viewModel.fontScaleOverride.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var pendingStartAfterPermissionGrant by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val activity = context.findActivity()
        val shouldShowRationale = activity?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(
                it,
                Manifest.permission.RECORD_AUDIO
            )
        } ?: false
        viewModel.onPermissionRequestResult(
            granted = isGranted,
            shouldShowRationale = shouldShowRationale
        )
        if (pendingStartAfterPermissionGrant && isGranted) {
            pendingStartAfterPermissionGrant = false
            viewModel.onStartClicked()
        } else {
            pendingStartAfterPermissionGrant = false
        }
    }

    // Re-check RECORD_AUDIO permission on ON_RESUME (after returning from Settings),
    // trigger screen-reopen auto-resume if configured (never overriding Pause or Stop),
    // and release hardware on ON_STOP when not rotating.
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            val activity = context.findActivity()
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    val rationale = activity?.let {
                        ActivityCompat.shouldShowRequestPermissionRationale(
                            it,
                            Manifest.permission.RECORD_AUDIO
                        )
                    } ?: false
                    // Only overwrite if OS explicitly reports granted, or if we previously requested
                    if (granted || viewModel.hasRequestedPermissionOnce.value) {
                        viewModel.onReCheckPermissionFromOs(
                            isGranted = granted,
                            shouldShowRationale = rationale
                        )
                    }
                    viewModel.onConversationScreenVisible()
                }
                Lifecycle.Event.ON_STOP -> {
                    val changingConfigs = activity?.isChangingConfigurations == true
                    viewModel.onActivityStopped(isChangingConfigurations = changingConfigs)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val openSystemAppSettings: () -> Unit = {
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Throwable) {
        }
    }

    val handleMainStartClick: () -> Unit = {
        val osGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (osGranted && viewModel.audioState.value.permissionState != MicPermissionState.GRANTED) {
            viewModel.onReCheckPermissionFromOs(isGranted = true, shouldShowRationale = false)
        }
        val permBeforeStart = viewModel.audioState.value.permissionState
        viewModel.onStartClicked()
        if (permBeforeStart == MicPermissionState.NOT_REQUESTED && !osGranted) {
            pendingStartAfterPermissionGrant = true
            try {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } catch (_: Throwable) {
                pendingStartAfterPermissionGrant = false
            }
        }
    }

    val baseDensity = LocalDensity.current
    val effectiveDensity = if (fontScaleOverride != null) {
        Density(density = baseDensity.density, fontScale = fontScaleOverride!!)
    } else {
        baseDensity
    }

    val copyDiagnosticsToClipboard: () -> String = {
        val redactedSnapshot = viewModel.getRedactedDiagnosticsSnapshot()
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(
                ClipData.newPlainText("OmniVoice Redacted Diagnostics", redactedSnapshot)
            )
        } catch (_: Throwable) {
        }
        redactedSnapshot
    }

    CompositionLocalProvider(LocalDensity provides effectiveDensity) {
        when (activeScreen) {
            ActiveScreen.MICROPHONE_SETUP -> {
                MicrophoneSetupScreen(
                    uiState = uiState,
                    audioState = audioState,
                    onRequestMicPermission = {
                        try {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } catch (_: Throwable) {
                            viewModel.onPermissionRequestResult(granted = false, shouldShowRationale = true)
                        }
                    },
                    onOpenAppSettings = openSystemAppSettings,
                    onStartSelfTest = { viewModel.onStartMicSelfTestClicked() },
                    onStopSelfTest = { viewModel.onStopMicSelfTestClicked() },
                    onPlayAndDeleteEphemeral = { viewModel.onPlayAndDeleteEphemeralCheckClicked() },
                    onWipeEphemeral = viewModel::onWipeEphemeralAudioClicked,
                    onToggleEphemeralArmed = viewModel::onSetEphemeralCheckArmed,
                    onLargeStopAll = { viewModel.onStopClicked() },
                    onNavigateBack = viewModel::onNavigateBackFromMicSetup,
                    modifier = modifier
                )
            }

            ActiveScreen.LIVE_DIAGNOSTICS -> {
                LiveDiagnosticsScreen(
                    uiState = uiState,
                    liveState = liveState,
                    onConnectLiveHandshake = viewModel::onConnectLiveHandshakeClicked,
                    onDisconnectLiveSession = { viewModel.onDisconnectLiveSessionClicked() },
                    onLargeStopAll = { viewModel.onStopClicked() },
                    onNavigateBack = viewModel::onNavigateBackFromLiveDiagnostics,
                    onCopyDiagnostics = { copyDiagnosticsToClipboard() },
                    onScreenActive = viewModel::onLiveDiagnosticsScreenActive,
                    onScreenDisposed = viewModel::onLiveDiagnosticsScreenDisposed,
                    modifier = modifier
                )
            }

            ActiveScreen.CONVERSATION_SHELL -> {
                ConversationShellScreen(
                    uiState = uiState,
                    isSettingsOpen = isSettingsOpen,
                    fontScaleOverride = fontScaleOverride,
                    onStartClick = handleMainStartClick,
                    onResumeClick = viewModel::onResumeClicked,
                    onFinishSpokenTurnClick = viewModel::onFinishSpokenTurnClicked,
                    onPauseClick = { viewModel.onPauseClicked() },
                    onStopClick = { viewModel.onStopClicked() },
                    onResetIdleClick = { viewModel.onResetIdleClicked() },
                    onVerifyPrecedenceClick = { viewModel.onVerifyStopPausePrecedenceClicked() },
                    onToggleActiveConversationMode = viewModel::onToggleActiveConversationMode,
                    onToggleAutoResumeOnReopen = viewModel::onToggleAutoResumeOnReopen,
                    onSelectInactivityTimeout = viewModel::onSelectInactivityTimeout,
                    onOpenMicSetup = viewModel::onOpenMicSetup,
                    onOpenLiveDiagnostics = viewModel::onOpenLiveDiagnostics,
                    onCopyDiagnostics = copyDiagnosticsToClipboard,
                    onOpenSettings = viewModel::onOpenSettings,
                    onDismissSettings = viewModel::onDismissSettings,
                    onSelectFontScale = viewModel::onSetFontScaleOverride,
                    modifier = modifier
                )
            }
        }
    }
}

@Composable
fun ConversationShellScreen(
    uiState: ConversationUiState,
    isSettingsOpen: Boolean,
    fontScaleOverride: Float?,
    onStartClick: () -> Unit,
    onResumeClick: () -> Unit = {},
    onFinishSpokenTurnClick: () -> Unit = {},
    onPauseClick: () -> Unit,
    onStopClick: () -> Unit,
    onResetIdleClick: () -> Unit,
    onVerifyPrecedenceClick: () -> Unit,
    onToggleActiveConversationMode: (Boolean) -> Unit = {},
    onToggleAutoResumeOnReopen: (Boolean) -> Unit = {},
    onSelectInactivityTimeout: (InactivityTimeoutOption) -> Unit = {},
    onOpenMicSetup: () -> Unit = {},
    onOpenLiveDiagnostics: () -> Unit = {},
    onCopyDiagnostics: () -> String = { "" },
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
            val isLandscapeLayout = maxWidth >= 600.dp || maxWidth > maxHeight

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TopTelemetryHeader(
                    uiState = uiState,
                    isLandscapeLayout = isLandscapeLayout,
                    onOpenMicSetup = onOpenMicSetup,
                    onOpenLiveDiagnostics = onOpenLiveDiagnostics,
                    onCopyDiagnostics = { onCopyDiagnostics() },
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
                        Column(
                            modifier = Modifier
                                .weight(0.58f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            VoiceTurnPipelineCard(
                                uiState = uiState,
                                onFinishSpokenTurnClick = onFinishSpokenTurnClick,
                                modifier = Modifier.fillMaxWidth()
                            )
                            TranscriptAndTelemetryPane(
                                uiState = uiState,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            )
                        }
                        CommandDeckPane(
                            uiState = uiState,
                            onStartClick = onStartClick,
                            onResumeClick = onResumeClick,
                            onFinishSpokenTurnClick = onFinishSpokenTurnClick,
                            onPauseClick = onPauseClick,
                            onStopClick = onStopClick,
                            onResetIdleClick = onResetIdleClick,
                            onVerifyPrecedenceClick = onVerifyPrecedenceClick,
                            onToggleActiveConversationMode = onToggleActiveConversationMode,
                            onToggleAutoResumeOnReopen = onToggleAutoResumeOnReopen,
                            onSelectInactivityTimeout = onSelectInactivityTimeout,
                            onOpenMicSetup = onOpenMicSetup,
                            onOpenLiveDiagnostics = onOpenLiveDiagnostics,
                            modifier = Modifier
                                .weight(0.42f)
                                .fillMaxHeight()
                        )
                    }
                } else {
                    // Portrait mode: usable, dense cyber layout with no unexplained empty box
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .testTag("portrait_fallback_container"),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        VoiceTurnPipelineCard(
                            uiState = uiState,
                            onFinishSpokenTurnClick = onFinishSpokenTurnClick,
                            modifier = Modifier.fillMaxWidth()
                        )
                        CommandDeckPane(
                            uiState = uiState,
                            onStartClick = onStartClick,
                            onResumeClick = onResumeClick,
                            onFinishSpokenTurnClick = onFinishSpokenTurnClick,
                            onPauseClick = onPauseClick,
                            onStopClick = onStopClick,
                            onResetIdleClick = onResetIdleClick,
                            onVerifyPrecedenceClick = onVerifyPrecedenceClick,
                            onToggleActiveConversationMode = onToggleActiveConversationMode,
                            onToggleAutoResumeOnReopen = onToggleAutoResumeOnReopen,
                            onSelectInactivityTimeout = onSelectInactivityTimeout,
                            onOpenMicSetup = onOpenMicSetup,
                            onOpenLiveDiagnostics = onOpenLiveDiagnostics,
                            isPortraitEmbedded = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        TranscriptAndTelemetryPane(
                            uiState = uiState,
                            isPortraitEmbedded = true,
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
                onToggleActiveConversationMode = onToggleActiveConversationMode,
                onToggleAutoResumeOnReopen = onToggleAutoResumeOnReopen,
                onSelectInactivityTimeout = onSelectInactivityTimeout,
                onOpenMicSetup = {
                    onDismissSettings()
                    onOpenMicSetup()
                },
                onOpenLiveDiagnostics = {
                    onDismissSettings()
                    onOpenLiveDiagnostics()
                },
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
    onOpenMicSetup: () -> Unit,
    onOpenLiveDiagnostics: () -> Unit,
    onCopyDiagnostics: () -> Unit,
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
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Full-width title & phase description so words stay intact and never wrap character-by-character
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("top_header_title_column"),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("app_title_text")
                )
                Text(
                    text = "${stringResource(R.string.shell_subtitle)} • ${if (isLandscapeLayout) "LANDSCAPE DECK" else "PORTRAIT DECK"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("app_subtitle_text")
                )
            }

            // Separate responsive row for state badge and navigation/action icons
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("top_header_status_action_row"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(modifier = Modifier.align(Alignment.CenterVertically)) {
                    RealStateBadge(state = uiState.state)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedButton(
                        onClick = onCopyDiagnostics,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .semantics {
                                contentDescription = "Copy redacted Phase 07A diagnostic snapshot to clipboard"
                            }
                            .testTag("copy_diag_button")
                    ) {
                        Text(
                            text = stringResource(R.string.action_copy_diag),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(
                        onClick = onOpenLiveDiagnostics,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("top_live_diagnostics_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CloudSync,
                            contentDescription = stringResource(R.string.cd_live_diag_button),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    IconButton(
                        onClick = onOpenMicSetup,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("top_mic_setup_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = stringResource(R.string.cd_mic_setup_button),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

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
                TelemetryChip(
                    label = "LIVE: ${uiState.liveConnectionState.phase.wireCode} (${uiState.liveConnectionState.configuredModelId})",
                    tag = "live_status_indicator"
                )
                HonestMicrophoneBadge(
                    label = uiState.microphoneStatusLabel,
                    isCapturing = uiState.isMicrophoneActive
                )
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
        ConversationState.PREPARING,
        ConversationState.CONNECTING,
        ConversationState.THINKING,
        ConversationState.PROCESSING -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        ConversationState.LISTENING,
        ConversationState.SPEAKING,
        ConversationState.READY -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        ConversationState.ERROR,
        ConversationState.STOPPED -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.onErrorContainer
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
        modifier = Modifier
            .testTag(tag)
            .semantics(mergeDescendants = true) {},
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
private fun HonestMicrophoneBadge(
    label: String,
    isCapturing: Boolean
) {
    Surface(
        modifier = Modifier.testTag("mic_honest_indicator"),
        color = if (isCapturing) {
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
        },
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            1.dp,
            if (isCapturing) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (isCapturing) Icons.Outlined.MicNone else Icons.Outlined.MicOff,
                contentDescription = stringResource(R.string.mic_status_honest),
                modifier = Modifier.size(14.dp),
                tint = if (isCapturing) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (isCapturing) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                }
            )
        }
    }
}

/**
 * Phase 04/05 Voice Turn Pipeline, Active Conversation State Machine & Streaming Audio Telemetry Card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceTurnPipelineCard(
    uiState: ConversationUiState,
    onFinishSpokenTurnClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pipelineStages = listOf(
        ConversationState.CONNECTING,
        ConversationState.LISTENING,
        ConversationState.PROCESSING,
        ConversationState.SPEAKING,
        ConversationState.READY
    )
    val activeConversationStages = listOf(
        ActiveConversationPhase.PREPARING,
        ActiveConversationPhase.LISTENING,
        ActiveConversationPhase.THINKING,
        ActiveConversationPhase.SPEAKING,
        ActiveConversationPhase.PAUSED,
        ActiveConversationPhase.ERROR
    )
    val playSnap = uiState.playbackState

    Card(
        modifier = modifier.testTag("voice_turn_pipeline_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "PHASE 05 ACTIVE CONVERSATION & AUDIO PIPELINE",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("pipeline_card_header_text")
                )
                Text(
                    text = if (playSnap.isPlaybackDrained) {
                        "PLAYBACK: DRAINED"
                    } else if (playSnap.isPlaying) {
                        "PLAYBACK: STREAMING (${playSnap.verifiedSampleRateHz}Hz)"
                    } else {
                        "PLAYBACK: IDLE"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (playSnap.isPlaybackDrained || playSnap.isPlaying) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.testTag("playback_drain_status_text")
                )
            }

            // Phase 05 Active Conversation State Machine Row: Preparing, Listening, Thinking, Speaking, Paused, Error
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("active_conversation_phase_row"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                activeConversationStages.forEach { phase ->
                    val isCurrent = uiState.activeConversationPhase == phase
                    val isVisited = uiState.activeConversationPhaseSequence.contains(phase)
                    val chipBg = when {
                        isCurrent -> MaterialTheme.colorScheme.primaryContainer
                        isVisited -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    val chipBorder = when {
                        isCurrent -> MaterialTheme.colorScheme.primary
                        isVisited -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.outline
                    }
                    val chipText = when {
                        isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
                        isVisited -> MaterialTheme.colorScheme.onTertiaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Surface(
                        color = chipBg,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(if (isCurrent) 1.5.dp else 1.dp, chipBorder),
                        modifier = Modifier.testTag("active_phase_${phase.wireLabel.lowercase()}")
                    ) {
                        Text(
                            text = phase.wireLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isCurrent || isVisited) FontWeight.Bold else FontWeight.Normal,
                            color = chipText,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // 5-stage visual pipeline: CONNECTING -> LISTENING -> PROCESSING -> SPEAKING -> READY
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("voice_turn_stage_row"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                pipelineStages.forEach { stage ->
                    val isCurrent = uiState.state == stage
                    val isVisited = uiState.voiceTurnStateSequence.contains(stage)
                    val chipBg = when {
                        isCurrent -> MaterialTheme.colorScheme.primaryContainer
                        isVisited -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    val chipBorder = when {
                        isCurrent -> MaterialTheme.colorScheme.primary
                        isVisited -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.outline
                    }
                    val chipText = when {
                        isCurrent -> MaterialTheme.colorScheme.onPrimaryContainer
                        isVisited -> MaterialTheme.colorScheme.onTertiaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Surface(
                        color = chipBg,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(if (isCurrent) 1.5.dp else 1.dp, chipBorder),
                        modifier = Modifier.testTag("pipeline_stage_${stage.wireLabel.lowercase()}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (isVisited && !isCurrent) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = chipBorder,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                            Text(
                                text = stage.wireLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isCurrent || isVisited) FontWeight.Bold else FontWeight.Normal,
                                color = chipText
                            )
                        }
                    }
                }
            }

            // State summary banner
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("state_summary_banner"),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = if (uiState.state == ConversationState.SPEAKING || uiState.state == ConversationState.LISTENING) {
                            Icons.Filled.GraphicEq
                        } else {
                            Icons.Outlined.Info
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "STATE: ${uiState.state.wireLabel} (PHASE: ${uiState.activeConversationPhase.wireLabel})",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = uiState.lastVoiceTurnSummary.ifBlank { uiState.state.summary },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Honest Prerequisite / Runtime Error Banner when an error occurs
            if (uiState.activeVoiceTurnErrorCode != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("voice_turn_error_banner"),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "ERROR CODE: ${uiState.activeVoiceTurnErrorCode}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.testTag("voice_turn_error_code_text")
                            )
                            Text(
                                text = uiState.activeVoiceTurnErrorDetail ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // Live Mic, AudioTrack, Half-Duplex & Language Policy Telemetry Chips
            val displayedMicRms = maxOf(
                uiState.voiceTurnPeakRmsLinear,
                uiState.audioCaptureState.currentRmsLinear,
                uiState.audioCaptureState.peakRmsLinear
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TelemetryChip(
                    label = "MIC SENT: ${uiState.voiceTurnFramesSent} frames (${uiState.voiceTurnBytesSent} B) • RMS ${"%.3f".format(displayedMicRms)}",
                    tag = "voice_turn_mic_metrics"
                )
                TelemetryChip(
                    label = "AUDIOTRACK: played=${playSnap.hardwarePlaybackHeadFrames}/${playSnap.totalFramesWrittenToHardware} accepted (recv=${playSnap.framesReceivedFromServer}, q=${playSnap.queuedFrames}, pad=${playSnap.paddingFramesWrittenToHardware}, ${playSnap.chunksReceived} chunks • s=${playSnap.trackState}/p=${playSnap.playState})",
                    tag = "voice_turn_playback_metrics"
                )
                TelemetryChip(
                    label = "TURNS COMPLETED: ${uiState.completedTurnsInSession} • STALE DROPPED: ${uiState.staleTurnCallbacksDropped}",
                    tag = "active_conversation_turns_chip"
                )
                val vadModelStatus = when {
                    uiState.vadModelLoaded -> "LOADED"
                    !uiState.vadModelLoadError.isNullOrBlank() -> "LOAD_FAILED"
                    else -> "NOT_LOADED"
                }
                val vadDecisionLabel = if (uiState.vadSpeechDecision) "SPEECH" else "NOISE"
                TelemetryChip(
                    label = "VAD: ${uiState.vadDetectorName} v${uiState.vadDetectorVersion} ($vadModelStatus) • $vadDecisionLabel p=${"%.2f".format(uiState.vadSpeechProbability)}",
                    tag = "speech_vad_detector_chip"
                )
                TelemetryChip(
                    label = "GATE: ${uiState.speechGateState.wireCode} • cand=${uiState.consecutiveSpeechDurationMs}ms (${uiState.vadCandidateConfirmationCount}w) • actStart=${uiState.activityStartCount}",
                    tag = "speech_vad_gate_chip"
                )
                TelemetryChip(
                    label = uiState.duplexPolicyLabel,
                    tag = "duplex_policy_chip"
                )
            }

            if (uiState.currentTurnUserTranscript.isNotBlank() || uiState.currentTurnModelTranscript.isNotBlank()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("voice_turn_live_transcript_box"),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (uiState.currentTurnUserTranscript.isNotBlank()) {
                            Text(
                                text = "YOU: ${uiState.currentTurnUserTranscript}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("voice_turn_user_transcript_text")
                            )
                        }
                        if (uiState.currentTurnModelTranscript.isNotBlank()) {
                            Text(
                                text = "OMNIVOICE: ${uiState.currentTurnModelTranscript}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("voice_turn_model_transcript_text")
                            )
                        }
                    }
                }
            }

            if (uiState.sessionDialogueHistory.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("session_dialogue_history_box"),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "BOUNDED SESSION DIALOGUE (${uiState.sessionDialogueHistory.size}/${uiState.maxSessionDialogueTurns} TURNS)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        uiState.sessionDialogueHistory.takeLast(3).forEach { dTurn ->
                            Text(
                                text = "#${dTurn.turnNumberInSession} (${dTurn.turnId.value}): ${dTurn.userTranscript} → ${dTurn.modelTranscript}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            if (uiState.state == ConversationState.LISTENING) {
                Button(
                    onClick = onFinishSpokenTurnClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .minimumInteractiveComponentSize()
                        .testTag("pipeline_end_spoken_turn_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.action_end_spoken_turn),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranscriptAndTelemetryPane(
    uiState: ConversationUiState,
    isPortraitEmbedded: Boolean = false,
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
                .fillMaxWidth()
                .then(if (isPortraitEmbedded) Modifier else Modifier.fillMaxSize())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.transcript_header),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "ENTRIES: ${uiState.transcript.size}/${uiState.maxTranscriptEntries}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isPortraitEmbedded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("transcript_list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    uiState.transcript.asReversed().take(8).forEach { entry ->
                        TranscriptEntryCard(entry = entry)
                    }
                }
            } else {
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
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommandDeckPane(
    uiState: ConversationUiState,
    onStartClick: () -> Unit,
    onResumeClick: () -> Unit,
    onFinishSpokenTurnClick: () -> Unit,
    onPauseClick: () -> Unit,
    onStopClick: () -> Unit,
    onResetIdleClick: () -> Unit,
    onVerifyPrecedenceClick: () -> Unit,
    onToggleActiveConversationMode: (Boolean) -> Unit,
    onToggleAutoResumeOnReopen: (Boolean) -> Unit,
    onSelectInactivityTimeout: (InactivityTimeoutOption) -> Unit,
    onOpenMicSetup: () -> Unit,
    onOpenLiveDiagnostics: () -> Unit,
    isPortraitEmbedded: Boolean = false,
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
                .then(if (isPortraitEmbedded) Modifier else Modifier.verticalScroll(rememberScrollState()))
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
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
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
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = stringResource(R.string.action_start),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (uiState.isActiveConversationEnabled) {
                            "Active Conversation (Auto-Restore Capture After Speech)"
                        } else {
                            "One-Turn Gemini Live Voice (CONNECTING → LISTENING → SPEAKING → READY)"
                        },
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            // End Spoken Turn Button (active during LISTENING to immediately finish spoken question)
            OutlinedButton(
                onClick = onFinishSpokenTurnClick,
                enabled = uiState.state == ConversationState.LISTENING,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("end_spoken_turn_button"),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                border = BorderStroke(
                    1.dp,
                    if (uiState.state == ConversationState.LISTENING) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.outline
                    }
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.GraphicEq,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.action_end_spoken_turn),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            // Large Pause, Resume & Stop Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.action_pause),
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                Button(
                    onClick = onResumeClick,
                    enabled = uiState.state != ConversationState.STOPPED && !uiState.isDeliberatelyStopped,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 64.dp)
                        .minimumInteractiveComponentSize()
                        .testTag("resume_button")
                        .semantics { contentDescription = "Resume paused conversation session" },
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.action_resume),
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
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.action_stop),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            // Phase 05 Active Conversation & Inactivity Session Closure Visible Controls
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("active_conversation_settings_card"),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "ACTIVE CONVERSATION & INACTIVITY SESSION CLOSURE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = uiState.isActiveConversationEnabled,
                            onClick = { onToggleActiveConversationMode(!uiState.isActiveConversationEnabled) },
                            label = {
                                Text(
                                    text = if (uiState.isActiveConversationEnabled) {
                                        "Auto-Continue Turns: ON"
                                    } else {
                                        "Auto-Continue Turns: OFF"
                                    },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            modifier = Modifier.testTag("active_conversation_mode_chip")
                        )

                        FilterChip(
                            selected = uiState.autoResumeOnScreenReopen,
                            onClick = { onToggleAutoResumeOnReopen(!uiState.autoResumeOnScreenReopen) },
                            label = {
                                Text(
                                    text = if (uiState.autoResumeOnScreenReopen) {
                                        "Auto-Resume on Reopen: ON"
                                    } else {
                                        "Auto-Resume on Reopen: OFF"
                                    },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            modifier = Modifier.testTag("auto_resume_reopen_chip")
                        )
                    }

                    Text(
                        text = "Inactivity Session Timeout (closes idle session; no arbitrary 6-turn limit):",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("inactivity_timeout_selector_row"),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        InactivityTimeoutOption.entries.forEach { option ->
                            FilterChip(
                                selected = uiState.inactivityTimeoutOption == option,
                                onClick = { onSelectInactivityTimeout(option) },
                                label = {
                                    Text(
                                        text = option.shortChipLabel,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                modifier = Modifier.testTag("inactivity_timeout_chip_${option.shortChipLabel.lowercase()}")
                            )
                        }
                    }
                }
            }

            // Phase 03 Gemini Live Connection & Diagnostics Screen Entry Button
            Button(
                onClick = onOpenLiveDiagnostics,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("open_live_diagnostics_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.CloudSync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.action_open_live_diagnostics),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            // Phase 02 Microphone Setup & Local Self-Test Entry Button
            Button(
                onClick = onOpenMicSetup,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("open_mic_setup_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.action_open_mic_setup),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

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
    onToggleActiveConversationMode: (Boolean) -> Unit,
    onToggleAutoResumeOnReopen: (Boolean) -> Unit,
    onSelectInactivityTimeout: (InactivityTimeoutOption) -> Unit,
    onOpenMicSetup: () -> Unit,
    onOpenLiveDiagnostics: () -> Unit,
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

                Button(
                    onClick = onOpenLiveDiagnostics,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("settings_open_live_diag_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CloudSync,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.action_open_live_diagnostics))
                }

                Button(
                    onClick = onOpenMicSetup,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("settings_open_mic_setup_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.action_open_mic_setup))
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                Text(
                    text = "PHASE 05 ACTIVE CONVERSATION PREFERENCES",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = uiState.isActiveConversationEnabled,
                        onClick = { onToggleActiveConversationMode(!uiState.isActiveConversationEnabled) },
                        label = {
                            Text(
                                text = "Auto-Continue Turns: ${if (uiState.isActiveConversationEnabled) "ON" else "OFF"}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        modifier = Modifier.testTag("settings_active_conversation_chip")
                    )

                    FilterChip(
                        selected = uiState.autoResumeOnScreenReopen,
                        onClick = { onToggleAutoResumeOnReopen(!uiState.autoResumeOnScreenReopen) },
                        label = {
                            Text(
                                text = "Auto-Resume on Screen Reopen: ${if (uiState.autoResumeOnScreenReopen) "ON" else "OFF"}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        modifier = Modifier.testTag("settings_auto_resume_reopen_chip")
                    )
                }

                Text(
                    text = "Inactivity Session Timeout (${uiState.inactivityTimeoutOption.shortChipLabel}):",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    InactivityTimeoutOption.entries.forEach { option ->
                        FilterChip(
                            selected = uiState.inactivityTimeoutOption == option,
                            onClick = { onSelectInactivityTimeout(option) },
                            label = { Text(option.shortChipLabel, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("settings_inactivity_chip_${option.shortChipLabel.lowercase()}")
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                Text(
                    text = "PHASE 05 ENVIRONMENT & CONTRACT TELEMETRY",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = "• Application ID: ${BuildConfig.APPLICATION_ID}\n" +
                        "• Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                        "• Source/Build ID: ${BuildConfig.SOURCE_BUILD_ID}\n" +
                        "• Live Model ID: ${uiState.liveConnectionState.configuredModelId} (${uiState.liveConnectionState.phase.wireCode})\n" +
                        "• Model Config: ${BuildConfig.MODEL_CONFIG_STATUS}\n" +
                        "• Duplex Policy: ${uiState.duplexPolicyLabel} (Barge-In Claimed: ${uiState.isSpokenBargeInClaimed})\n" +
                        "• Active Phase: ${uiState.activeConversationPhase.wireLabel} (Completed Turns: ${uiState.completedTurnsInSession})\n" +
                        "• Resource Releases Before Capture: ${uiState.resourceReleaseBeforeCaptureCount}\n" +
                        "• Inactivity Closures: ${uiState.inactivityClosedCount}\n" +
                        "• Live Session Gen: ${uiState.liveConnectionState.diagnostics.sessionGenerationWireId}\n" +
                        "• Audio Owner: ${uiState.audioCaptureState.owner.wireLabel} (Gen: ${uiState.audioCaptureState.activeGeneration?.wireId ?: "NONE"})\n" +
                        "• Playback Gen: ${uiState.playbackState.activeGeneration?.wireId ?: "NONE"} (Drained: ${uiState.playbackState.isPlaybackDrained}, State=${uiState.playbackState.trackState}, Play=${uiState.playbackState.playState}, Write=${uiState.playbackState.lastWriteErrorName}, Recreated=${uiState.playbackState.trackRecreationCount})\n" +
                        "• Mic Permission: ${uiState.audioCaptureState.permissionState.wireLabel}\n" +
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
