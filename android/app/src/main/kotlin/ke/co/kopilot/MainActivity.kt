package ke.co.kopilot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.SatelliteAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.compose.currentBackStackEntryAsState
import ke.co.kopilot.core.designsystem.theme.KoPilotClusterTokens
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import ke.co.kopilot.core.database.preferences.PreferencesRepository
import ke.co.kopilot.core.designsystem.theme.KoPilotTheme
import ke.co.kopilot.feature.dashboard.DashboardScreen
import ke.co.kopilot.feature.dashboard.DashboardViewModel
import ke.co.kopilot.feature.dashboard.NavigationScreen
import ke.co.kopilot.feature.dashboard.PerformanceThermalScreen
import ke.co.kopilot.feature.dashboard.pretrip.PreTripStartupScreen
import ke.co.kopilot.feature.diagnostics.ObdScreen
import ke.co.kopilot.feature.sensors.DrivingIntelligenceScreen
import ke.co.kopilot.feature.sensors.SensorsScreen
import ke.co.kopilot.feature.sensors.wearable.SmartwatchHealthScreen
import ke.co.kopilot.feature.settings.SettingsScreen
import ke.co.kopilot.feature.soundstudio.AudioSettingsScreen
import ke.co.kopilot.feature.soundstudio.ImportSoundPackScreen
import ke.co.kopilot.feature.soundstudio.SoundLibraryScreen
import ke.co.kopilot.feature.soundstudio.SoundProfileDetailsScreen
import ke.co.kopilot.feature.soundstudio.SoundStudioScreen
import ke.co.kopilot.feature.soundstudio.SoundStudioViewModel
import ke.co.kopilot.feature.trips.TripsScreen
import ke.co.kopilot.intelligence.VehicleIntelligenceScreen
import ke.co.kopilot.safety.AccidentDiagnosticsScreen
import ke.co.kopilot.safety.DrivingSafetyMonitoringService
import ke.co.kopilot.safety.EmergencyContactsScreen
import ke.co.kopilot.safety.EmergencyCountdownScreen
import ke.co.kopilot.safety.EmergencyNotificationRecord
import ke.co.kopilot.safety.EmergencySmsManager
import ke.co.kopilot.safety.LiveMapScreen
import ke.co.kopilot.safety.SafetyAndEmergencyScreen
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var prefsRepo: PreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always-on mode: prevent screen from dimming or turning off while KoPilot is open
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            val settings by prefsRepo.settingsFlow.collectAsStateWithLifecycle(
                initialValue = ke.co.kopilot.core.database.preferences.KoPilotSettings(),
            )
            var showSplash by remember { mutableStateOf(true) }

            LaunchedEffect(Unit) {
                delay(1400L)
                showSplash = false
            }

            KoPilotTheme(darkTheme = settings.darkTheme) {
                if (showSplash) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF040A12)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.kopilot_logo_splash),
                                contentDescription = "KoPilot Logo",
                                modifier = Modifier
                                    .size(220.dp)
                                    .clip(RoundedCornerShape(36.dp)),
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = "Your Intelligent Driving Assistant",
                                color = Color(0xFF18E2B8),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    return@KoPilotTheme
                }

                val navController = rememberNavController()
                val dashboardViewModel: DashboardViewModel = hiltViewModel()
                val dashState by dashboardViewModel.uiState.collectAsStateWithLifecycle()

                LaunchedEffect(dashState.isDrivingSessionActive, settings.accidentMonitoringEnabled) {
                    if (dashState.isDrivingSessionActive && settings.accidentMonitoringEnabled) {
                        DrivingSafetyMonitoringService.start(this@MainActivity)
                    } else {
                        DrivingSafetyMonitoringService.stop(this@MainActivity)
                    }
                }

                LaunchedEffect(dashState.emergencyDispatchTriggerId) {
                    val triggerId = dashState.emergencyDispatchTriggerId ?: return@LaunchedEffect
                    val assessment = dashState.pendingAccident
                    if (assessment != null) {
                        val contacts = EmergencySmsManager.parseContacts(settings.emergencyContactsJson)
                        val fix = dashState.locationState.currentFix
                        val msg = EmergencySmsManager.buildEmergencyMessage(
                            assessment = assessment,
                            latitude = fix?.latitude,
                            longitude = fix?.longitude,
                            isTest = assessment.isSimulation,
                        )
                        val res = EmergencySmsManager.sendEmergencySms(
                            context = this@MainActivity,
                            incidentId = assessment.incidentId.ifBlank { triggerId },
                            contacts = contacts,
                            message = msg,
                            autoSmsOptedIn = settings.emergencySmsEnabled,
                            isSimulation = assessment.isSimulation,
                        )
                        dashboardViewModel.recordSafetySmsOutcome(
                            incidentId = assessment.incidentId.ifBlank { triggerId },
                            smsAttempted = !assessment.isSimulation && settings.emergencySmsEnabled,
                            statusLabel = res.statusLabel,
                        )
                        val existingHistory = EmergencySmsManager.parseHistory(settings.emergencyHistoryJson)
                        val record = EmergencyNotificationRecord(
                            id = UUID.randomUUID().toString(),
                            timestampMillis = System.currentTimeMillis(),
                            eventLabel = assessment.eventClass.label,
                            confidencePercent = assessment.confidencePercent,
                            status = res.statusLabel,
                            recipients = contacts.joinToString(", ") { it.name }.ifBlank { "None" },
                            detail = assessment.triggeringMeasurements.joinToString(" • "),
                        )
                        prefsRepo.setEmergencyHistoryJson(
                            EmergencySmsManager.encodeHistory(listOf(record) + existingHistory),
                        )
                    }
                    dashboardViewModel.acknowledgeEmergencyDispatch()
                }

                val requiredPermissions = buildList {
                    add(Manifest.permission.CAMERA)
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        add(Manifest.permission.BLUETOOTH_CONNECT)
                        add(Manifest.permission.BLUETOOTH_SCAN)
                    }
                }.toTypedArray()

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions(),
                ) {
                    val btOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    } else true
                    dashboardViewModel.updatePermissions(
                        cameraGranted = hasPermission(Manifest.permission.CAMERA),
                        locationGranted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION),
                        micGranted = hasPermission(Manifest.permission.RECORD_AUDIO),
                        bluetoothGranted = btOk,
                    )
                }

                LaunchedEffect(Unit) {
                    val btOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    } else true
                    dashboardViewModel.updatePermissions(
                        cameraGranted = hasPermission(Manifest.permission.CAMERA),
                        locationGranted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION),
                        micGranted = hasPermission(Manifest.permission.RECORD_AUDIO),
                        bluetoothGranted = btOk,
                    )
                    if (requiredPermissions.any { !hasPermission(it) }) {
                        permissionLauncher.launch(requiredPermissions)
                    }
                }

                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route ?: "dashboard"
                val navigateTopLevel: (String) -> Unit = { route ->
                    navController.navigate(route) {
                        popUpTo("dashboard") { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
                val topLevelDestinations = listOf(
                    TopLevelDestination("dashboard", "Home", Icons.Default.Home),
                    TopLevelDestination("sensors", "Sensors", Icons.Default.SatelliteAlt),
                    TopLevelDestination("obd", "Vehicle", Icons.Default.DirectionsCar),
                    TopLevelDestination("trips", "Trips", Icons.Default.Route),
                    TopLevelDestination("settings", "Settings", Icons.Default.Settings),
                )
                val isSelectedRoute: (String) -> Boolean = { route ->
                    when (route) {
                        "sensors" -> currentRoute in setOf("sensors", "road_quality", "driving_behaviour")
                        else -> currentRoute == route
                    }
                }

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val useRail = maxWidth >= 600.dp
                    val pending = dashState.pendingAccident
                    if (pending != null) {
                        val contacts = remember(settings.emergencyContactsJson) {
                            EmergencySmsManager.parseContacts(settings.emergencyContactsJson)
                        }
                        EmergencyCountdownScreen(
                            assessment = pending,
                            remainingSeconds = dashState.accidentCountdownRemainingSec,
                            totalSeconds = settings.emergencyCountdownSeconds,
                            latitude = dashState.locationState.currentFix?.latitude,
                            longitude = dashState.locationState.currentFix?.longitude,
                            contactsCount = contacts.size,
                            vibrationEnabled = settings.accidentVibrationEnabled,
                            onCancelAlert = { dashboardViewModel.cancelEmergencyCountdown() },
                            onSendAlertNow = { dashboardViewModel.sendEmergencyAlertNow() },
                            onOpenContacts = {
                                dashboardViewModel.cancelEmergencyCountdown()
                                navController.navigate("emergency_contacts")
                            },
                        )
                    } else {
                        Scaffold(
                        containerColor = KoPilotClusterTokens.Background,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        bottomBar = {
                            if (!useRail && currentRoute != "pre_trip_wizard") {
                                NavigationBar(
                                    containerColor = KoPilotClusterTokens.CardSurface,
                                    contentColor = KoPilotClusterTokens.PrimaryText,
                                    windowInsets = WindowInsets.navigationBars,
                                ) {
                                    topLevelDestinations.forEach { dest ->
                                        val selected = isSelectedRoute(dest.route)
                                        NavigationBarItem(
                                            selected = selected,
                                            onClick = { navigateTopLevel(dest.route) },
                                            icon = {
                                                Icon(
                                                    imageVector = dest.icon,
                                                    contentDescription = dest.label,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            },
                                            label = {
                                                Text(
                                                    text = dest.label,
                                                    fontSize = 11.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            },
                                            colors = NavigationBarItemDefaults.colors(
                                                selectedIconColor = KoPilotClusterTokens.PrimaryAccent,
                                                selectedTextColor = KoPilotClusterTokens.PrimaryAccent,
                                                unselectedIconColor = KoPilotClusterTokens.SecondaryText,
                                                unselectedTextColor = KoPilotClusterTokens.SecondaryText,
                                                indicatorColor = KoPilotClusterTokens.ElevatedSurface,
                                            ),
                                        )
                                    }
                                }
                            }
                        },
                    ) { innerPadding ->
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                        ) {
                            if (useRail && currentRoute != "pre_trip_wizard") {
                                NavigationRail(
                                    modifier = Modifier.fillMaxHeight(),
                                    containerColor = KoPilotClusterTokens.CardSurface,
                                    contentColor = KoPilotClusterTokens.PrimaryText,
                                ) {
                                    Spacer(Modifier.height(8.dp))
                                    topLevelDestinations.forEach { dest ->
                                        val selected = isSelectedRoute(dest.route)
                                        NavigationRailItem(
                                            selected = selected,
                                            onClick = { navigateTopLevel(dest.route) },
                                            icon = {
                                                Icon(
                                                    imageVector = dest.icon,
                                                    contentDescription = dest.label,
                                                    modifier = Modifier.size(20.dp),
                                                )
                                            },
                                            label = {
                                                Text(
                                                    text = dest.label,
                                                    fontSize = 11.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            },
                                            colors = NavigationRailItemDefaults.colors(
                                                selectedIconColor = KoPilotClusterTokens.PrimaryAccent,
                                                selectedTextColor = KoPilotClusterTokens.PrimaryAccent,
                                                unselectedIconColor = KoPilotClusterTokens.SecondaryText,
                                                unselectedTextColor = KoPilotClusterTokens.SecondaryText,
                                                indicatorColor = KoPilotClusterTokens.ElevatedSurface,
                                            ),
                                        )
                                    }
                                }
                            }

                            NavHost(
                                navController = navController,
                                startDestination = "pre_trip_wizard",
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                enterTransition = { fadeIn(animationSpec = tween(200)) },
                                exitTransition = { fadeOut(animationSpec = tween(160)) },
                            ) {
                                composable("pre_trip_wizard") {
                                    val btGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        hasPermission(Manifest.permission.BLUETOOTH_CONNECT) &&
                                            hasPermission(Manifest.permission.BLUETOOTH_SCAN)
                                    } else {
                                        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                                    }
                                    PreTripStartupScreen(
                                        cameraPermissionGranted = hasPermission(Manifest.permission.CAMERA),
                                        locationPermissionGranted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION),
                                        bluetoothPermissionGranted = btGranted,
                                        micPermissionGranted = hasPermission(Manifest.permission.RECORD_AUDIO),
                                        onRequestPermissions = {
                                            permissionLauncher.launch(requiredPermissions)
                                        },
                                        onStartTrip = {
                                            dashboardViewModel.startDriving()
                                            navController.navigate("dashboard") {
                                                popUpTo("pre_trip_wizard") { inclusive = true }
                                                launchSingleTop = true
                                            }
                                        },
                                        onSkipToDashboard = {
                                            navController.navigate("dashboard") {
                                                popUpTo("pre_trip_wizard") { inclusive = true }
                                                launchSingleTop = true
                                            }
                                        },
                                        onOpenFullDiagnostics = {
                                            navController.navigate("performance_thermal")
                                        },
                                        onOpenSmartwatchHealth = {
                                            navController.navigate("smartwatch_health")
                                        },
                                    )
                                }
                                composable("dashboard") {
                                    DashboardScreen(
                                        onOpenSettings = { navigateTopLevel("settings") },
                                        onOpenSensors = { navigateTopLevel("sensors") },
                                        onOpenRoadQuality = { navController.navigate("road_quality") },
                                        onOpenDrivingBehaviour = { navController.navigate("driving_behaviour") },
                                        onOpenDrivingIntelligence = { navController.navigate("driving_intelligence") },
                                        onOpenVehicleIntelligence = { navController.navigate("vehicle_intelligence") },
                                        onOpenObd = { navigateTopLevel("obd") },
                                        onOpenTrips = { navigateTopLevel("trips") },
                                        onOpenNavigation = { navController.navigate("navigation") },
                                        onOpenSoundStudio = { navController.navigate("sound_studio") },
                                        onOpenSafetySettings = { navController.navigate("safety_settings") },
                                        onOpenLiveMap = { navController.navigate("live_map") },
                                        onOpenPerformanceThermal = { navController.navigate("performance_thermal") },
                                        onOpenSmartwatchHealth = { navController.navigate("smartwatch_health") },
                                        onCallEmergency = { phone ->
                                            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
                                        },
                                        viewModel = dashboardViewModel,
                                    )
                                }
                                composable("performance_thermal") {
                                    PerformanceThermalScreen(
                                        onBack = { navController.popBackStack() },
                                        viewModel = dashboardViewModel,
                                    )
                                }
                                composable("smartwatch_health") {
                                    val btGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        hasPermission(Manifest.permission.BLUETOOTH_CONNECT) &&
                                            hasPermission(Manifest.permission.BLUETOOTH_SCAN)
                                    } else {
                                        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                                    }
                                    SmartwatchHealthScreen(
                                        onBack = { navController.popBackStack() },
                                        bluetoothPermissionGranted = btGranted,
                                        onRequestBluetoothPermissions = {
                                            permissionLauncher.launch(requiredPermissions)
                                        },
                                    )
                                }
                                composable("trips") {
                                    TripsScreen(onBack = { navController.popBackStack() })
                                }
                                composable("navigation") {
                                    NavigationScreen(
                                        onBack = { navController.popBackStack() },
                                        onStartRouteOnDashboard = {
                                            navController.popBackStack("dashboard", inclusive = false)
                                        },
                                        viewModel = dashboardViewModel,
                                    )
                                }
                                composable("obd") {
                                    ObdScreen(
                                        onBack = { navController.popBackStack() },
                                        onOpenSoundStudio = { navController.navigate("sound_studio") },
                                        onOpenVehicleIntelligence = { navController.navigate("vehicle_intelligence") },
                                    )
                                }
                                composable("sensors") {
                                    SensorsScreen(
                                        onBack = { navController.popBackStack() },
                                        onOpenTrips = { navigateTopLevel("trips") },
                                        initialTab = 0,
                                    )
                                }
                                composable("road_quality") {
                                    SensorsScreen(
                                        onBack = { navController.popBackStack() },
                                        onOpenTrips = { navigateTopLevel("trips") },
                                        initialTab = 1,
                                    )
                                }
                                composable("driving_behaviour") {
                                    SensorsScreen(
                                        onBack = { navController.popBackStack() },
                                        onOpenTrips = { navigateTopLevel("trips") },
                                        initialTab = 2,
                                    )
                                }
                                composable("driving_intelligence") {
                                    DrivingIntelligenceScreen(
                                        onBack = { navController.popBackStack() },
                                    )
                                }
                                composable("vehicle_intelligence") {
                                    VehicleIntelligenceScreen(
                                        onBack = { navController.popBackStack() },
                                        onExportReport = { reportText ->
                                            val share = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_SUBJECT, "KoPilot Vehicle Health Report")
                                                putExtra(Intent.EXTRA_TEXT, reportText)
                                            }
                                            startActivity(Intent.createChooser(share, "Export Vehicle Health Report"))
                                        },
                                    )
                                }
                                composable("settings") {
                                    val btGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
                                    } else {
                                        true
                                    }
                                    SettingsScreen(
                                        onBack = { navController.popBackStack() },
                                        onOpenObd = { navigateTopLevel("obd") },
                                        onOpenSafetySettings = { navController.navigate("safety_settings") },
                                        onOpenEmergencyContacts = { navController.navigate("emergency_contacts") },
                                        onOpenPerformanceThermal = { navController.navigate("performance_thermal") },
                                        onOpenSmartwatchHealth = { navController.navigate("smartwatch_health") },
                                        bluetoothPermissionGranted = btGranted,
                                        onExportJson = { json ->
                                            val share = Intent(Intent.ACTION_SEND).apply {
                                                type = "application/json"
                                                putExtra(Intent.EXTRA_SUBJECT, "KoPilot Trip Export")
                                                putExtra(Intent.EXTRA_TEXT, json)
                                            }
                                            startActivity(Intent.createChooser(share, "Export KoPilot Trips"))
                                        },
                                    )
                                }
                                composable("live_map") {
                                    LiveMapScreen(
                                        state = dashState,
                                        onBack = { navController.popBackStack() },
                                    )
                                }
                                composable("safety_settings") {
                                    SafetyAndEmergencyScreen(
                                        state = dashState,
                                        prefsRepo = prefsRepo,
                                        onRecalibrateMount = { dashboardViewModel.recalibratePhoneMount() },
                                        onTestEmergencyWorkflow = { dashboardViewModel.triggerTestEmergencyWorkflow() },
                                        onOpenContacts = { navController.navigate("emergency_contacts") },
                                        onOpenAccidentDiagnostics = { navController.navigate("accident_diagnostics") },
                                        onBack = { navController.popBackStack() },
                                    )
                                }
                                composable("accident_diagnostics") {
                                    AccidentDiagnosticsScreen(
                                        state = dashState,
                                        onUpdateThresholdConfig = { dashboardViewModel.updateAccidentThresholdConfig(it) },
                                        onRunSimulation = { dashboardViewModel.runSafeAccidentSimulation(it) },
                                        onRecalibrateMount = { dashboardViewModel.recalibratePhoneMount() },
                                        onBack = { navController.popBackStack() },
                                    )
                                }
                                composable("emergency_contacts") {
                                    EmergencyContactsScreen(
                                        state = dashState,
                                        prefsRepo = prefsRepo,
                                        onBack = { navController.popBackStack() },
                                    )
                                }
                                composable("sound_studio") {
                                    val soundVm: SoundStudioViewModel = hiltViewModel()
                                    SoundStudioScreen(
                                        viewModel = soundVm,
                                        onBack = { navController.popBackStack() },
                                        onOpenLibrary = { navController.navigate("sound_library") },
                                        onOpenProfileDetails = { id -> navController.navigate("sound_profile/$id") },
                                        onOpenImport = { navController.navigate("sound_import") },
                                        onOpenSettings = { navController.navigate("sound_settings") },
                                    )
                                }
                                composable("sound_library") {
                                    val parentEntry = remember(it) {
                                        runCatching { navController.getBackStackEntry("sound_studio") }.getOrNull()
                                    }
                                    val soundVm: SoundStudioViewModel = if (parentEntry != null) {
                                        hiltViewModel(parentEntry)
                                    } else {
                                        hiltViewModel()
                                    }
                                    SoundLibraryScreen(
                                        viewModel = soundVm,
                                        onBack = { navController.popBackStack() },
                                        onOpenProfileDetails = { id -> navController.navigate("sound_profile/$id") },
                                        onOpenImport = { navController.navigate("sound_import") },
                                    )
                                }
                                composable(
                                    route = "sound_profile/{profileId}",
                                    arguments = listOf(navArgument("profileId") { type = NavType.StringType }),
                                ) { backStackEntry ->
                                    val parentEntry = remember(backStackEntry) {
                                        runCatching { navController.getBackStackEntry("sound_studio") }.getOrNull()
                                    }
                                    val soundVm: SoundStudioViewModel = if (parentEntry != null) {
                                        hiltViewModel(parentEntry)
                                    } else {
                                        hiltViewModel()
                                    }
                                    val profileId = backStackEntry.arguments?.getString("profileId").orEmpty()
                                    SoundProfileDetailsScreen(
                                        profileId = profileId,
                                        viewModel = soundVm,
                                        onBack = { navController.popBackStack() },
                                    )
                                }
                                composable("sound_import") {
                                    val parentEntry = remember(it) {
                                        runCatching { navController.getBackStackEntry("sound_studio") }.getOrNull()
                                    }
                                    val soundVm: SoundStudioViewModel = if (parentEntry != null) {
                                        hiltViewModel(parentEntry)
                                    } else {
                                        hiltViewModel()
                                    }
                                    ImportSoundPackScreen(
                                        viewModel = soundVm,
                                        onBack = { navController.popBackStack() },
                                    )
                                }
                                composable("sound_settings") {
                                    val parentEntry = remember(it) {
                                        runCatching { navController.getBackStackEntry("sound_studio") }.getOrNull()
                                    }
                                    val soundVm: SoundStudioViewModel = if (parentEntry != null) {
                                        hiltViewModel(parentEntry)
                                    } else {
                                        hiltViewModel()
                                    }
                                    AudioSettingsScreen(
                                        viewModel = soundVm,
                                        onBack = { navController.popBackStack() },
                                    )
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

private data class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)
