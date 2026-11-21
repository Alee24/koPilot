package ke.co.kopilot.maintenance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ke.co.kopilot.core.database.maintenance.MaintenanceCalculator
import ke.co.kopilot.core.database.maintenance.MaintenanceCategory
import ke.co.kopilot.core.database.maintenance.OdometerSource
import ke.co.kopilot.core.database.maintenance.ServiceUrgency
import ke.co.kopilot.core.designsystem.theme.KoPilotClusterTokens
import ke.co.kopilot.feature.dashboard.DashboardUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VehicleMaintenanceScreen(
    state: DashboardUiState,
    onUpdateManualOdometer: (Double) -> Unit,
    onAddServiceRecord: (
        category: MaintenanceCategory,
        customTitle: String,
        odometerKm: Double,
        serviceDateMillis: Long,
        intervalKm: Int,
        intervalMonths: Int,
        costKes: Double?,
        notes: String,
    ) -> Unit,
    onUpdateThresholds: (String) -> Unit,
    onBack: () -> Unit,
) {
    val s = state.settings
    val dateFmt = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    val history = remember(s.serviceHistoryJson) { MaintenanceCalculator.parseServiceHistory(s.serviceHistoryJson) }

    var showOdometerDialog by remember { mutableStateOf(false) }
    var showAddServiceDialog by remember { mutableStateOf<MaintenanceCategory?>(null) }
    var showHistoryDialog by remember { mutableStateOf(false) }

    if (showOdometerDialog) {
        var odoInput by remember { mutableStateOf(s.officialOdometerKm?.toInt()?.toString() ?: "") }
        AlertDialog(
            onDismissRequest = { showOdometerDialog = false },
            title = { Text("Update Official Odometer") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Enter the exact odometer reading from your vehicle's instrument cluster (in km). " +
                            "GPS distance is never substituted as the official odometer.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = odoInput,
                        onValueChange = { odoInput = it },
                        label = { Text("Odometer (km)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { showOdometerDialog = false }) { Text("Cancel") } },
            confirmButton = {
                Button(
                    enabled = odoInput.toDoubleOrNull()?.let { it >= 0.0 } == true,
                    onClick = {
                        odoInput.toDoubleOrNull()?.let { onUpdateManualOdometer(it) }
                        showOdometerDialog = false
                    },
                ) { Text("Save") }
            },
        )
    }

    showAddServiceDialog?.let { initialCat ->
        var selectedCat by remember(initialCat) { mutableStateOf(initialCat) }
        var customTitle by remember { mutableStateOf("") }
        var odoStr by remember { mutableStateOf(s.officialOdometerKm?.toInt()?.toString() ?: "") }
        var intervalKmStr by remember(selectedCat) { mutableStateOf(selectedCat.defaultIntervalKm.toString()) }
        var intervalMonthsStr by remember(selectedCat) { mutableStateOf(selectedCat.defaultIntervalMonths.toString()) }
        var costStr by remember { mutableStateOf("") }
        var notesStr by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddServiceDialog = null },
            title = { Text("Add Service Record") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        MaintenanceCategory.entries.forEach { cat ->
                            FilterChip(
                                selected = selectedCat == cat,
                                onClick = { selectedCat = cat },
                                label = { Text(cat.displayName, fontSize = 11.sp) },
                            )
                        }
                    }
                    if (selectedCat == MaintenanceCategory.CUSTOM) {
                        OutlinedTextField(
                            value = customTitle,
                            onValueChange = { customTitle = it },
                            label = { Text("Custom Service Name") },
                            singleLine = true,
                        )
                    }
                    OutlinedTextField(
                        value = odoStr,
                        onValueChange = { odoStr = it },
                        label = { Text("Service Odometer (km)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = intervalKmStr,
                            onValueChange = { intervalKmStr = it },
                            label = { Text("Interval (km)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = intervalMonthsStr,
                            onValueChange = { intervalMonthsStr = it },
                            label = { Text("Months") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }
                    OutlinedTextField(
                        value = costStr,
                        onValueChange = { costStr = it },
                        label = { Text("Cost (KES, optional)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = notesStr,
                        onValueChange = { notesStr = it },
                        label = { Text("Notes (parts, garage)") },
                    )
                }
            },
            dismissButton = { TextButton(onClick = { showAddServiceDialog = null }) { Text("Cancel") } },
            confirmButton = {
                Button(
                    enabled = odoStr.toDoubleOrNull() != null,
                    onClick = {
                        onAddServiceRecord(
                            selectedCat,
                            customTitle,
                            odoStr.toDoubleOrNull() ?: 0.0,
                            System.currentTimeMillis(),
                            intervalKmStr.toIntOrNull() ?: selectedCat.defaultIntervalKm,
                            intervalMonthsStr.toIntOrNull() ?: selectedCat.defaultIntervalMonths,
                            costStr.toDoubleOrNull(),
                            notesStr,
                        )
                        showAddServiceDialog = null
                    },
                ) { Text("Save Record") }
            },
        )
    }

    if (showHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showHistoryDialog = false },
            title = { Text("Service History (${history.size})") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (history.isEmpty()) {
                        Text("No service records logged yet.")
                    } else {
                        history.forEach { rec ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = KoPilotClusterTokens.Background,
                                border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("${rec.title} • ${rec.odometerKm.toInt()} km", fontWeight = FontWeight.Bold, color = KoPilotClusterTokens.PrimaryText, fontSize = 13.sp)
                                    Text(
                                        text = "${dateFmt.format(Date(rec.serviceDateMillis))}${rec.costKes?.let { " • KES ${it.toInt()}" } ?: ""}",
                                        color = KoPilotClusterTokens.SecondaryText,
                                        fontSize = 11.sp,
                                    )
                                    if (rec.notes.isNotBlank()) {
                                        Text(rec.notes, color = KoPilotClusterTokens.SecondaryText, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHistoryDialog = false }) { Text("Close") } },
        )
    }

    Scaffold(
        containerColor = KoPilotClusterTokens.Background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = KoPilotClusterTokens.Background,
                    titleContentColor = KoPilotClusterTokens.PrimaryText,
                    navigationIconContentColor = KoPilotClusterTokens.PrimaryText,
                ),
                title = {
                    Column {
                        Text("Vehicle Maintenance", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("Nissan Tiida 1.5L CVT", fontSize = 11.sp, color = KoPilotClusterTokens.SecondaryText)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showHistoryDialog = true }) {
                        Icon(Icons.Default.History, contentDescription = "Service History", tint = KoPilotClusterTokens.PrimaryAccent)
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
            // Vehicle Odometer Card
            MaintCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Vehicle Odometer", color = KoPilotClusterTokens.SecondaryText, fontSize = 12.sp)
                        Text(
                            text = s.officialOdometerKm?.let { "%,d km".format(Locale.US, it.toInt()) } ?: "Not Set",
                            color = KoPilotClusterTokens.PrimaryText,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                        )
                        val sourceLabel = when (s.officialOdometerSource) {
                            "ECU" -> OdometerSource.ECU.label
                            "MANUAL" -> OdometerSource.MANUAL.label
                            else -> OdometerSource.UNSET.label
                        }
                        val ageStr = if (s.officialOdometerTimestampMillis > 0L) {
                            " • Updated ${dateFmt.format(Date(s.officialOdometerTimestampMillis))}"
                        } else ""
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Icon(
                                imageVector = if (s.officialOdometerSource == "ECU") Icons.Default.CheckCircle else Icons.Default.DirectionsCar,
                                contentDescription = null,
                                tint = if (s.officialOdometerSource == "ECU") KoPilotClusterTokens.SmoothRoad else KoPilotClusterTokens.PrimaryAccent,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = "$sourceLabel$ageStr",
                                color = if (s.officialOdometerSource == "ECU") KoPilotClusterTokens.SmoothRoad else KoPilotClusterTokens.SecondaryText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    OutlinedButton(onClick = { showOdometerDialog = true }) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Update")
                    }
                }
            }

            // Next Service Card
            val next = state.evaluatedServices.firstOrNull()
            if (next != null) {
                val badgeColor = when (next.urgency) {
                    ServiceUrgency.OVERDUE, ServiceUrgency.DUE_SOON -> Color(0xFFEF4444)
                    ServiceUrgency.REMINDER_100, ServiceUrgency.REMINDER_500 -> KoPilotClusterTokens.RoughRoad
                    ServiceUrgency.REMINDER_1000 -> KoPilotClusterTokens.ModerateRoad
                    ServiceUrgency.OK -> KoPilotClusterTokens.SmoothRoad
                    ServiceUrgency.UNCONFIGURED -> KoPilotClusterTokens.SecondaryText
                }
                MaintCard(borderColor = badgeColor.copy(alpha = 0.5f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Build, contentDescription = null, tint = KoPilotClusterTokens.PrimaryAccent, modifier = Modifier.size(18.dp))
                            Text("Next Service", color = KoPilotClusterTokens.PrimaryText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                        Surface(color = badgeColor.copy(alpha = 0.2f), shape = RoundedCornerShape(8.dp)) {
                            Text(
                                text = next.urgency.label,
                                color = badgeColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(next.schedule.title, color = KoPilotClusterTokens.PrimaryText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        text = buildString {
                            append(next.kmRemaining?.let { "Due in %,d km".format(Locale.US, it.toInt()) } ?: "Set odometer to track km")
                            append(" or ${next.daysRemaining} days • At %,d km".format(Locale.US, next.schedule.nextServiceOdometerKm.toInt()))
                        },
                        color = KoPilotClusterTokens.SecondaryText,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { next.progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = badgeColor,
                        trackColor = KoPilotClusterTokens.Background,
                    )
                }
            }

            // Maintenance Schedule List (all categories)
            MaintCard {
                Text("Maintenance Schedule", color = KoPilotClusterTokens.PrimaryText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                state.evaluatedServices.forEach { item ->
                    val statusColor = when (item.urgency) {
                        ServiceUrgency.OVERDUE, ServiceUrgency.DUE_SOON -> Color(0xFFEF4444)
                        ServiceUrgency.REMINDER_100, ServiceUrgency.REMINDER_500, ServiceUrgency.REMINDER_1000 -> KoPilotClusterTokens.ModerateRoad
                        else -> KoPilotClusterTokens.SmoothRoad
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showAddServiceDialog = item.schedule.category }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.schedule.title, color = KoPilotClusterTokens.PrimaryText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(
                                text = "Every %,d km / ${item.schedule.intervalMonths} mo".format(Locale.US, item.schedule.intervalKm),
                                color = KoPilotClusterTokens.SecondaryText,
                                fontSize = 11.sp,
                            )
                        }
                        Text(
                            text = item.kmRemaining?.let { "%,d km left".format(Locale.US, it.toInt()) } ?: "${item.daysRemaining}d left",
                            color = statusColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            Button(
                onClick = { showAddServiceDialog = MaintenanceCategory.ENGINE_OIL },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = KoPilotClusterTokens.PrimaryAccent, contentColor = KoPilotClusterTokens.Background),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Add Service Record", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MaintCard(
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
