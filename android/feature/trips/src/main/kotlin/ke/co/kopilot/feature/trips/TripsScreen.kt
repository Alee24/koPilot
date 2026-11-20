package ke.co.kopilot.feature.trips

import android.content.Intent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.model.LatLng
import ke.co.kopilot.core.database.entity.TripEntity
import ke.co.kopilot.feature.navigation.KoPilotNavigationMap
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ParsedTripDetails(
    val routePoints: List<LatLng>,
    val fuelConsumption: String,
    val batteryVoltage: String,
    val overallSummary: String,
)

object TripLogParser {
    private val gpsRegex = Regex("""GPS=([-+]?\d+\.\d+),([-+]?\d+\.\d+)""")
    private val trackRegex = Regex("""\[GPS_TRACK\]\s+([-+]?\d+\.\d+),([-+]?\d+\.\d+)""")

    fun parse(file: File?): ParsedTripDetails {
        if (file == null || !file.exists()) {
            return ParsedTripDetails(
                routePoints = emptyList(),
                fuelConsumption = "OBD Offline",
                batteryVoltage = "OBD Offline",
                overallSummary = "Trip recorded",
            )
        }
        return runCatching {
            val lines = file.readLines()
            val pts = mutableListOf<LatLng>()
            for (line in lines) {
                val trackMatch = trackRegex.find(line)
                if (trackMatch != null) {
                    val lat = trackMatch.groupValues[1].toDoubleOrNull()
                    val lng = trackMatch.groupValues[2].toDoubleOrNull()
                    if (lat != null && lng != null) pts.add(LatLng(lat, lng))
                    continue
                }
                val gpsMatch = gpsRegex.find(line)
                if (gpsMatch != null) {
                    val lat = gpsMatch.groupValues[1].toDoubleOrNull()
                    val lng = gpsMatch.groupValues[2].toDoubleOrNull()
                    if (lat != null && lng != null) pts.add(LatLng(lat, lng))
                }
            }
            val summaryLine = lines.lastOrNull { it.contains("[TRIP_SUMMARY]") }
                ?.substringAfter("[TRIP_SUMMARY]")?.trim()
            val fuel = summaryLine?.substringAfter("Fuel=", "")?.substringBefore("|")?.trim()
                ?.takeIf { it.isNotBlank() } ?: "OBD Offline"
            val battery = summaryLine?.substringAfter("Battery=", "")?.substringBefore("|")?.trim()
                ?.takeIf { it.isNotBlank() } ?: "OBD Offline"
            val summary = summaryLine?.substringAfter("Summary=", "")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: run {
                    val road = lines.lastOrNull { it.contains("[ROAD_QUALITY]") }
                        ?.substringAfter("Avg Score=")?.substringBefore("|")?.trim()
                    val driver = lines.lastOrNull { it.contains("[DRIVING_BEHAVIOUR]") }
                        ?.substringAfter("Driver Score=")?.substringBefore("|")?.trim()
                    listOfNotNull(
                        driver?.let { "Safety $it" },
                        road?.let { "Road $it" },
                    ).joinToString(" • ").ifBlank { "All systems normal" }
                }
            ParsedTripDetails(
                routePoints = pts,
                fuelConsumption = fuel,
                batteryVoltage = battery,
                overallSummary = summary,
            )
        }.getOrElse {
            ParsedTripDetails(emptyList(), "OBD Offline", "OBD Offline", "Trip recorded")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsScreen(
    onBack: () -> Unit,
    viewModel: TripsViewModel = hiltViewModel(),
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Trips & Maps") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        TripsContent(
            viewModel = viewModel,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TripsContent(
    viewModel: TripsViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val trips by viewModel.trips.collectAsStateWithLifecycle()
    val activeLogPath by viewModel.activeLogPath.collectAsStateWithLifecycle()
    val activeLineCount by viewModel.activeLineCount.collectAsStateWithLifecycle()
    var viewingFile by remember { mutableStateOf<File?>(null) }
    var fullMapTrip by remember { mutableStateOf<Pair<TripEntity, ParsedTripDetails>?>(null) }

    val dateFormatter = remember { SimpleDateFormat("EEE, dd MMM yyyy • HH:mm", Locale.getDefault()) }

    val shareLogFile: (File) -> Unit = { file ->
        runCatching {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "KoPilot Trip Activity Log (${file.name})")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(intent, "Share Trip Log (.txt)"))
        }
    }

    fullMapTrip?.let { (trip, details) ->
        AlertDialog(
            onDismissRequest = { fullMapTrip = null },
            title = {
                Text(
                    text = "${trip.originLabel ?: "Start"} → ${trip.destinationLabel ?: "Local Drive"}",
                    maxLines = 1,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(340.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    ) {
                        KoPilotNavigationMap(
                            currentLat = details.routePoints.firstOrNull()?.latitude,
                            currentLng = details.routePoints.firstOrNull()?.longitude,
                            routePoints = details.routePoints,
                            destinationLatLng = details.routePoints.lastOrNull(),
                            destinationName = trip.destinationLabel ?: "Trip End",
                            hasLocationPermission = details.routePoints.isNotEmpty(),
                        )
                    }
                    Text(
                        text = "Fuel: ${details.fuelConsumption} • Battery: ${details.batteryVoltage}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = details.overallSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { fullMapTrip = null }) { Text("Close") }
            },
        )
    }

    viewingFile?.let { file ->
        val content = remember(file.absolutePath, file.length()) {
            runCatching { file.readText() }.getOrElse { "Unable to read log file: ${it.localizedMessage}" }
        }
        AlertDialog(
            onDismissRequest = { viewingFile = null },
            title = { Text(file.name, maxLines = 1) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = content,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(10.dp).horizontalScroll(rememberScrollState()),
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { viewingFile = null }) { Text("Close") }
            },
            confirmButton = {
                Button(onClick = { shareLogFile(file) }) { Text("Share .txt") }
            },
        )
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val totalKm = trips.sumOf { it.distanceKm }
        val totalMins = trips.sumOf { it.durationSeconds } / 60L
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Driving Summary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TripStatChip("Trips", "${trips.size}")
                    TripStatChip("Distance", "${"%.1f".format(totalKm)} km")
                    TripStatChip("Drive Time", "${totalMins}m")
                }
            }
        }

        val activeFile = if (activeLogPath != null) viewModel.getActiveLogFile() else null
        if (activeFile != null) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Active Trip Recording ($activeLineCount events)", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewingFile = activeFile }) {
                            Text("View Live Log")
                        }
                        Button(onClick = { shareLogFile(activeFile) }) {
                            Text("Share .txt")
                        }
                    }
                }
            }
        }

        if (trips.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("No completed trips yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "KoPilot auto-starts recording when your car begins moving. Completed trips show your full route map, fuel consumption, and battery health.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {
            trips.forEach { trip ->
                val logFile = remember(trip.id, trip.startedAtMillis) {
                    viewModel.findLogFile(trip.startedAtMillis)
                }
                val details = remember(logFile?.absolutePath, logFile?.length()) {
                    TripLogParser.parse(logFile)
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${trip.originLabel ?: "Current Location"} → ${trip.destinationLabel ?: "Local Drive"}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = dateFormatter.format(Date(trip.startedAtMillis)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (trip.syncedToBackend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            ) {
                                Text(
                                    text = if (trip.syncedToBackend) "Synced" else "Local",
                                    color = if (trip.syncedToBackend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }

                        // Embedded OpenStreetMap showing full recorded trip route
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(RoundedCornerShape(12.dp)),
                        ) {
                            if (details.routePoints.isNotEmpty()) {
                                KoPilotNavigationMap(
                                    currentLat = details.routePoints.first().latitude,
                                    currentLng = details.routePoints.first().longitude,
                                    routePoints = details.routePoints,
                                    destinationLatLng = details.routePoints.last(),
                                    destinationName = trip.destinationLabel ?: "Trip End",
                                    hasLocationPermission = true,
                                )
                                IconButton(
                                    onClick = { fullMapTrip = trip to details },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp),
                                ) {
                                    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xAA071019)) {
                                        Icon(
                                            imageVector = Icons.Default.Fullscreen,
                                            contentDescription = "Expand Trip Map",
                                            tint = Color.White,
                                            modifier = Modifier.padding(6.dp),
                                        )
                                    }
                                }
                            } else {
                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    color = MaterialTheme.colorScheme.surface,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "Map route unavailable (GPS not locked during trip)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }

                        // Clean Common-User Summary (no raw sensor spam)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TripStatChip("Distance", "${"%.2f".format(trip.distanceKm)} km")
                            TripStatChip("Duration", "${trip.durationSeconds / 60}m ${trip.durationSeconds % 60}s")
                            TripStatChip("Fuel", details.fuelConsumption)
                            TripStatChip("Battery", details.batteryVoltage)
                            if (trip.heartRateSamplesCount > 0 && trip.avgHeartRateBpm != null) {
                                TripStatChip(
                                    label = "Heart Rate",
                                    value = "${trip.avgHeartRateBpm} BPM (${trip.minHeartRateBpm ?: "--"}-${trip.maxHeartRateBpm ?: "--"})",
                                )
                            }
                            trip.preDriveSleepMinutes?.let { sleepMins ->
                                val h = sleepMins / 60
                                val m = sleepMins % 60
                                TripStatChip("Pre-Drive Sleep", if (h > 0) "${h}h ${m}m" else "${m}m")
                            }
                        }
                        if (trip.watchDisconnectedGapsCount > 0) {
                            Text(
                                text = "Smartwatch data gaps during trip: ${trip.watchDisconnectedGapsCount} disconnection interval(s) preserved.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        val safetyEventLines = remember(logFile?.absolutePath, logFile?.length()) {
                            runCatching {
                                logFile?.takeIf { it.exists() }
                                    ?.readLines()
                                    ?.filter { it.contains("[SAFETY_EVENT]") || it.contains("ACCIDENT_") }
                                    ?.takeLast(8)
                                    .orEmpty()
                            }.getOrDefault(emptyList())
                        }
                        if (safetyEventLines.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = "Safety Events (${safetyEventLines.size})",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    safetyEventLines.forEach { line ->
                                        Text(
                                            text = line.trim(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = details.overallSummary,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(10.dp),
                            )
                        }

                        if (logFile != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (details.routePoints.isNotEmpty()) {
                                    OutlinedButton(onClick = { fullMapTrip = trip to details }) {
                                        Text("Full Map")
                                    }
                                }
                                OutlinedButton(onClick = { viewingFile = logFile }) {
                                    Text("View Log")
                                }
                                Button(onClick = { shareLogFile(logFile) }) {
                                    Text("Share")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TripStatChip(label: String, value: String) {
    Column {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}
