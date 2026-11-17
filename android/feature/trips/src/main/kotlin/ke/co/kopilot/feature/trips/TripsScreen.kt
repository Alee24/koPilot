package ke.co.kopilot.feature.trips

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsScreen(
    onBack: () -> Unit,
    viewModel: TripsViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    val trips by viewModel.trips.collectAsStateWithLifecycle()
    val activeLogPath by viewModel.activeLogPath.collectAsStateWithLifecycle()
    val activeLineCount by viewModel.activeLineCount.collectAsStateWithLifecycle()
    var viewingFile by remember { mutableStateOf<File?>(null) }

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
                    Text(
                        text = "Path: ${file.absolutePath}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Trips & Activity Log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Overall Summary KPIs
            val totalKm = trips.sumOf { it.distanceKm }
            val totalMins = trips.sumOf { it.durationSeconds } / 60L
            val totalSigns = trips.sumOf { it.signsDetectedCount }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Driving Summary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TripStatChip("Trips", "${trips.size}")
                        TripStatChip("Distance", "${"%.1f".format(totalKm)} km")
                        TripStatChip("Drive Time", "${totalMins}m")
                        TripStatChip("Signs", "$totalSigns")
                    }
                }
            }

            // Active Trip Live Log Card
            val activeFile = if (activeLogPath != null) viewModel.getActiveLogFile() else null
            if (activeFile != null) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Active Trip Recording ($activeLineCount events)", fontWeight = FontWeight.Bold)
                        Text(activeFile.name, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { viewingFile = activeFile }) {
                                Text("View Live .txt")
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
                            "Tap Start Driving on the dashboard to record distance, speeds, road signs, alerts, and a timestamped .txt activity log.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            } else {
                trips.forEach { trip ->
                    val logFile = remember(trip.id, trip.startedAtMillis) {
                        viewModel.findLogFile(trip.startedAtMillis)
                    }
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TripStatChip("Distance", "${"%.2f".format(trip.distanceKm)} km")
                                TripStatChip("Duration", "${trip.durationSeconds / 60}m ${trip.durationSeconds % 60}s")
                                TripStatChip("Max Speed", "${trip.maxSpeedKmh.toInt()} km/h")
                                TripStatChip("Avg Speed", "${trip.avgSpeedKmh.toInt()} km/h")
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                TripStatChip("Signs Detected", "${trip.signsDetectedCount}")
                                TripStatChip("Alerts Triggered", "${trip.alertsTriggeredCount}")
                            }

                            if (logFile != null) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { viewingFile = logFile }) {
                                        Text("View Activity Log (.txt)")
                                    }
                                    Button(onClick = { shareLogFile(logFile) }) {
                                        Text("Share .txt")
                                    }
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
        Text(value, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}
