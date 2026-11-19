package ke.co.kopilot.feature.navigation

import android.graphics.Color as AndroidColor
import android.preference.PreferenceManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.maps.model.LatLng
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * Interactive OpenStreetMap (osmdroid) navigation map with zero API key required.
 * Displays live GPS location, destination pin, and OSRM/route polylines with local tile caching.
 */
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
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapView = remember {
        Configuration.getInstance().load(
            context.applicationContext,
            PreferenceManager.getDefaultSharedPreferences(context.applicationContext),
        )
        Configuration.getInstance().userAgentValue = "${context.packageName}/2.9.5"
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            controller.setZoom(14.5)
            val startLat = currentLat ?: -1.2921
            val startLng = currentLng ?: 36.8219
            controller.setCenter(GeoPoint(startLat, startLng))
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                view.overlays.clear()

                val currentGeo = if (currentLat != null && currentLng != null) {
                    GeoPoint(currentLat, currentLng)
                } else {
                    null
                }
                val destGeo = destinationLatLng?.let { GeoPoint(it.latitude, it.longitude) }

                if (routePoints.size >= 2) {
                    val line = Polyline(view).apply {
                        setPoints(routePoints.map { GeoPoint(it.latitude, it.longitude) })
                        outlinePaint.color = AndroidColor.parseColor("#00DDB5")
                        outlinePaint.strokeWidth = 12f
                    }
                    view.overlays.add(line)
                }

                if (currentGeo != null && hasLocationPermission) {
                    val currentMarker = Marker(view).apply {
                        position = currentGeo
                        title = "Your Vehicle"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                    view.overlays.add(currentMarker)
                }

                if (destGeo != null) {
                    val destMarker = Marker(view).apply {
                        position = destGeo
                        title = destinationName ?: "Destination"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    }
                    view.overlays.add(destMarker)
                }

                when {
                    routePoints.size >= 2 -> {
                        val pts = routePoints.map { GeoPoint(it.latitude, it.longitude) }
                        val box = BoundingBox.fromGeoPoints(pts)
                        view.post {
                            runCatching { view.zoomToBoundingBox(box, true, 90) }
                        }
                    }
                    currentGeo != null && destGeo != null -> {
                        val box = BoundingBox.fromGeoPoints(listOf(currentGeo, destGeo))
                        view.post {
                            runCatching { view.zoomToBoundingBox(box, true, 90) }
                        }
                    }
                    currentGeo != null -> {
                        view.controller.animateTo(currentGeo)
                    }
                }
                view.invalidate()
            },
        )

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0xB3071019),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp),
        ) {
            Text(
                text = "© OpenStreetMap • OSRM",
                color = Color(0xFFD0DCE6),
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
