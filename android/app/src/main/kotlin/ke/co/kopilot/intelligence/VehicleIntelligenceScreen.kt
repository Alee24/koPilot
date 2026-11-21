package ke.co.kopilot.intelligence

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import ke.co.kopilot.feature.trips.TripsContent
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.TextButton
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ke.co.kopilot.core.database.entity.SensorHistoryEntity
import ke.co.kopilot.core.database.preferences.PreferencesRepository
import ke.co.kopilot.core.designsystem.theme.KoPilotClusterTokens
import ke.co.kopilot.feature.alerts.SpeechSink
import ke.co.kopilot.feature.voice.LocalVoiceCommandEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt

data class VehicleIntelligenceUiState(
    val snapshot: VehicleSnapshot,
    val alerts: List<String> = emptyList(),
    val historySamples: List<SensorHistoryEntity> = emptyList(),
    val lastQuestion: String = "",
    val aiAnswer: String = "Ask anything about your vehicle's live OBD-II sensors, coolant temperature, fuel efficiency, or diagnostic codes.",
    val isListening: Boolean = false,
    val isGeneratingReport: Boolean = false,
    val healthReport: FullVehicleHealthReport? = null,
)

@HiltViewModel
class VehicleIntelligenceViewModel @Inject constructor(
    private val telemetryRepo: VehicleTelemetryRepository,
    private val intelligenceEngine: VehicleIntelligenceEngine,
    private val localVoiceEngine: LocalVoiceCommandEngine,
    private val speechSink: SpeechSink,
    private val prefsRepo: PreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        VehicleIntelligenceUiState(snapshot = telemetryRepo.getVehicleSnapshot()),
    )
    val uiState: StateFlow<VehicleIntelligenceUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            telemetryRepo.snapshot.collect { snap ->
                val alerts = intelligenceEngine.getVehicleHealthAlerts()
                val history = telemetryRepo.getRecentHistory(snap.timestampMillis - 60 * 60_000L)
                _uiState.value = _uiState.value.copy(
                    snapshot = snap,
                    alerts = alerts,
                    historySamples = history,
                )
            }
        }
    }

    fun askQuestion(question: String, speakReply: Boolean = false) {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val (answer, report) = intelligenceEngine.answerVehicleQuestion(trimmed)
            _uiState.value = _uiState.value.copy(
                lastQuestion = trimmed,
                aiAnswer = answer,
                healthReport = report ?: _uiState.value.healthReport,
            )
            if (speakReply) {
                speakText(answer)
            }
        }
    }

    fun startVoiceQuestion() {
        viewModelScope.launch {
            val settings = prefsRepo.settingsFlow.firstOrNull()
            val lang = settings?.language ?: "en"
            localVoiceEngine.startListening(
                language = lang,
                onListeningChanged = { listening ->
                    _uiState.value = _uiState.value.copy(isListening = listening)
                },
                onResultSpoken = { transcript, response ->
                    _uiState.value = _uiState.value.copy(
                        lastQuestion = transcript,
                        aiAnswer = response,
                    )
                    speakText(response)
                },
                onError = { err ->
                    _uiState.value = _uiState.value.copy(
                        isListening = false,
                        aiAnswer = err,
                    )
                },
            )
        }
    }

    fun speakCurrentAnswer() {
        speakText(_uiState.value.aiAnswer)
    }

    fun generateHealthReport(speakSummary: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isGeneratingReport = true)
            val report = intelligenceEngine.generateFullVehicleHealthReport(
                performFreshDtcScan = _uiState.value.snapshot.obdConnected,
            )
            _uiState.value = _uiState.value.copy(
                isGeneratingReport = false,
                healthReport = report,
                aiAnswer = report.spokenSummary,
            )
            if (speakSummary) {
                speakText(report.spokenSummary)
            }
        }
    }

    private fun speakText(text: String) {
        viewModelScope.launch {
            val settings = prefsRepo.settingsFlow.firstOrNull()
            speechSink.speak(
                text = text,
                flushQueue = true,
                speechRate = settings?.voiceSpeed ?: 1.0f,
                languageTag = settings?.language ?: "en",
            )
        }
    }
}

@Composable
fun VehicleIntelligenceScreen(
    onBack: () -> Unit,
    onExportReport: (String) -> Unit,
    onOpenEcuPerformance: () -> Unit = {},
    viewModel: VehicleIntelligenceViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Overview", "Trips", "Ask KoPilot", "Sensors", "Report")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KoPilotClusterTokens.Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Vehicle Intelligence",
                    style = MaterialTheme.typography.titleLarge,
                    color = KoPilotClusterTokens.PrimaryText,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = ui.snapshot.vehicleIdentity,
                    style = MaterialTheme.typography.bodySmall,
                    color = KoPilotClusterTokens.SecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(end = 8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            if (ui.snapshot.obdConnected) KoPilotClusterTokens.SmoothRoad else KoPilotClusterTokens.SecondaryText,
                            CircleShape,
                        ),
                )
                Text(
                    text = if (ui.snapshot.obdConnected) "OBD LIVE" else "OFFLINE",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (ui.snapshot.obdConnected) KoPilotClusterTokens.SmoothRoad else KoPilotClusterTokens.SecondaryText,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            edgePadding = 8.dp,
            containerColor = KoPilotClusterTokens.CardSurface,
            contentColor = KoPilotClusterTokens.PrimaryAccent,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = KoPilotClusterTokens.PrimaryAccent,
                )
            },
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            maxLines = 1,
                            color = if (selectedTab == index) KoPilotClusterTokens.PrimaryAccent else KoPilotClusterTokens.SecondaryText,
                        )
                    },
                )
            }
        }

        when (selectedTab) {
            0 -> OverviewTab(
                ui = ui,
                onOpenAsk = { selectedTab = 2 },
                onOpenReport = {
                    viewModel.generateHealthReport(speakSummary = false)
                    selectedTab = 4
                },
                onOpenEcuPerformance = onOpenEcuPerformance,
            )
            1 -> TripsContent(modifier = Modifier.fillMaxSize())
            2 -> AskKoPilotTab(
                ui = ui,
                onAsk = { q -> viewModel.askQuestion(q, speakReply = false) },
                onMic = { viewModel.startVoiceQuestion() },
                onSpeak = { viewModel.speakCurrentAnswer() },
            )
            3 -> SensorExplorerTab(ui = ui)
            else -> HealthReportTab(
                ui = ui,
                onGenerate = { viewModel.generateHealthReport(speakSummary = false) },
                onSpeakSummary = { viewModel.generateHealthReport(speakSummary = true) },
                onExport = onExportReport,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverviewTab(
    ui: VehicleIntelligenceUiState,
    onOpenAsk: () -> Unit,
    onOpenReport: () -> Unit,
    onOpenEcuPerformance: () -> Unit = {},
) {
    val snap = ui.snapshot
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ViCard {
            Text(
                text = "Connection & Monitoring Status",
                style = MaterialTheme.typography.titleSmall,
                color = KoPilotClusterTokens.PrimaryText,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (snap.obdConnected) {
                    "Protocol: ${snap.obdProtocol ?: "Auto"} • ${snap.availableCount()} active PIDs • ${snap.unsupportedCount()} unsupported"
                } else {
                    "OBD-II disconnected. Phone GPS, motion sensors, and offline history remain active."
                },
                style = MaterialTheme.typography.bodySmall,
                color = KoPilotClusterTokens.SecondaryText,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onOpenEcuPerformance,
                colors = ButtonDefaults.buttonColors(
                    containerColor = KoPilotClusterTokens.PrimaryAccent,
                    contentColor = Color.Black,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("ECU Performance Intelligence", fontWeight = FontWeight.SemiBold)
            }
        }

        // Only show supported/valid measurements (never display unsupported as 0)
        val keyPids = listOf("010C", "0105", "010D", "0104", "0142", "0111", "0110", "012F", "015E")
        val visibleReadings = keyPids.mapNotNull { snap.reading(it) }
            .filter { it.validity == MeasurementValidity.AVAILABLE || it.validity == MeasurementValidity.STALE }

        ViCard {
            Text(
                text = "Supported Live Engine Telemetry",
                style = MaterialTheme.typography.titleSmall,
                color = KoPilotClusterTokens.PrimaryText,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            if (visibleReadings.isEmpty()) {
                Text(
                    text = "No live OBD-II readings available right now. Connect your HHOBD Bluetooth adapter with ignition ON.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KoPilotClusterTokens.SecondaryText,
                )
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    visibleReadings.forEach { r ->
                        Surface(
                            color = KoPilotClusterTokens.ElevatedSurface,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(
                                    text = r.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = KoPilotClusterTokens.SecondaryText,
                                )
                                Text(
                                    text = r.formatValue(),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = KoPilotClusterTokens.PrimaryAccent,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }

        ViCard {
            Text(
                text = "Diagnostic Status",
                style = MaterialTheme.typography.titleSmall,
                color = KoPilotClusterTokens.PrimaryText,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = snap.diagnostics.scanStatus.label,
                style = MaterialTheme.typography.bodyMedium,
                color = when (snap.diagnostics.scanStatus) {
                    DtcScanStatus.SCANNED_FAULTS_FOUND -> KoPilotClusterTokens.VeryRough
                    DtcScanStatus.SCANNED_CLEAN -> KoPilotClusterTokens.SmoothRoad
                    else -> KoPilotClusterTokens.SecondaryText
                },
                fontWeight = FontWeight.SemiBold,
            )
            if (ui.alerts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ui.alerts.forEach { alert ->
                    Text(
                        text = "• $alert",
                        style = MaterialTheme.typography.bodySmall,
                        color = KoPilotClusterTokens.ModerateRoad,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onOpenAsk,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = KoPilotClusterTokens.PrimaryAccent,
                    contentColor = KoPilotClusterTokens.Background,
                ),
            ) {
                Text("Ask KoPilot", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onOpenReport,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                border = BorderStroke(1.dp, KoPilotClusterTokens.SecondaryAccent),
            ) {
                Text("Health Report", color = KoPilotClusterTokens.SecondaryAccent)
            }
        }
    }
}

@Composable
private fun AskKoPilotTab(
    ui: VehicleIntelligenceUiState,
    onAsk: (String) -> Unit,
    onMic: () -> Unit,
    onSpeak: () -> Unit,
) {
    var showKeyboardInput by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    val suggestions = listOf(
        "How is my car performing right now?",
        "Are there any check engine codes?",
        "What's my engine temperature?",
        "What's my RPM and voltage?",
        "Give me a full vehicle health report",
        "What sensors are available?",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Hands-Free Voice-First Centerpiece (no keyboard popping up automatically)
        ViCard(borderColor = KoPilotClusterTokens.PrimaryAccent.copy(alpha = 0.5f)) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Hands-Free Voice Assistant (Offline)",
                    style = MaterialTheme.typography.titleSmall,
                    color = KoPilotClusterTokens.PrimaryText,
                    fontWeight = FontWeight.Bold,
                )
                Surface(
                    onClick = onMic,
                    shape = CircleShape,
                    color = if (ui.isListening) KoPilotClusterTokens.VeryRough else KoPilotClusterTokens.PrimaryAccent,
                    modifier = Modifier.size(76.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Tap to Speak",
                            tint = if (ui.isListening) Color.White else KoPilotClusterTokens.Background,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
                Text(
                    text = if (ui.isListening) "Listening... Speak your question" else "Tap microphone and speak naturally",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ui.isListening) KoPilotClusterTokens.VeryRough else KoPilotClusterTokens.PrimaryAccent,
                    fontWeight = FontWeight.SemiBold,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    suggestions.forEach { q ->
                        FilterChip(
                            selected = false,
                            onClick = { onAsk(q) },
                            label = { Text(q) },
                            modifier = Modifier.height(44.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                labelColor = KoPilotClusterTokens.PrimaryText,
                            ),
                        )
                    }
                }

                if (showKeyboardInput) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = { Text("Type optional question...") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = KoPilotClusterTokens.PrimaryAccent,
                                unfocusedBorderColor = KoPilotClusterTokens.Border,
                                focusedTextColor = KoPilotClusterTokens.PrimaryText,
                                unfocusedTextColor = KoPilotClusterTokens.PrimaryText,
                            ),
                        )
                        IconButton(
                            onClick = {
                                if (inputText.isNotBlank()) {
                                    onAsk(inputText)
                                    inputText = ""
                                }
                            },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = KoPilotClusterTokens.PrimaryAccent,
                            )
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = { showKeyboardInput = true },
                        modifier = Modifier.height(40.dp),
                        border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
                    ) {
                        Text("Optional Text Input", style = MaterialTheme.typography.labelSmall, color = KoPilotClusterTokens.SecondaryText)
                    }
                }
            }
        }

        ViCard(borderColor = KoPilotClusterTokens.PrimaryAccent.copy(alpha = 0.6f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (ui.lastQuestion.isNotBlank()) "You asked: ${ui.lastQuestion}" else "KoPilot Response",
                    style = MaterialTheme.typography.labelMedium,
                    color = KoPilotClusterTokens.PrimaryAccent,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onSpeak,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Speak response",
                        tint = KoPilotClusterTokens.PrimaryAccent,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = ui.aiAnswer,
                style = MaterialTheme.typography.bodyMedium,
                color = KoPilotClusterTokens.PrimaryText,
            )
        }
    }
}

@Composable
private fun SensorExplorerTab(ui: VehicleIntelligenceUiState) {
    val snap = ui.snapshot
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (ui.historySamples.size >= 2) {
            ViCard {
                Text(
                    text = "Coolant Temperature History (${ui.historySamples.size} samples)",
                    style = MaterialTheme.typography.titleSmall,
                    color = KoPilotClusterTokens.PrimaryText,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                val temps = ui.historySamples.mapNotNull { it.coolantTempC?.toFloat() }
                HistorySparkline(
                    values = temps,
                    minVal = 40f,
                    maxVal = 120f,
                    lineColor = KoPilotClusterTokens.PrimaryAccent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(90.dp),
                )
            }
        }

        ViCard {
            Text(
                text = "OBD-II & Phone Sensor Registry",
                style = MaterialTheme.typography.titleSmall,
                color = KoPilotClusterTokens.PrimaryText,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            snap.engineReadings.values.forEachIndexed { idx, reading ->
                val statusColor = when (reading.validity) {
                    MeasurementValidity.AVAILABLE -> KoPilotClusterTokens.SmoothRoad
                    MeasurementValidity.STALE -> KoPilotClusterTokens.ModerateRoad
                    MeasurementValidity.UNSUPPORTED -> KoPilotClusterTokens.SecondaryText
                    else -> KoPilotClusterTokens.RoughRoad
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${reading.pidOrSensorId} • ${reading.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = KoPilotClusterTokens.PrimaryText,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = reading.timestampMillis?.let { "Updated ${fmt.format(Date(it))}" } ?: reading.source,
                            style = MaterialTheme.typography.labelSmall,
                            color = KoPilotClusterTokens.SecondaryText,
                        )
                    }
                    Text(
                        text = reading.formatValue(),
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (idx < snap.engineReadings.size - 1) {
                    HorizontalDivider(color = KoPilotClusterTokens.Border.copy(alpha = 0.4f))
                }
            }
        }
    }
}

@Composable
private fun HealthReportTab(
    ui: VehicleIntelligenceUiState,
    onGenerate: () -> Unit,
    onSpeakSummary: () -> Unit,
    onExport: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onGenerate,
                enabled = !ui.isGeneratingReport,
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = KoPilotClusterTokens.PrimaryAccent,
                    contentColor = KoPilotClusterTokens.Background,
                ),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (ui.isGeneratingReport) "Scanning..." else "Run Full Report",
                    fontWeight = FontWeight.Bold,
                )
            }
            OutlinedButton(
                onClick = onSpeakSummary,
                modifier = Modifier.height(50.dp),
                border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Speak report", tint = KoPilotClusterTokens.PrimaryText)
            }
            ui.healthReport?.let { rep ->
                OutlinedButton(
                    onClick = { onExport(rep.detailedText) },
                    modifier = Modifier.height(50.dp),
                    border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Export report", tint = KoPilotClusterTokens.PrimaryText)
                }
            }
        }

        val rep = ui.healthReport
        var showTechnicalDetails by remember { mutableStateOf(false) }
        val snap = ui.snapshot
        if (rep == null) {
            ViCard {
                Text(
                    text = "Tap 'Run Full Report' for a simple summary of your fuel consumption, battery voltage, engine health, and fault codes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = KoPilotClusterTokens.SecondaryText,
                )
            }
        } else {
            val fuelLine = rep.fuelObservations.firstOrNull() ?: "Fuel data unavailable"
            val batteryLine = rep.electricalObservations.firstOrNull() ?: "Battery data unavailable"
            val engineLine = rep.engineObservations.firstOrNull() ?: "Engine standby"
            val dtcs = snap.diagnostics.storedCodes
            val faultLine = if (dtcs.isEmpty()) "No Fault Codes" else "${dtcs.size} Code(s): ${dtcs.joinToString(", ")}"

            ReportSectionCard(
                title = "All-in-One Vehicle Summary",
                lines = listOf(
                    "Fuel: $fuelLine",
                    "Battery: $batteryLine",
                    "Engine: $engineLine",
                    "Fault Check: $faultLine",
                ),
            )

            TextButton(onClick = { showTechnicalDetails = !showTechnicalDetails }) {
                Text(
                    text = if (showTechnicalDetails) "Hide Technical Sensor Details" else "Show Technical Sensor Details",
                    color = KoPilotClusterTokens.PrimaryAccent,
                )
            }

            if (showTechnicalDetails) {
                ReportSectionCard("Engine Observations", rep.engineObservations)
                ReportSectionCard("Electrical Observations", rep.electricalObservations)
                ReportSectionCard("Fuel Observations", rep.fuelObservations)
                ReportSectionCard("Diagnostic Findings", rep.diagnosticFindings)
                if (rep.recentTrends.isNotEmpty()) ReportSectionCard("Recent Trends", rep.recentTrends)
                if (rep.potentialConcerns.isNotEmpty()) ReportSectionCard("Potential Concerns", rep.potentialConcerns)
                ReportSectionCard("Recommended Checks", rep.recommendedChecks)
            }
        }
    }
}

@Composable
private fun ReportSectionCard(title: String, lines: List<String>) {
    ViCard {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = KoPilotClusterTokens.PrimaryAccent,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        lines.forEach { line ->
            Text(
                text = "• $line",
                style = MaterialTheme.typography.bodySmall,
                color = KoPilotClusterTokens.PrimaryText,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun ViCard(
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
private fun HistorySparkline(
    values: List<Float>,
    minVal: Float,
    maxVal: Float,
    lineColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val path = Path()
        val span = (maxVal - minVal).coerceAtLeast(1f)
        val stepX = size.width / (values.size - 1).coerceAtLeast(1)
        values.forEachIndexed { idx, v ->
            val x = idx * stepX
            val norm = ((v - minVal) / span).coerceIn(0f, 1f)
            val y = size.height * (1f - norm)
            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}
