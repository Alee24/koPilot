package ke.co.kopilot.feature.soundstudio

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ke.co.kopilot.core.designsystem.theme.KoPilotClusterTokens
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Screen A: Engine Sound Studio (Main)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SoundStudioScreen(
    viewModel: SoundStudioViewModel,
    onBack: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenProfileDetails: (String) -> Unit,
    onOpenImport: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KoPilotClusterTokens.Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        StudioTopBar(
            title = "Engine Sound Studio",
            onBack = onBack,
            actions = {
                IconButton(
                    onClick = onOpenLibrary,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.LibraryMusic,
                        contentDescription = "Engine Sound Library",
                        tint = KoPilotClusterTokens.PrimaryText,
                    )
                }
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Audio Settings",
                        tint = KoPilotClusterTokens.PrimaryText,
                    )
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // OBD-II & Output Route Status Strip
            StudioCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    color = when {
                                        state.obdConnected && !state.isObdStale -> KoPilotClusterTokens.SmoothRoad
                                        state.obdConnected -> KoPilotClusterTokens.ModerateRoad
                                        else -> KoPilotClusterTokens.SecondaryText
                                    },
                                    shape = CircleShape,
                                ),
                        )
                        Column {
                            Text(
                                text = state.obdStatusLabel,
                                style = MaterialTheme.typography.titleSmall,
                                color = KoPilotClusterTokens.PrimaryText,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Output: ${state.audioOutputRoute} (${state.audioLatencyEstimateMs} ms)",
                                style = MaterialTheme.typography.bodySmall,
                                color = KoPilotClusterTokens.SecondaryText,
                            )
                        }
                    }

                    StatusBadge(
                        text = if (state.isRunning) {
                            if (state.isPreviewActive) "PREVIEW" else "ACTIVE"
                        } else {
                            "STANDBY"
                        },
                        color = if (state.isRunning) {
                            KoPilotClusterTokens.PrimaryAccent
                        } else {
                            KoPilotClusterTokens.SecondaryText
                        },
                    )
                }

                if (state.isDuckedOrPausedReason != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = state.isDuckedOrPausedReason.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = KoPilotClusterTokens.ModerateRoad,
                    )
                }
            }

            // Selected Profile Summary
            StudioCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.selectedProfile.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = KoPilotClusterTokens.PrimaryText,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${state.selectedProfile.configuration} • ${
                                if (state.selectedProfile.isCustomImported) "Custom WAV Pack" else "Synthesized Offline"
                            }",
                            style = MaterialTheme.typography.bodySmall,
                            color = KoPilotClusterTokens.SecondaryText,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = onOpenLibrary,
                        modifier = Modifier.height(48.dp),
                        border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
                    ) {
                        Text("Switch", color = KoPilotClusterTokens.PrimaryAccent)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Idle ${state.selectedProfile.idleRpm} RPM",
                        style = MaterialTheme.typography.labelSmall,
                        color = KoPilotClusterTokens.SecondaryText,
                    )
                    Text(
                        text = "Redline ${state.selectedProfile.redlineRpm} RPM",
                        style = MaterialTheme.typography.labelSmall,
                        color = KoPilotClusterTokens.SecondaryText,
                    )
                }
            }

            // Live RPM Gauge + Telemetry + Waveform
            StudioCard {
                val activeRpm = if (state.isPreviewActive) state.previewRpm.toFloat() else state.liveRpm
                val redline = state.selectedProfile.redlineRpm.toFloat().coerceAtLeast(3000f)

                RpmPointerGauge(
                    rpm = activeRpm,
                    maxRpm = (redline * 1.05f),
                    redlineRpm = redline,
                    isPreview = state.isPreviewActive,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(176.dp),
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    TelemetryStat(
                        label = "Throttle",
                        value = state.liveThrottlePct?.let { "${it.roundToInt()}%" } ?: "--",
                    )
                    TelemetryStat(
                        label = "Speed",
                        value = state.liveSpeedKmh?.let { "$it km/h" } ?: "--",
                    )
                    TelemetryStat(
                        label = "Load",
                        value = state.liveLoadPct?.let { "${it.roundToInt()}%" } ?: "--",
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                WaveformBars(
                    samples = state.waveformSnapshot,
                    active = state.isRunning && !state.isMuted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                )
            }

            // Primary Controls: Start/Stop + Mute + Parked Preview
            StudioCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = { viewModel.toggleEngineSound() },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.isRunning && !state.isPreviewActive) {
                                KoPilotClusterTokens.VeryRough
                            } else {
                                KoPilotClusterTokens.PrimaryAccent
                            },
                            contentColor = KoPilotClusterTokens.Background,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            imageVector = if (state.isRunning && !state.isPreviewActive) {
                                Icons.Default.Stop
                            } else {
                                Icons.Default.PlayArrow
                            },
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (state.isRunning && !state.isPreviewActive) "Stop Engine" else "Start Live OBD",
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    OutlinedButton(
                        onClick = { viewModel.toggleMute() },
                        modifier = Modifier.height(52.dp),
                        border = BorderStroke(
                            1.dp,
                            if (state.isMuted) KoPilotClusterTokens.ModerateRoad else KoPilotClusterTokens.Border,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            imageVector = if (state.isMuted) {
                                Icons.AutoMirrored.Filled.VolumeOff
                            } else {
                                Icons.AutoMirrored.Filled.VolumeUp
                            },
                            contentDescription = if (state.isMuted) "Unmute" else "Mute",
                            tint = if (state.isMuted) {
                                KoPilotClusterTokens.ModerateRoad
                            } else {
                                KoPilotClusterTokens.PrimaryText
                            },
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (state.isMuted) "Muted" else "Mute",
                            color = KoPilotClusterTokens.PrimaryText,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Parked Audition Preview",
                        style = MaterialTheme.typography.bodyMedium,
                        color = KoPilotClusterTokens.PrimaryText,
                    )
                    OutlinedButton(
                        onClick = {
                            if (state.isPreviewActive) {
                                viewModel.stopAllAudio()
                            } else {
                                viewModel.startParkedPreview(state.selectedProfile.idleRpm)
                            }
                        },
                        modifier = Modifier.height(48.dp),
                        border = BorderStroke(1.dp, KoPilotClusterTokens.SecondaryAccent),
                    ) {
                        Text(
                            text = if (state.isPreviewActive) "Stop Preview" else "Test Sound",
                            color = KoPilotClusterTokens.SecondaryAccent,
                        )
                    }
                }

                if (state.isPreviewActive) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Preview RPM: ${state.previewRpm} RPM",
                        style = MaterialTheme.typography.labelMedium,
                        color = KoPilotClusterTokens.PrimaryAccent,
                    )
                    Slider(
                        value = state.previewRpm.toFloat(),
                        onValueChange = { viewModel.setPreviewRpm(it.roundToInt()) },
                        valueRange = state.selectedProfile.idleRpm.toFloat()..state.selectedProfile.redlineRpm.toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = KoPilotClusterTokens.PrimaryAccent,
                            activeTrackColor = KoPilotClusterTokens.PrimaryAccent,
                        ),
                    )
                }
            }

            // Simulation Mode & Quick Tuning
            StudioCard {
                Text(
                    text = "Simulation Mode",
                    style = MaterialTheme.typography.titleSmall,
                    color = KoPilotClusterTokens.PrimaryText,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EngineSimulationMode.entries.forEach { mode ->
                        val selected = state.audioSettings.simulationMode == mode
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.setSimulationMode(mode) },
                            label = {
                                Text(
                                    text = mode.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = KoPilotClusterTokens.PrimaryAccent.copy(alpha = 0.18f),
                                selectedLabelColor = KoPilotClusterTokens.PrimaryAccent,
                                labelColor = KoPilotClusterTokens.SecondaryText,
                            ),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = state.audioSettings.simulationMode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = KoPilotClusterTokens.SecondaryText,
                )

                Spacer(modifier = Modifier.height(10.dp))
                LabeledSlider(
                    label = "Master Volume",
                    valueText = "${(state.audioSettings.masterVolume * 100).roundToInt()}%",
                    value = state.audioSettings.masterVolume,
                    onValueChange = { v -> viewModel.updateSettings { it.copy(masterVolume = v) } },
                )
                LabeledSlider(
                    label = "Sound Intensity",
                    valueText = "${(state.audioSettings.soundIntensity * 100).roundToInt()}%",
                    value = state.audioSettings.soundIntensity,
                    onValueChange = { v -> viewModel.updateSettings { it.copy(soundIntensity = v) } },
                )
            }

            // Quick Actions Footer
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onOpenLibrary,
                    modifier = Modifier.height(48.dp),
                    border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
                ) {
                    Icon(Icons.Default.LibraryMusic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Engine Library", color = KoPilotClusterTokens.PrimaryText)
                }
                OutlinedButton(
                    onClick = { onOpenProfileDetails(state.selectedProfile.id) },
                    modifier = Modifier.height(48.dp),
                    border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
                ) {
                    Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Profile Details", color = KoPilotClusterTokens.PrimaryText)
                }
                OutlinedButton(
                    onClick = onOpenImport,
                    modifier = Modifier.height(48.dp),
                    border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import WAV Pack", color = KoPilotClusterTokens.PrimaryText)
                }
                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.height(48.dp),
                    border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Audio Settings", color = KoPilotClusterTokens.PrimaryText)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Screen B: Engine Sound Library
// ---------------------------------------------------------------------------

private enum class LibraryFilter(val label: String) {
    ALL("All"),
    PETROL("Petrol"),
    PERFORMANCE("Performance"),
    DIESEL("Diesel"),
    CLASSIC("Classic"),
    CUSTOM("Custom"),
}

@Composable
fun SoundLibraryScreen(
    viewModel: SoundStudioViewModel,
    onBack: () -> Unit,
    onOpenProfileDetails: (String) -> Unit,
    onOpenImport: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(LibraryFilter.ALL) }

    val filteredProfiles = remember(state.profiles, searchQuery, activeFilter) {
        state.profiles.filter { profile ->
            val matchesQuery = searchQuery.isBlank() ||
                profile.name.contains(searchQuery, ignoreCase = true) ||
                profile.configuration.contains(searchQuery, ignoreCase = true) ||
                profile.category.displayName.contains(searchQuery, ignoreCase = true)
            val matchesFilter = when (activeFilter) {
                LibraryFilter.ALL -> true
                LibraryFilter.PETROL -> profile.category in setOf(
                    EngineCategory.FOUR_CYL_PETROL,
                    EngineCategory.INLINE_SIX_SPORTS,
                    EngineCategory.V6_PERFORMANCE,
                )
                LibraryFilter.PERFORMANCE -> profile.category in setOf(
                    EngineCategory.V8_MUSCLE,
                    EngineCategory.V10_SUPERCAR,
                    EngineCategory.V12_SUPERCAR,
                    EngineCategory.TURBO_PERFORMANCE,
                )
                LibraryFilter.DIESEL -> profile.category.isDiesel
                LibraryFilter.CLASSIC -> profile.category == EngineCategory.CLASSIC_ENGINE
                LibraryFilter.CUSTOM -> profile.isCustomImported
            }
            matchesQuery && matchesFilter
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KoPilotClusterTokens.Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        StudioTopBar(
            title = "Engine Sound Library",
            onBack = onBack,
            actions = {
                IconButton(
                    onClick = onOpenImport,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Import Custom Sound Pack",
                        tint = KoPilotClusterTokens.PrimaryAccent,
                    )
                }
            },
        )

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search engine profiles...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = KoPilotClusterTokens.PrimaryAccent,
                    unfocusedBorderColor = KoPilotClusterTokens.Border,
                    focusedTextColor = KoPilotClusterTokens.PrimaryText,
                    unfocusedTextColor = KoPilotClusterTokens.PrimaryText,
                ),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LibraryFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = activeFilter == filter,
                        onClick = { activeFilter = filter },
                        label = { Text(filter.label) },
                        modifier = Modifier.height(48.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = KoPilotClusterTokens.PrimaryAccent.copy(alpha = 0.18f),
                            selectedLabelColor = KoPilotClusterTokens.PrimaryAccent,
                            labelColor = KoPilotClusterTokens.SecondaryText,
                        ),
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(filteredProfiles, key = { it.id }) { profile ->
                val isSelected = profile.id == state.selectedProfile.id
                StudioCard(
                    borderColor = if (isSelected) KoPilotClusterTokens.PrimaryAccent else KoPilotClusterTokens.Border,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = profile.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = KoPilotClusterTokens.PrimaryText,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "${profile.configuration} • ${profile.idleRpm}-${profile.redlineRpm} RPM",
                                style = MaterialTheme.typography.bodySmall,
                                color = KoPilotClusterTokens.SecondaryText,
                            )
                        }
                        if (isSelected) {
                            StatusBadge(text = "ACTIVE", color = KoPilotClusterTokens.PrimaryAccent)
                        } else if (profile.isCustomImported) {
                            StatusBadge(text = "CUSTOM WAV", color = KoPilotClusterTokens.SecondaryAccent)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = profile.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = KoPilotClusterTokens.SecondaryText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                viewModel.selectProfile(profile.id)
                                viewModel.startParkedPreview((profile.idleRpm + profile.redlineRpm) / 3)
                            },
                            modifier = Modifier.height(48.dp),
                            border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Preview", color = KoPilotClusterTokens.PrimaryText)
                        }
                        OutlinedButton(
                            onClick = { onOpenProfileDetails(profile.id) },
                            modifier = Modifier.height(48.dp),
                            border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
                        ) {
                            Text("Details", color = KoPilotClusterTokens.PrimaryText)
                        }
                        Button(
                            onClick = {
                                viewModel.selectProfile(profile.id)
                                onBack()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSelected) {
                                    KoPilotClusterTokens.ElevatedSurface
                                } else {
                                    KoPilotClusterTokens.PrimaryAccent
                                },
                                contentColor = if (isSelected) {
                                    KoPilotClusterTokens.PrimaryAccent
                                } else {
                                    KoPilotClusterTokens.Background
                                },
                            ),
                        ) {
                            Text(if (isSelected) "Selected" else "Use Profile", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Screen C: Sound Profile Details
// ---------------------------------------------------------------------------

@Composable
fun SoundProfileDetailsScreen(
    profileId: String,
    viewModel: SoundStudioViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val profile = state.profiles.firstOrNull { it.id == profileId } ?: state.selectedProfile

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KoPilotClusterTokens.Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        StudioTopBar(
            title = profile.name,
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StudioCard {
                Text(
                    text = profile.configuration,
                    style = MaterialTheme.typography.titleMedium,
                    color = KoPilotClusterTokens.PrimaryAccent,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = profile.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = KoPilotClusterTokens.PrimaryText,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Acoustic Character: ${profile.characteristics}",
                    style = MaterialTheme.typography.bodySmall,
                    color = KoPilotClusterTokens.SecondaryText,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TelemetryStat(label = "Idle", value = "${profile.idleRpm} RPM")
                    TelemetryStat(label = "Redline", value = "${profile.redlineRpm} RPM")
                    TelemetryStat(label = "Cylinders", value = "${profile.category.cylinderCount}")
                }
            }

            StudioCard {
                Text(
                    text = "Parked RPM Audition",
                    style = MaterialTheme.typography.titleSmall,
                    color = KoPilotClusterTokens.PrimaryText,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val midRpm = (profile.idleRpm + profile.redlineRpm) / 2
                    val highRpm = (profile.redlineRpm * 0.85f).roundToInt()

                    OutlinedButton(
                        onClick = {
                            viewModel.selectProfile(profile.id)
                            viewModel.startParkedPreview(profile.idleRpm)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
                    ) {
                        Text("Idle", color = KoPilotClusterTokens.PrimaryText)
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.selectProfile(profile.id)
                            viewModel.startParkedPreview(midRpm)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
                    ) {
                        Text("Mid", color = KoPilotClusterTokens.PrimaryText)
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.selectProfile(profile.id)
                            viewModel.startParkedPreview(highRpm)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
                    ) {
                        Text("High", color = KoPilotClusterTokens.PrimaryText)
                    }
                }
                if (state.isPreviewActive) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.stopAllAudio() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        border = BorderStroke(1.dp, KoPilotClusterTokens.VeryRough),
                    ) {
                        Text("Stop Preview", color = KoPilotClusterTokens.VeryRough)
                    }
                }
            }

            StudioCard {
                Text(
                    text = if (profile.isCustomImported) "Imported WAV Sample Layers" else "Offline Synthesis Layers",
                    style = MaterialTheme.typography.titleSmall,
                    color = KoPilotClusterTokens.PrimaryText,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (profile.isCustomImported && profile.sampleLayers.isNotEmpty()) {
                    profile.sampleLayers.forEach { layer ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "${layer.slot.label} (${layer.fileName})",
                                style = MaterialTheme.typography.bodySmall,
                                color = KoPilotClusterTokens.PrimaryText,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${layer.minRpm}-${layer.maxRpm} RPM",
                                style = MaterialTheme.typography.labelSmall,
                                color = KoPilotClusterTokens.SecondaryText,
                            )
                        }
                    }
                } else {
                    Text(
                        text = "Real-time harmonic synthesis (fundamental + 2nd/3rd/4th order harmonics, " +
                            "exhaust warmth ${(profile.exhaustWarmth * 100).roundToInt()}%, " +
                            "intake growl ${(profile.intakeGrowl * 100).roundToInt()}%). " +
                            "No proprietary recordings bundled.",
                        style = MaterialTheme.typography.bodySmall,
                        color = KoPilotClusterTokens.SecondaryText,
                    )
                }
            }

            Button(
                onClick = {
                    viewModel.selectProfile(profile.id)
                    onBack()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = KoPilotClusterTokens.PrimaryAccent,
                    contentColor = KoPilotClusterTokens.Background,
                ),
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Activate Profile", fontWeight = FontWeight.Bold)
            }

            if (profile.isCustomImported) {
                OutlinedButton(
                    onClick = {
                        viewModel.deleteCustomPack(profile.id)
                        onBack()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    border = BorderStroke(1.dp, KoPilotClusterTokens.VeryRough),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = KoPilotClusterTokens.VeryRough)
                    Spacer(Modifier.width(8.dp))
                    Text("Delete Custom Pack", color = KoPilotClusterTokens.VeryRough)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Screen D: Import Sound Pack
// ---------------------------------------------------------------------------

@Composable
fun ImportSoundPackScreen(
    viewModel: SoundStudioViewModel,
    onBack: () -> Unit,
) {
    val importStatus by viewModel.importStatus.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var packName by remember { mutableStateOf("") }
    var configuration by remember { mutableStateOf("Custom Engine") }
    var description by remember { mutableStateOf("User-imported offline WAV sound pack.") }
    var idleRpmText by remember { mutableStateOf("750") }
    var redlineRpmText by remember { mutableStateOf("6800") }

    val slotSamples = remember { mutableStateMapOf<RpmSampleSlot, ValidatedWavAudio>() }
    val slotRanges = remember {
        mutableStateMapOf<RpmSampleSlot, Pair<String, String>>().apply {
            RpmSampleSlot.entries.forEach { slot ->
                put(slot, slot.defaultMinRpm.toString() to slot.defaultMaxRpm.toString())
            }
        }
    }
    var pendingSlot by remember { mutableStateOf<RpmSampleSlot?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }

    val wavPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        val slot = pendingSlot ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            when (val result = viewModel.validateWavUri(uri)) {
                is WavValidationResult.Valid -> {
                    slotSamples[slot] = result.audio
                    localError = null
                }
                is WavValidationResult.Invalid -> {
                    localError = "${slot.label}: ${result.reason}"
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KoPilotClusterTokens.Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        StudioTopBar(title = "Import Sound Pack", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (localError != null || importStatus != null) {
                StudioCard(
                    borderColor = if (localError != null) KoPilotClusterTokens.VeryRough else KoPilotClusterTokens.PrimaryAccent,
                ) {
                    Text(
                        text = localError ?: importStatus.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (localError != null) KoPilotClusterTokens.VeryRough else KoPilotClusterTokens.PrimaryAccent,
                    )
                }
            }

            StudioCard {
                Text(
                    text = "Pack Metadata",
                    style = MaterialTheme.typography.titleSmall,
                    color = KoPilotClusterTokens.PrimaryText,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = packName,
                    onValueChange = { packName = it },
                    label = { Text("Sound Pack Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = configuration,
                    onValueChange = { configuration = it },
                    label = { Text("Engine Configuration (e.g. Inline-4 Turbo)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = idleRpmText,
                        onValueChange = { idleRpmText = it.filter(Char::isDigit) },
                        label = { Text("Idle RPM") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = redlineRpmText,
                        onValueChange = { redlineRpmText = it.filter(Char::isDigit) },
                        label = { Text("Redline RPM") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            StudioCard {
                Text(
                    text = "RPM Sample Slots (.wav, 8-96 kHz, 16/24/32-bit, <= 15 MB)",
                    style = MaterialTheme.typography.titleSmall,
                    color = KoPilotClusterTokens.PrimaryText,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))

                RpmSampleSlot.entries.forEach { slot ->
                    val assigned = slotSamples[slot]
                    val range = slotRanges[slot] ?: (slot.defaultMinRpm.toString() to slot.defaultMaxRpm.toString())

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = slot.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = KoPilotClusterTokens.PrimaryText,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = assigned?.let {
                                        "${it.fileName} (${it.sampleRateHz} Hz, ${"%.1f".format(it.durationSec)}s)"
                                    } ?: "No WAV assigned",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (assigned != null) KoPilotClusterTokens.PrimaryAccent else KoPilotClusterTokens.SecondaryText,
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (assigned != null) {
                                    IconButton(
                                        onClick = { slotSamples.remove(slot) },
                                        modifier = Modifier.size(48.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Remove ${slot.label}",
                                            tint = KoPilotClusterTokens.VeryRough,
                                        )
                                    }
                                }
                                OutlinedButton(
                                    onClick = {
                                        pendingSlot = slot
                                        wavPicker.launch(arrayOf("audio/wav", "audio/x-wav", "audio/*"))
                                    },
                                    modifier = Modifier.height(48.dp),
                                    border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
                                ) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Pick WAV", color = KoPilotClusterTokens.PrimaryText)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = range.first,
                                onValueChange = { v -> slotRanges[slot] = v.filter(Char::isDigit) to range.second },
                                label = { Text("Min RPM") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = range.second,
                                onValueChange = { v -> slotRanges[slot] = range.first to v.filter(Char::isDigit) },
                                label = { Text("Max RPM") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(top = 10.dp),
                            color = KoPilotClusterTokens.Border,
                        )
                    }
                }
            }

            Button(
                onClick = {
                    val trimmedName = packName.trim()
                    if (trimmedName.isEmpty()) {
                        localError = "Enter a sound pack name."
                        return@Button
                    }
                    if (slotSamples.isEmpty()) {
                        localError = "Assign at least one valid WAV file."
                        return@Button
                    }
                    val idleRpm = idleRpmText.toIntOrNull()?.coerceIn(400, 2000) ?: 750
                    val redlineRpm = redlineRpmText.toIntOrNull()?.coerceIn(idleRpm + 1000, 14000) ?: 6800
                    val payload = slotSamples.mapValues { (slot, audio) ->
                        val pair = slotRanges[slot]
                        val minRpm = pair?.first?.toIntOrNull() ?: slot.defaultMinRpm
                        val maxRpm = pair?.second?.toIntOrNull()?.coerceAtLeast(minRpm + 200) ?: slot.defaultMaxRpm
                        audio to (minRpm to maxRpm)
                    }
                    viewModel.clearImportStatus()
                    viewModel.saveCustomPack(
                        name = trimmedName,
                        description = description.trim().ifEmpty { "Custom WAV engine pack." },
                        configuration = configuration.trim().ifEmpty { "Custom Engine" },
                        idleRpm = idleRpm,
                        redlineRpm = redlineRpm,
                        samples = payload,
                    )
                    onBack()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = KoPilotClusterTokens.PrimaryAccent,
                    contentColor = KoPilotClusterTokens.Background,
                ),
            ) {
                Text("Save Sound Pack", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Screen E: Audio Settings
// ---------------------------------------------------------------------------

@Composable
fun AudioSettingsScreen(
    viewModel: SoundStudioViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = state.audioSettings

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KoPilotClusterTokens.Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        StudioTopBar(title = "Audio & Studio Settings", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StudioCard {
                Text(
                    text = "Output Routing",
                    style = MaterialTheme.typography.titleSmall,
                    color = KoPilotClusterTokens.PrimaryText,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Active Device: ${state.audioOutputRoute}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = KoPilotClusterTokens.PrimaryAccent,
                )
                Text(
                    text = "Estimated Audio Latency: ${state.audioLatencyEstimateMs} ms (44.1 kHz low-latency float PCM)",
                    style = MaterialTheme.typography.bodySmall,
                    color = KoPilotClusterTokens.SecondaryText,
                )
            }

            StudioCard {
                Text(
                    text = "Audio & Synthesis Tuning",
                    style = MaterialTheme.typography.titleSmall,
                    color = KoPilotClusterTokens.PrimaryText,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                LabeledSlider(
                    label = "Master Volume",
                    valueText = "${(settings.masterVolume * 100).roundToInt()}%",
                    value = settings.masterVolume,
                    onValueChange = { v -> viewModel.updateSettings { it.copy(masterVolume = v) } },
                )
                LabeledSlider(
                    label = "Sound Intensity",
                    valueText = "${(settings.soundIntensity * 100).roundToInt()}%",
                    value = settings.soundIntensity,
                    onValueChange = { v -> viewModel.updateSettings { it.copy(soundIntensity = v) } },
                )
                LabeledSlider(
                    label = "RPM Responsiveness",
                    valueText = "${(settings.rpmResponsiveness * 100).roundToInt()}%",
                    value = settings.rpmResponsiveness,
                    valueRange = 0.2f..1.0f,
                    onValueChange = { v -> viewModel.updateSettings { it.copy(rpmResponsiveness = v) } },
                )
                LabeledSlider(
                    label = "Crossfade Smoothing",
                    valueText = "${(settings.crossfadeSmoothing * 100).roundToInt()}%",
                    value = settings.crossfadeSmoothing,
                    onValueChange = { v -> viewModel.updateSettings { it.copy(crossfadeSmoothing = v) } },
                )
            }

            StudioCard {
                Text(
                    text = "Safety & Power Management",
                    style = MaterialTheme.typography.titleSmall,
                    color = KoPilotClusterTokens.PrimaryText,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                SettingsToggleRow(
                    title = "Turbo Spool Effects",
                    subtitle = "Enable boost whistle on turbocharged profiles.",
                    checked = settings.turboEffectsEnabled,
                    onCheckedChange = { checked ->
                        viewModel.updateSettings { it.copy(turboEffectsEnabled = checked) }
                    },
                )
                SettingsToggleRow(
                    title = "Mute During Phone Calls",
                    subtitle = "Automatically silence engine audio when a call is active.",
                    checked = settings.muteOnPhoneCall,
                    onCheckedChange = { checked ->
                        viewModel.updateSettings { it.copy(muteOnPhoneCall = checked) }
                    },
                )
                SettingsToggleRow(
                    title = "Auto-Pause on OBD Disconnect",
                    subtitle = "Fade out audio safely if Bluetooth OBD-II drops.",
                    checked = settings.pauseOnObdDisconnect,
                    onCheckedChange = { checked ->
                        viewModel.updateSettings { it.copy(pauseOnObdDisconnect = checked) }
                    },
                )
                SettingsToggleRow(
                    title = "Pause/Duck on Safety Alerts",
                    subtitle = "Prioritize speed, collision, and road-sign voice alerts.",
                    checked = settings.pauseOnSafetyAlert,
                    onCheckedChange = { checked ->
                        viewModel.updateSettings { it.copy(pauseOnSafetyAlert = checked) }
                    },
                )
                SettingsToggleRow(
                    title = "Battery-Saving Mode",
                    subtitle = "Reduce DSP harmonic complexity and UI refresh rate.",
                    checked = settings.batterySavingMode,
                    onCheckedChange = { checked ->
                        viewModel.updateSettings { it.copy(batterySavingMode = checked) }
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Reusable Studio Components
// ---------------------------------------------------------------------------

@Composable
private fun StudioTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(KoPilotClusterTokens.CardSurface)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = KoPilotClusterTokens.PrimaryText,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = KoPilotClusterTokens.PrimaryText,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

@Composable
private fun StudioCard(
    borderColor: Color = KoPilotClusterTokens.Border,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = KoPilotClusterTokens.CardSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            content()
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.16f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun TelemetryStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = KoPilotClusterTokens.SecondaryText,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = KoPilotClusterTokens.PrimaryText,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = KoPilotClusterTokens.PrimaryText,
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = KoPilotClusterTokens.PrimaryAccent,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = KoPilotClusterTokens.PrimaryAccent,
                activeTrackColor = KoPilotClusterTokens.PrimaryAccent,
                inactiveTrackColor = KoPilotClusterTokens.ElevatedSurface,
            ),
        )
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = KoPilotClusterTokens.PrimaryText,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = KoPilotClusterTokens.SecondaryText,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = KoPilotClusterTokens.Background,
                checkedTrackColor = KoPilotClusterTokens.PrimaryAccent,
            ),
        )
    }
}

@Composable
private fun RpmPointerGauge(
    rpm: Float,
    maxRpm: Float,
    redlineRpm: Float,
    isPreview: Boolean,
    modifier: Modifier = Modifier,
) {
    val animatedRpm by animateFloatAsState(
        targetValue = rpm.coerceIn(0f, maxRpm),
        animationSpec = tween(durationMillis = 80),
        label = "rpmGauge",
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val diameter = minOf(size.width * 0.72f, size.height * 1.15f)
            val topLeft = Offset((size.width - diameter) / 2f, size.height * 0.08f)
            val arcSize = Size(diameter, diameter)
            val startAngle = 140f
            val sweepTotal = 260f

            // Track
            drawArc(
                color = KoPilotClusterTokens.ElevatedSurface,
                startAngle = startAngle,
                sweepAngle = sweepTotal,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round),
            )

            // Redline zone
            val redlineStartRatio = (redlineRpm / maxRpm).coerceIn(0f, 1f)
            drawArc(
                color = KoPilotClusterTokens.VeryRough.copy(alpha = 0.65f),
                startAngle = startAngle + sweepTotal * redlineStartRatio,
                sweepAngle = sweepTotal * (1f - redlineStartRatio),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round),
            )

            // Active RPM arc
            val ratio = (animatedRpm / maxRpm).coerceIn(0f, 1f)
            val activeColor = if (animatedRpm >= redlineRpm) {
                KoPilotClusterTokens.VeryRough
            } else {
                KoPilotClusterTokens.PrimaryAccent
            }
            drawArc(
                color = activeColor,
                startAngle = startAngle,
                sweepAngle = sweepTotal * ratio,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round),
            )

            // Pointer needle
            val center = Offset(topLeft.x + diameter / 2f, topLeft.y + diameter / 2f)
            val angleRad = (startAngle + sweepTotal * ratio) * PI / 180.0
            val needleLen = diameter * 0.38f
            val needleEnd = Offset(
                x = center.x + (cos(angleRad) * needleLen).toFloat(),
                y = center.y + (sin(angleRad) * needleLen).toFloat(),
            )
            drawLine(
                color = KoPilotClusterTokens.PrimaryText,
                start = center,
                end = needleEnd,
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 28.dp),
        ) {
            Text(
                text = animatedRpm.roundToInt().toString(),
                style = MaterialTheme.typography.headlineLarge,
                color = KoPilotClusterTokens.PrimaryText,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (isPreview) "RPM (PREVIEW)" else "RPM",
                style = MaterialTheme.typography.labelSmall,
                color = if (isPreview) KoPilotClusterTokens.SecondaryAccent else KoPilotClusterTokens.SecondaryText,
            )
        }
    }
}

@Composable
private fun WaveformBars(
    samples: List<Float>,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val count = samples.size.coerceAtLeast(1)
        val slotWidth = size.width / count
        val barWidth = (slotWidth * 0.58f).coerceAtLeast(2.dp.toPx())
        val midY = size.height / 2f

        samples.forEachIndexed { index, raw ->
            val amp = if (active) kotlin.math.abs(raw).coerceIn(0.06f, 1f) else 0.06f
            val barH = size.height * amp
            val x = index * slotWidth + (slotWidth - barWidth) / 2f
            drawRoundRect(
                color = if (active) {
                    KoPilotClusterTokens.PrimaryAccent
                } else {
                    KoPilotClusterTokens.Border
                },
                topLeft = Offset(x, midY - barH / 2f),
                size = Size(barWidth, barH),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            )
        }
    }
}
