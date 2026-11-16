package ke.co.kopilot.feature.navigation

import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import ke.co.kopilot.core.designsystem.theme.KoPilotColors

@Composable
fun KoPilotNavigationMap(
    currentLat: Double?,
    currentLng: Double?,
    routePoints: List<LatLng>,
    destinationLatLng: LatLng?,
    destinationName: String?,
    hasLocationPermission: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hasMapsKey = remember {
        runCatching {
            val appInfo = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            val key = appInfo.metaData?.getString("com.google.android.geo.API_KEY").orEmpty()
            key.isNotBlank()
        }.getOrDefault(false)
    }

    if (!hasMapsKey) {
        Box(
            modifier = modifier.fillMaxSize().background(Color(0xFF131C26)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(16.dp),
            ) {
                Text("Map Tiles Require Google Maps Key", style = MaterialTheme.typography.titleMedium, color = Color.White)
                val locText = if (currentLat != null && currentLng != null) {
                    "GPS: ${"%.4f".format(currentLat)}, ${"%.4f".format(currentLng)}"
                } else {
                    "Acquiring GPS coordinates..."
                }
                Text(locText, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF9FB0C3))
            }
        }
        return
    }
    val defaultNairobi = LatLng(-1.286389, 36.817223)
    val target = if (currentLat != null && currentLng != null) {
        LatLng(currentLat, currentLng)
    } else {
        defaultNairobi
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(target, 14f)
    }

    GoogleMap(
        modifier = modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState,
        properties = MapProperties(
            isMyLocationEnabled = hasLocationPermission,
            isTrafficEnabled = true,
        ),
        uiSettings = MapUiSettings(
            compassEnabled = true,
            myLocationButtonEnabled = false,
            zoomControlsEnabled = false,
        ),
    ) {
        if (routePoints.isNotEmpty()) {
            Polyline(
                points = routePoints,
                color = KoPilotColors.AccentBlue,
                width = 12f,
            )
        }
        if (destinationLatLng != null) {
            Marker(
                state = MarkerState(position = destinationLatLng),
                title = destinationName ?: "Destination",
            )
        }
    }
}
