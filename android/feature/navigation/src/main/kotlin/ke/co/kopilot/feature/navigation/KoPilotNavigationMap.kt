package ke.co.kopilot.feature.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
