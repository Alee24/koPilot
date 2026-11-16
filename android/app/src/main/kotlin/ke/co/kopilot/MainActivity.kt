package ke.co.kopilot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
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
import ke.co.kopilot.feature.sensors.SensorsScreen
import ke.co.kopilot.feature.settings.SettingsScreen
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var prefsRepo: PreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val settings by prefsRepo.settingsFlow.collectAsStateWithLifecycle(
                initialValue = ke.co.kopilot.core.database.preferences.KoPilotSettings(),
            )

            KoPilotTheme(darkTheme = settings.darkTheme) {
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
                            onCallEmergency = { phone ->
                                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
                            },
                            viewModel = dashboardViewModel,
                        )
                    }
                    composable("sensors") {
                        SensorsScreen(onBack = { navController.popBackStack() })
                    }
                    composable("settings") {
                        val btGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        } else {
                            true
                        }
                        SettingsScreen(
                            onBack = { navController.popBackStack() },
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
