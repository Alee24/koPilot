package ke.co.kopilot.feature.sensors.wearable

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ke.co.kopilot.core.designsystem.theme.KoPilotClusterTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 5 — Dedicated Smartwatch & Health Screen.
 *
 * Sections:
 * 1. Device Section: Connected smartwatch name, connection status, battery %, last sync time, RSSI signal,
 *    Scan / Connect / Disconnect / Forget / Sync actions.
 * 2. Heart-Rate Section: Current BPM (`--` or `No current reading` when disconnected/stale), live measurement indicator,
 *    heart-rate trend graph with missing-data gap breaks, Min/Max/Avg BPM, and last measurement timestamp.
 * 3. Sleep Section: Last recorded sleep session, total duration, start and wake times, sleep-stage breakdown
 *    (explicitly distinguishing unavailable stages from zero-duration stages), 7-day sleep chart, and sleep history.
 * 4. Diagnostics Section: BLE connection state, discovered GATT services, `0x2A37` Heart Rate characteristic availability,
 *    current health-data source, Health Connect permission state, last successful measurement & sleep sync,
 *    and error messages with recommended corrective actions.
 *
 * Strictly uses `KoPilotClusterTokens` design system with zero emojis and zero fabricated sensor data.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SmartwatchHealthScreen(
    onBack: () -> Unit,
    bluetoothPermissionGranted: Boolean,
    onRequestBluetoothPermissions: () -> Unit = {},
    viewModel: SmartwatchHealthViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val diag by viewModel.diagnostics.collectAsStateWithLifecycle()
    val discovered by viewModel.discoveredWatches.collectAsStateWithLifecycle()
    val hr by viewModel.heartRateState.collectAsStateWithLifecycle()
    val sleep by viewModel.sleepState.collectAsStateWithLifecycle()
    val savedDevices by viewModel.savedDevices.collectAsStateWithLifecycle()
    val connEvents by viewModel.connectionEvents.collectAsStateWithLifecycle()
    val syncHistory by viewModel.syncHistory.collectAsStateWithLifecycle()

    var showDeleteConfirm by remember { mutableStateOf(false) }

    val healthPermissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) {
        viewModel.syncHealthConnectNow()
    }

    LaunchedEffect(bluetoothPermissionGranted) {
        viewModel.onScreenOpened(bluetoothPermissionGranted)
    }

    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val dateTimeFmt = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }
    val shortDateFmt = remember { SimpleDateFormat("EEE dd MMM", Locale.getDefault()) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Stored Health Data") },
            text = {
                Text(
                    "This permanently deletes all locally stored heart-rate measurements, sleep sessions, sleep stages, and wearable synchronization logs from this device.",
                )
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteAllStoredHealthData()
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = KoPilotClusterTokens.VeryRough),
                ) {
                    Text("Delete All", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
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
                        Text(
                            text = "Smartwatch & Health",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        )
                        Text(
                            text = "BLE GATT Heart Rate (0x180D) & Android Health Connect Sleep Analytics",
                            fontSize = 11.sp,
                            color = KoPilotClusterTokens.SecondaryText,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
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
            // =========================================================================
            // 1. DEVICE SECTION
            // =========================================================================
            WearableSectionCard {
                val isConnected = diag.connectionState == SmartwatchConnectionState.SUBSCRIBED_LIVE ||
                    diag.connectionState == SmartwatchConnectionState.CONNECTED_NO_HR_SERVICE
                val isScanning = diag.connectionState == SmartwatchConnectionState.SCANNING
                val statusColor = when (diag.connectionState) {
                    SmartwatchConnectionState.SUBSCRIBED_LIVE -> KoPilotClusterTokens.SmoothRoad
                    SmartwatchConnectionState.CONNECTED_NO_HR_SERVICE,
                    SmartwatchConnectionState.RECONNECTING,
                    SmartwatchConnectionState.SCANNING,
                    SmartwatchConnectionState.CONNECTING_GATT,
                    SmartwatchConnectionState.DISCOVERING_SERVICES -> KoPilotClusterTokens.ModerateRoad
                    SmartwatchConnectionState.CONNECTION_ERROR,
                    SmartwatchConnectionState.BLUETOOTH_DISABLED,
                    SmartwatchConnectionState.PERMISSION_REQUIRED -> KoPilotClusterTokens.VeryRough
                    else -> KoPilotClusterTokens.SecondaryText
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "SMARTWATCH DEVICE",
                            style = MaterialTheme.typography.labelSmall,
                            color = KoPilotClusterTokens.SecondaryText,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = diag.connectedName ?: savedDevices.firstOrNull()?.deviceName ?: "No Smartwatch Selected",
                            style = MaterialTheme.typography.titleMedium,
                            color = KoPilotClusterTokens.PrimaryText,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        val hwLine = listOfNotNull(
                            diag.manufacturerName,
                            diag.modelNumber,
                            diag.firmwareRevision?.let { "FW $it" },
                            diag.connectedAddress,
                        ).joinToString(" | ")
                        if (hwLine.isNotBlank()) {
                            Text(
                                text = hwLine,
                                style = MaterialTheme.typography.labelSmall,
                                color = KoPilotClusterTokens.SecondaryText,
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = statusColor.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, statusColor),
                    ) {
                        Text(
                            text = diag.connectionState.displayName,
                            color = statusColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }

                Text(
                    text = diag.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = KoPilotClusterTokens.SecondaryText,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    WearableKpiTile(
                        label = "Watch Battery (0x180F)",
                        value = diag.batteryPercent?.let { "$it%" }
                            ?: if (isConnected && !diag.batteryServiceAvailable) "Not exposed by watch" else "--",
                        accent = KoPilotClusterTokens.PrimaryAccent,
                        modifier = Modifier.weight(1f),
                    )
                    WearableKpiTile(
                        label = "BLE Signal (RSSI)",
                        value = diag.rssiDbm?.let { "$it dBm" } ?: "--",
                        accent = KoPilotClusterTokens.PrimaryText,
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    WearableKpiTile(
                        label = "Last BLE Heart Rate",
                        value = (diag.lastMeasurementAtMillis ?: hr.lastMeasurementTimestampMillis)?.let { timeFmt.format(Date(it)) } ?: "Never",
                        accent = KoPilotClusterTokens.PrimaryText,
                        modifier = Modifier.weight(1f),
                    )
                    WearableKpiTile(
                        label = "Last Sleep Sync",
                        value = sleep.lastSyncTimestampMillis?.let { timeFmt.format(Date(it)) } ?: "Not synced",
                        accent = KoPilotClusterTokens.SecondaryAccent,
                        modifier = Modifier.weight(1f),
                    )
                }

                // Clean, uniform 2-row action grid (replaces uneven wrapping pills)
                val targetAddr = diag.connectedAddress ?: savedDevices.firstOrNull()?.deviceAddress
                val targetName = diag.connectedName ?: savedDevices.firstOrNull()?.deviceName ?: "Smartwatch"

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!bluetoothPermissionGranted) {
                        Button(
                            onClick = onRequestBluetoothPermissions,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = KoPilotClusterTokens.PrimaryAccent,
                                contentColor = KoPilotClusterTokens.Background,
                            ),
                        ) {
                            Text("Grant Bluetooth", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = {
                                if (isScanning) {
                                    viewModel.stopBleScan(bluetoothPermissionGranted)
                                } else {
                                    viewModel.startBleScan(bluetoothPermissionGranted)
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = KoPilotClusterTokens.PrimaryAccent,
                                contentColor = KoPilotClusterTokens.Background,
                            ),
                        ) {
                            Text(
                                text = if (isScanning) "Stop Scan" else "Scan Watches",
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    if (isConnected || diag.connectionState == SmartwatchConnectionState.RECONNECTING) {
                        OutlinedButton(
                            onClick = { viewModel.disconnectWatch() },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Disconnect", color = KoPilotClusterTokens.PrimaryText, fontWeight = FontWeight.SemiBold)
                        }
                    } else if (!targetAddr.isNullOrBlank() && bluetoothPermissionGranted) {
                        OutlinedButton(
                            onClick = { viewModel.connectWatch(targetAddr, targetName, bluetoothPermissionGranted) },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Reconnect", color = KoPilotClusterTokens.PrimaryAccent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = { viewModel.syncHealthConnectNow() },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Sync Sleep", color = KoPilotClusterTokens.SecondaryAccent, fontWeight = FontWeight.SemiBold)
                    }
                    if (!targetAddr.isNullOrBlank()) {
                        OutlinedButton(
                            onClick = { viewModel.forgetWatch(targetAddr) },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Forget Watch", color = KoPilotClusterTokens.VeryRough, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                // Discovered Nearby BLE Smartwatches List
                if (discovered.isNotEmpty()) {
                    HorizontalDivider(color = KoPilotClusterTokens.ElevatedSurface)
                    Text(
                        text = "Discovered BLE Devices (${discovered.size}) — Tap to Connect:",
                        style = MaterialTheme.typography.labelMedium,
                        color = KoPilotClusterTokens.PrimaryText,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        discovered.take(10).forEach { item ->
                            Surface(
                                onClick = {
                                    viewModel.connectWatch(item.address, item.name, bluetoothPermissionGranted)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = KoPilotClusterTokens.ElevatedSurface,
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = if (item.advertisesHeartRateService) {
                                        KoPilotClusterTokens.PrimaryAccent
                                    } else {
                                        Color.Transparent
                                    },
                                ),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.name,
                                            color = KoPilotClusterTokens.PrimaryText,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                        )
                                        Text(
                                            text = buildString {
                                                append(item.address)
                                                append(" | ${item.rssiDbm} dBm")
                                                if (item.isBonded) append(" | Paired")
                                                if (item.advertisesHeartRateService) append(" | HR 0x180D Advertised")
                                            },
                                            color = KoPilotClusterTokens.SecondaryText,
                                            fontSize = 11.sp,
                                        )
                                    }
                                    Text(
                                        text = "CONNECT",
                                        color = KoPilotClusterTokens.PrimaryAccent,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // 2. HEART-RATE SECTION
            // =========================================================================
            WearableSectionCard {
                val liveColor = if (hr.isLiveReading && !hr.isStale) {
                    KoPilotClusterTokens.SmoothRoad
                } else {
                    KoPilotClusterTokens.SecondaryText
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = "LIVE HEART-RATE MONITORING",
                            style = MaterialTheme.typography.labelSmall,
                            color = KoPilotClusterTokens.SecondaryText,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = hr.displayBpmText,
                                color = if (hr.isLiveReading && !hr.isStale) {
                                    KoPilotClusterTokens.PrimaryText
                                } else {
                                    KoPilotClusterTokens.SecondaryText
                                },
                                fontSize = 38.sp,
                                fontWeight = FontWeight.ExtraBold,
                            )
                            Text(
                                text = if (hr.isLiveReading && !hr.isStale) "BPM" else "No current reading",
                                color = KoPilotClusterTokens.SecondaryText,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = liveColor.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, liveColor),
                    ) {
                        Text(
                            text = when {
                                hr.isLiveReading && !hr.isStale -> "LIVE 0x2A37"
                                hr.isStale -> "STALE (>15s)"
                                else -> "OFFLINE"
                            },
                            color = liveColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }

                val contactText = when {
                    !hr.sensorContactSupported -> "Sensor contact bit: Not reported by watch"
                    hr.sensorContactDetected == true -> "Sensor contact: Wrist contact confirmed"
                    hr.sensorContactDetected == false -> "Sensor contact: No wrist contact detected"
                    else -> "Sensor contact: Waiting for reading"
                }
                val lastTsText = hr.lastMeasurementTimestampMillis?.let {
                    "Last reading at ${timeFmt.format(Date(it))} (${hr.dataSource})"
                } ?: "No measurements recorded yet"

                Text(
                    text = "$lastTsText | $contactText",
                    style = MaterialTheme.typography.bodySmall,
                    color = KoPilotClusterTokens.SecondaryText,
                )

                hr.invalidReadingNotice?.let { notice ->
                    Text(
                        text = "Notice: $notice",
                        style = MaterialTheme.typography.bodySmall,
                        color = KoPilotClusterTokens.ModerateRoad,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    WearableKpiTile(
                        label = "Minimum BPM",
                        value = hr.minBpm?.let { "$it BPM" } ?: "--",
                        accent = KoPilotClusterTokens.PrimaryAccent,
                        modifier = Modifier.weight(1f),
                    )
                    WearableKpiTile(
                        label = "Average BPM",
                        value = hr.avgBpm?.let { "$it BPM" } ?: "--",
                        accent = KoPilotClusterTokens.PrimaryText,
                        modifier = Modifier.weight(1f),
                    )
                    WearableKpiTile(
                        label = "Maximum BPM",
                        value = hr.maxBpm?.let { "$it BPM" } ?: "--",
                        accent = KoPilotClusterTokens.ModerateRoad,
                        modifier = Modifier.weight(1f),
                    )
                }

                // Heart-Rate Trend Graph
                Text(
                    text = "Heart-Rate Trend Graph (${hr.historyPoints.size} samples — gaps indicate disconnection intervals):",
                    style = MaterialTheme.typography.labelSmall,
                    color = KoPilotClusterTokens.SecondaryText,
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = KoPilotClusterTokens.ElevatedSurface,
                ) {
                    val pts = hr.historyPoints
                    if (pts.size < 2) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "Connect a BLE smartwatch broadcasting Heart Rate (0x180D) to plot live BPM.",
                                color = KoPilotClusterTokens.SecondaryText,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    } else {
                        val lineColor = KoPilotClusterTokens.PrimaryAccent
                        val gridColor = KoPilotClusterTokens.SecondaryText.copy(alpha = 0.2f)
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(10.dp),
                        ) {
                            val w = size.width
                            val h = size.height
                            drawLine(gridColor, Offset(0f, h * 0.25f), Offset(w, h * 0.25f), strokeWidth = 1f)
                            drawLine(gridColor, Offset(0f, h * 0.50f), Offset(w, h * 0.50f), strokeWidth = 1f)
                            drawLine(gridColor, Offset(0f, h * 0.75f), Offset(w, h * 0.75f), strokeWidth = 1f)

                            val minScale = 45f
                            val maxScale = 185f
                            val stepX = w / (pts.size - 1).coerceAtLeast(1)

                            for (i in 1 until pts.size) {
                                val prev = pts[i - 1]
                                if (prev.isGapAfter) continue // Leave visible gap when disconnected
                                val curr = pts[i]
                                val x1 = (i - 1) * stepX
                                val x2 = i * stepX
                                val y1 = h * (1f - ((prev.bpm - minScale) / (maxScale - minScale)).coerceIn(0f, 1f))
                                val y2 = h * (1f - ((curr.bpm - minScale) / (maxScale - minScale)).coerceIn(0f, 1f))
                                drawLine(
                                    color = lineColor,
                                    start = Offset(x1, y1),
                                    end = Offset(x2, y2),
                                    strokeWidth = 2.4.dp.toPx(),
                                    cap = StrokeCap.Round,
                                )
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // 3. SLEEP SECTION (ANDROID HEALTH CONNECT & OFFLINE CACHE)
            // =========================================================================
            WearableSectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "SLEEP ANALYTICS & 7-DAY HISTORY",
                            style = MaterialTheme.typography.labelSmall,
                            color = KoPilotClusterTokens.SecondaryText,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = sleep.latestSession?.let {
                                "Last Sleep: ${SleepAnalyticsCalculator.formatDurationHoursMinutes(it.totalSleepDurationMinutes)}"
                            } ?: "No Synchronized Sleep Sessions",
                            style = MaterialTheme.typography.titleMedium,
                            color = KoPilotClusterTokens.PrimaryText,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = KoPilotClusterTokens.SecondaryAccent.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, KoPilotClusterTokens.SecondaryAccent),
                    ) {
                        Text(
                            text = sleep.healthConnectAvailability.displayName,
                            color = KoPilotClusterTokens.SecondaryAccent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }

                Text(
                    text = sleep.lastSyncStatusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = KoPilotClusterTokens.SecondaryText,
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (sleep.healthConnectAvailability) {
                        HealthConnectAvailabilityState.AVAILABLE -> {
                            if (!sleep.sleepPermissionGranted || !sleep.heartRatePermissionGranted) {
                                Button(
                                    onClick = {
                                        runCatching {
                                            healthPermissionLauncher.launch(viewModel.requiredHealthPermissions)
                                        }.onFailure {
                                            runCatching {
                                                context.startActivity(viewModel.buildHealthConnectSettingsIntent())
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = KoPilotClusterTokens.PrimaryAccent,
                                        contentColor = KoPilotClusterTokens.Background,
                                    ),
                                ) {
                                    Text("Authorize Health Connect", fontWeight = FontWeight.Bold)
                                }
                            }
                            OutlinedButton(onClick = { viewModel.syncHealthConnectNow() }) {
                                Text(
                                    text = if (sleep.isSyncing) "Syncing..." else "Sync Sleep Now",
                                    color = KoPilotClusterTokens.PrimaryAccent,
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(viewModel.buildHealthConnectSettingsIntent())
                                    }
                                },
                            ) {
                                Text("Health Connect Settings", color = KoPilotClusterTokens.PrimaryText)
                            }
                        }

                        HealthConnectAvailabilityState.PROVIDER_UPDATE_REQUIRED,
                        HealthConnectAvailabilityState.NOT_INSTALLED -> {
                            Button(
                                onClick = {
                                    val marketIntent = Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("market://details?id=${SleepAnalyticsRepository.HEALTH_CONNECT_PROVIDER_PACKAGE}"),
                                    )
                                    runCatching { context.startActivity(marketIntent) }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = KoPilotClusterTokens.PrimaryAccent,
                                    contentColor = KoPilotClusterTokens.Background,
                                ),
                            ) {
                                Text("Install / Update Health Connect", fontWeight = FontWeight.Bold)
                            }
                        }

                        else -> {}
                    }
                }

                val latest = sleep.latestSession
                if (latest != null) {
                    HorizontalDivider(color = KoPilotClusterTokens.ElevatedSurface)
                    Text(
                        text = "Latest Recorded Session (${latest.sourceAppName})",
                        style = MaterialTheme.typography.labelMedium,
                        color = KoPilotClusterTokens.PrimaryText,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Bedtime: ${dateTimeFmt.format(Date(latest.startTimeMillis))} -> Wake: ${dateTimeFmt.format(Date(latest.endTimeMillis))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = KoPilotClusterTokens.SecondaryText,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        WearableKpiTile(
                            label = "Total Sleep",
                            value = SleepAnalyticsCalculator.formatDurationHoursMinutes(latest.totalSleepDurationMinutes),
                            accent = KoPilotClusterTokens.PrimaryAccent,
                            modifier = Modifier.weight(1f),
                        )
                        WearableKpiTile(
                            label = "Sleep Efficiency",
                            value = "${latest.sleepEfficiencyPercent}%",
                            accent = KoPilotClusterTokens.SmoothRoad,
                            modifier = Modifier.weight(1f),
                        )
                        WearableKpiTile(
                            label = "Interruptions",
                            value = "${latest.interruptionsCount}",
                            accent = KoPilotClusterTokens.PrimaryText,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    if (latest.stagesAvailable) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            WearableKpiTile(
                                label = "Deep Sleep",
                                value = latest.deepSleepMinutes?.let { SleepAnalyticsCalculator.formatDurationHoursMinutes(it) } ?: "0m",
                                accent = KoPilotClusterTokens.SecondaryAccent,
                                modifier = Modifier.weight(1f),
                            )
                            WearableKpiTile(
                                label = "Light Sleep",
                                value = latest.lightSleepMinutes?.let { SleepAnalyticsCalculator.formatDurationHoursMinutes(it) } ?: "0m",
                                accent = KoPilotClusterTokens.PrimaryAccent,
                                modifier = Modifier.weight(1f),
                            )
                            WearableKpiTile(
                                label = "REM Sleep",
                                value = latest.remSleepMinutes?.let { SleepAnalyticsCalculator.formatDurationHoursMinutes(it) } ?: "0m",
                                accent = KoPilotClusterTokens.SmoothRoad,
                                modifier = Modifier.weight(1f),
                            )
                            WearableKpiTile(
                                label = "Awake",
                                value = latest.awakeMinutes?.let { SleepAnalyticsCalculator.formatDurationHoursMinutes(it) } ?: "0m",
                                accent = KoPilotClusterTokens.ModerateRoad,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = KoPilotClusterTokens.ElevatedSurface,
                        ) {
                            Text(
                                text = "Sleep stages unavailable: ${latest.sourceAppName} recorded total sleep duration without granular Deep/Light/REM stage records.",
                                style = MaterialTheme.typography.bodySmall,
                                color = KoPilotClusterTokens.SecondaryText,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                }

                // 7-Day Sleep Chart & Trend
                if (sleep.sevenDayHistory.isNotEmpty()) {
                    HorizontalDivider(color = KoPilotClusterTokens.ElevatedSurface)
                    Text(
                        text = "7-Day Sleep Trend: ${sleep.trendAnalysis.trendSummary}",
                        style = MaterialTheme.typography.bodySmall,
                        color = KoPilotClusterTokens.PrimaryText,
                        fontWeight = FontWeight.Medium,
                    )

                    val chartSessions = sleep.sevenDayHistory.reversed()
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = KoPilotClusterTokens.ElevatedSurface,
                    ) {
                        val barColor = KoPilotClusterTokens.SecondaryAccent
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                        ) {
                            val maxMins = 600f // 10 hours reference scale
                            val slotW = size.width / chartSessions.size.coerceAtLeast(1)
                            val barW = (slotW * 0.55f).coerceAtMost(38.dp.toPx())
                            chartSessions.forEachIndexed { idx, sess ->
                                val ratio = (sess.totalSleepDurationMinutes.toFloat() / maxMins).coerceIn(0.06f, 1f)
                                val barH = size.height * ratio
                                val left = idx * slotW + (slotW - barW) * 0.5f
                                val top = size.height - barH
                                drawRoundRect(
                                    color = barColor,
                                    topLeft = Offset(left, top),
                                    size = Size(barW, barH),
                                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                                )
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        sleep.sevenDayHistory.forEach { sess ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = KoPilotClusterTokens.ElevatedSurface,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = shortDateFmt.format(Date(sess.endTimeMillis)),
                                            color = KoPilotClusterTokens.PrimaryText,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                        )
                                        Text(
                                            text = "${sess.sourceAppName} | Efficiency ${sess.sleepEfficiencyPercent}% | ${if (sess.stagesAvailable) "Stages recorded" else "Duration only"}",
                                            color = KoPilotClusterTokens.SecondaryText,
                                            fontSize = 11.sp,
                                        )
                                    }
                                    Text(
                                        text = SleepAnalyticsCalculator.formatDurationHoursMinutes(sess.totalSleepDurationMinutes),
                                        color = KoPilotClusterTokens.PrimaryAccent,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                        }
                    }
                }

                Text(
                    text = "Wellness Disclaimer: Sleep duration and heart-rate summaries are provided for informational driver context only and do not constitute a medical diagnosis or fatigue certification.",
                    style = MaterialTheme.typography.labelSmall,
                    color = KoPilotClusterTokens.SecondaryText,
                )
            }

            // =========================================================================
            // 4. DIAGNOSTICS SECTION
            // =========================================================================
            WearableSectionCard {
                Text(
                    text = "SMARTWATCH & HEALTH CONNECT DIAGNOSTICS",
                    style = MaterialTheme.typography.labelSmall,
                    color = KoPilotClusterTokens.SecondaryText,
                    fontWeight = FontWeight.Bold,
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = KoPilotClusterTokens.ElevatedSurface,
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "BLE Connection State: ${diag.connectionState.name} (${diag.connectionState.displayName})",
                            fontSize = 12.sp,
                            color = KoPilotClusterTokens.PrimaryText,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Heart Rate Service (0x180D): ${if (diag.heartRateServiceAvailable) "Available" else "Not Exposed"} | Characteristic (0x2A37): ${if (diag.heartRateCharacteristicAvailable) "Available" else "Not Exposed"}",
                            fontSize = 11.sp,
                            color = KoPilotClusterTokens.SecondaryText,
                        )
                        Text(
                            text = "CCCD 0x2902 Notifications: ${if (diag.heartRateNotificationsSubscribed) "Subscribed (Push Active)" else "Not Subscribed"}",
                            fontSize = 11.sp,
                            color = KoPilotClusterTokens.SecondaryText,
                        )
                        Text(
                            text = "Discovered GATT Services (${diag.discoveredGattServices.size}): ${diag.discoveredGattServices.ifEmpty { listOf("None discovered yet") }.joinToString(", ")}",
                            fontSize = 11.sp,
                            color = KoPilotClusterTokens.SecondaryText,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            text = "Health Data Source: ${sleep.activeDataSourceLabel}",
                            fontSize = 11.sp,
                            color = KoPilotClusterTokens.SecondaryText,
                        )
                        Text(
                            text = "Health Connect Permissions: Sleep=${if (sleep.sleepPermissionGranted) "GRANTED" else "NOT GRANTED"} | HeartRate=${if (sleep.heartRatePermissionGranted) "GRANTED" else "NOT GRANTED"}",
                            fontSize = 11.sp,
                            color = KoPilotClusterTokens.SecondaryText,
                        )
                        val installedCompanions = sleep.installedCompanionApps.filter { it.installed }
                        Text(
                            text = "Installed Companion Apps: ${if (installedCompanions.isEmpty()) "None detected" else installedCompanions.joinToString(", ") { it.appName }}",
                            fontSize = 11.sp,
                            color = KoPilotClusterTokens.SecondaryText,
                        )
                        Text(
                            text = "Direct BLE Sleep Note: ${sleep.directBleSleepProtocolStatus}",
                            fontSize = 11.sp,
                            color = KoPilotClusterTokens.SecondaryText,
                        )
                    }
                }

                if (diag.lastErrorMessage != null || diag.recommendedAction != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = KoPilotClusterTokens.ModerateRoad.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, KoPilotClusterTokens.ModerateRoad),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            diag.lastErrorMessage?.let { err ->
                                Text(
                                    text = "Diagnostic Issue: $err",
                                    color = KoPilotClusterTokens.ModerateRoad,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                )
                            }
                            diag.recommendedAction?.let { action ->
                                Text(
                                    text = "Recommended Action: $action",
                                    color = KoPilotClusterTokens.PrimaryText,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }

                if (connEvents.isNotEmpty()) {
                    Text(
                        text = "Recent BLE Connection Events (${connEvents.size}):",
                        style = MaterialTheme.typography.labelMedium,
                        color = KoPilotClusterTokens.PrimaryText,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = KoPilotClusterTokens.ElevatedSurface,
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            connEvents.take(6).forEach { ev ->
                                Text(
                                    text = "[${timeFmt.format(Date(ev.timestampMillis))}] ${ev.eventType}: ${ev.detail}",
                                    color = KoPilotClusterTokens.SecondaryText,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }

                if (syncHistory.isNotEmpty()) {
                    Text(
                        text = "Recent Health Synchronization History:",
                        style = MaterialTheme.typography.labelMedium,
                        color = KoPilotClusterTokens.PrimaryText,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = KoPilotClusterTokens.ElevatedSurface,
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            syncHistory.take(5).forEach { sh ->
                                Text(
                                    text = "[${dateTimeFmt.format(Date(sh.syncedAtMillis))}] ${sh.status} (${sh.recordsImportedCount} rec): ${sh.detailMessage}",
                                    color = KoPilotClusterTokens.SecondaryText,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Delete All Stored Health & Wearable Data",
                        color = KoPilotClusterTokens.VeryRough,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun WearableSectionCard(
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = KoPilotClusterTokens.CardSurface,
        border = BorderStroke(1.dp, KoPilotClusterTokens.ElevatedSurface),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun WearableKpiTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = KoPilotClusterTokens.ElevatedSurface,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = KoPilotClusterTokens.SecondaryText,
                fontSize = 10.sp,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = accent,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
        }
    }
}
