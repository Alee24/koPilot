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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import ke.co.kopilot.core.database.preferences.PreferencesRepository
import ke.co.kopilot.core.designsystem.theme.KoPilotTheme
import ke.co.kopilot.feature.dashboard.DashboardScreen
import ke.co.kopilot.feature.dashboard.DashboardViewModel
import ke.co.kopilot.feature.diagnostics.ObdScreen
import ke.co.kopilot.feature.sensors.SensorsScreen
import ke.co.kopilot.feature.settings.SettingsScreen
import ke.co.kopilot.feature.trips.TripsScreen
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
                    dashboardViewModel.updatePermissions(
                        cameraGranted = hasPermission(Manifest.permission.CAMERA),
                        locationGranted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION),
                        micGranted = hasPermission(Manifest.permission.RECORD_AUDIO),
                    )
                }

                LaunchedEffect(Unit) {
                    dashboardViewModel.updatePermissions(
                        cameraGranted = hasPermission(Manifest.permission.CAMERA),
                        locationGranted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION),
                        micGranted = hasPermission(Manifest.permission.RECORD_AUDIO),
                    )
                    if (requiredPermissions.any { !hasPermission(it) }) {
                        permissionLauncher.launch(requiredPermissions)
                    }
                }

                NavHost(navController = navController, startDestination = "dashboard") {
                    composable("dashboard") {
                        DashboardScreen(
                            onOpenSettings = { navController.navigate("settings") },
                            onOpenSensors = { navController.navigate("sensors") },
                            onOpenRoadQuality = { navController.navigate("road_quality") },
                            onOpenDrivingBehaviour = { navController.navigate("driving_behaviour") },
                            onOpenObd = { navController.navigate("obd") },
                            onOpenTrips = { navController.navigate("trips") },
                            onCallEmergency = { phone ->
                                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
                            },
                            viewModel = dashboardViewModel,
                        )
                    }
                    composable("trips") {
                        TripsScreen(onBack = { navController.popBackStack() })
                    }
                    composable("obd") {
                        ObdScreen(onBack = { navController.popBackStack() })
                    }
                    composable("sensors") {
                        SensorsScreen(
                            onBack = { navController.popBackStack() },
                            onOpenTrips = { navController.navigate("trips") },
                            initialTab = 0,
                        )
                    }
                    composable("road_quality") {
                        SensorsScreen(
                            onBack = { navController.popBackStack() },
                            onOpenTrips = { navController.navigate("trips") },
                            initialTab = 1,
                        )
                    }
                    composable("driving_behaviour") {
                        SensorsScreen(
                            onBack = { navController.popBackStack() },
                            onOpenTrips = { navController.navigate("trips") },
                            initialTab = 2,
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
                            onOpenObd = { navController.navigate("obd") },
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
                }
            }
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
