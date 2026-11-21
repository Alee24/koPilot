package ke.co.kopilot.ecu

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ke.co.kopilot.core.database.entity.TripEntity
import ke.co.kopilot.feature.dashboard.DashboardUiState
import ke.co.kopilot.feature.trips.TripLogParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val FuelEmerald = Color(0xFF22C55E)
private val FuelBlue = Color(0xFF38BDF8)
private val FuelAmber = Color(0xFFF59E0B)

private data class TripFuelSummary(
    val trip: TripEntity,
    val fuelLabel: String,
    val estimatedLiters: Double?,
    val litersPer100Km: Double?,
    val kmPerLiter: Double?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FuelEfficiencyAnalysisScreen(
    state: DashboardUiState,
    onOpenEcuPerformance: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val dateFormatter = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }

    val tripSummaries = remember(state.savedTrips) {
        val logDir = File(ctx.filesDir, "trip_logs")
        val lphRegex = Regex("""([\d.]+)\s*L/h""", RegexOption.IGNORE_CASE)
        val litersRegex = Regex("""([\d.]+)\s*L(?!/)""", RegexOption.IGNORE_CASE)
        state.savedTrips.map { trip ->
            val logFile = File(logDir, "trip_${trip.id}.txt")
            val parsed = TripLogParser.parse(if (logFile.exists()) logFile else null)
            val lph = lphRegex.find(parsed.fuelConsumption)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            val directLiters = litersRegex.find(parsed.fuelConsumption)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            val durationHours = (trip.durationSeconds / 3600.0).coerceAtLeast(0.0)
            val estLiters = when {
                directLiters != null && directLiters > 0.0 -> directLiters
                lph != null && durationHours > 0.0 -> lph * durationHours
                else -> null
            }
            val l100 = if (estLiters != null && trip.distanceKm >= 0.25) {
                (estLiters / trip.distanceKm) * 100.0
            } else null
            val kml = if (estLiters != null && estLiters > 0.0 && trip.distanceKm >= 0.25) {
                trip.distanceKm / estLiters
            } else null
            TripFuelSummary(
                trip = trip,
                fuelLabel = parsed.fuelConsumption,
                estimatedLiters = estLiters,
                litersPer100Km = l100,
                kmPerLiter = kml,
            )
        }
    }

    val totalDistanceKm = tripSummaries.sumOf { it.trip.distanceKm }
    val tripsWithFuel = tripSummaries.filter { it.estimatedLiters != null }
    val totalFuelLiters = tripsWithFuel.sumOf { it.estimatedLiters ?: 0.0 }
    val overallL100 = if (totalFuelLiters > 0.0 && totalDistanceKm >= 0.25) {
        (totalFuelLiters / totalDistanceKm) * 100.0
    } else state.ecuBaseline.avgLitersPer100Km
    val overallKmL = overallL100?.takeIf { it > 0.0 }?.let { 100.0 / it }
    val bestTrip = tripSummaries.filter { it.kmPerLiter != null }.maxByOrNull { it.kmPerLiter!! }
    val highestUsageTrip = tripSummaries.filter { it.litersPer100Km != null }.maxByOrNull { it.litersPer100Km!! }

    val currentAnalysis = state.ecuPerformanceAnalysis
    val baseline = state.ecuBaseline

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Fuel Efficiency Analysis",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${tripSummaries.size} trips analyzed • Multi-trip economy report",
                            style = MaterialTheme.typography.labelSmall,
                            color = FuelEmerald,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.LocalGasStation, contentDescription = null, tint = FuelEmerald)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "All-Trips Fuel Economy Summary",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            FuelStatBox("Trips", "${tripSummaries.size}", Modifier.weight(1f))
                            FuelStatBox("Total Dist", String.format(Locale.US, "%.1f km", totalDistanceKm), Modifier.weight(1f))
                            FuelStatBox("Fuel Used", if (totalFuelLiters > 0.0) String.format(Locale.US, "%.2f L", totalFuelLiters) else "--", Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            FuelStatBox("Avg Economy", overallKmL?.let { String.format(Locale.US, "%.1f km/L", it) } ?: "--", Modifier.weight(1f))
                            FuelStatBox("Avg L/100km", overallL100?.let { String.format(Locale.US, "%.1f", it) } ?: "--", Modifier.weight(1f))
                            FuelStatBox("Baseline Trips", "${baseline.tripCount}", Modifier.weight(1f))
                        }
                        bestTrip?.let {
                            Text(
                                text = "Best Trip: ${it.trip.originLabel ?: "Start"} → ${it.trip.destinationLabel ?: "End"} (${String.format(Locale.US, "%.1f km/L", it.kmPerLiter)})",
                                style = MaterialTheme.typography.bodySmall,
                                color = FuelEmerald,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        highestUsageTrip?.let {
                            if (it != bestTrip) {
                                Text(
                                    text = "Highest Consumption: ${it.trip.originLabel ?: "Start"} → ${it.trip.destinationLabel ?: "End"} (${String.format(Locale.US, "%.1f L/100km", it.litersPer100Km)})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = FuelAmber,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Speed, contentDescription = null, tint = FuelBlue)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Current Trip & Driving Style Breakdown",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        FuelRow("Data Source", currentAnalysis.fuelProvenance)
                        FuelRow("Current Trip Economy", currentAnalysis.kmPerLiter?.let { String.format(Locale.US, "%.1f km/L (%.1f L/100km)", it, currentAnalysis.litersPer100Km ?: 0.0) } ?: "Drive with OBD-II for live data")
                        FuelRow("Optimal Cruising Speed", currentAnalysis.recommendedCruisingRange ?: "Collecting steady cruise data")
                        FuelRow("Idle Fuel Share", currentAnalysis.idleFuelLiters?.let { String.format(Locale.US, "%.2f L", it) } ?: "--")
                        FuelRow("Acceleration Fuel", currentAnalysis.accelFuelLiters?.let { String.format(Locale.US, "%.2f L", it) } ?: "--")
                        FuelRow("Steady Cruise Fuel", currentAnalysis.cruiseFuelLiters?.let { String.format(Locale.US, "%.2f L", it) } ?: "--")
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = onOpenEcuPerformance,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(Icons.Rounded.Analytics, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Open Full ECU Performance Intelligence")
                        }
                    }
                }
            }

            if (tripsWithFuel.isNotEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Timeline, contentDescription = null, tint = FuelBlue)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Fuel Consumption Trend Across Trips (L/100km)",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            val chartItems = tripsWithFuel.filter { it.litersPer100Km != null }.take(10).reversed()
                            val maxL100 = chartItems.maxOfOrNull { it.litersPer100Km ?: 0.0 }?.coerceAtLeast(1.0) ?: 12.0
                            Canvas(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(110.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                                    .padding(10.dp),
                            ) {
                                val count = chartItems.size.coerceAtLeast(1)
                                val slotW = size.width / count
                                val barW = slotW * 0.55f
                                chartItems.forEachIndexed { idx, item ->
                                    val ratio = ((item.litersPer100Km ?: 0.0) / maxL100).toFloat().coerceIn(0f, 1f)
                                    val barH = size.height * ratio
                                    drawRect(
                                        color = if ((item.litersPer100Km ?: 99.0) <= (overallL100 ?: 9.0)) FuelEmerald else FuelAmber,
                                        topLeft = Offset(idx * slotW + (slotW - barW) / 2f, size.height - barH),
                                        size = Size(barW, barH),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Per-Trip Fuel Efficiency Breakdown",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (tripSummaries.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "No completed trips yet. Complete trips with KoPilot to build your multi-trip fuel efficiency report.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            } else {
                items(tripSummaries, key = { it.trip.id }) { item ->
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Rounded.DirectionsCar, contentDescription = null, tint = FuelBlue, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "${item.trip.originLabel ?: "Start"} → ${item.trip.destinationLabel ?: "Local Drive"}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    text = dateFormatter.format(Date(item.trip.startedAtMillis)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            FuelRow("Distance / Avg Speed", "${String.format(Locale.US, "%.2f km", item.trip.distanceKm)} • ${item.trip.avgSpeedKmh.toInt()} km/h")
                            FuelRow("Fuel Summary", item.fuelLabel)
                            if (item.kmPerLiter != null && item.litersPer100Km != null) {
                                FuelRow(
                                    "Calculated Economy",
                                    String.format(Locale.US, "%.1f km/L (%.1f L/100km)", item.kmPerLiter, item.litersPer100Km),
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun FuelStatBox(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FuelRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}
