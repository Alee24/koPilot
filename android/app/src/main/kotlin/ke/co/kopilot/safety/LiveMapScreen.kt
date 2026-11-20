package ke.co.kopilot.safety

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.drawable.BitmapDrawable
import android.preference.PreferenceManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.maps.model.LatLng
import ke.co.kopilot.core.designsystem.theme.KoPilotClusterTokens
import ke.co.kopilot.feature.dashboard.DashboardUiState
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.Locale

@Composable
fun LiveMapScreen(
    state: DashboardUiState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val fix = state.locationState.currentFix
    var followVehicle by remember { mutableStateOf(true) }
    var zoomLevel by remember { mutableDoubleStateOf(16.0) }
    var mapStyleIndex by remember { mutableStateOf(0) } // 0=OSM Standard, 1=OSM Topo
    val mapStyles = listOf("Standard" to TileSourceFactory.MAPNIK, "Topo" to TileSourceFactory.OpenTopo)

    val mapView = remember {
        Configuration.getInstance().load(
            context.applicationContext,
            PreferenceManager.getDefaultSharedPreferences(context.applicationContext),
        )
        Configuration.getInstance().userAgentValue = "${context.packageName}/2.9.6"
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(fix?.latitude ?: -1.2921, fix?.longitude ?: 36.8219))
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

    val headingArrowDrawable = remember {
        val sizePx = 76
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = AndroidCanvas(bmp)
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.argb(65, 0, 176, 255)
            style = Paint.Style.FILL
        }
        c.drawCircle(sizePx / 2f, sizePx / 2f, sizePx * 0.46f, haloPaint)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#00B0FF")
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4.5f
        }
        val path = AndroidPath().apply {
            moveTo(sizePx * 0.50f, sizePx * 0.12f)
            lineTo(sizePx * 0.80f, sizePx * 0.78f)
            lineTo(sizePx * 0.50f, sizePx * 0.64f)
            lineTo(sizePx * 0.20f, sizePx * 0.78f)
            close()
        }
        c.drawPath(path, fillPaint)
        c.drawPath(path, borderPaint)
        BitmapDrawable(context.resources, bmp)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KoPilotClusterTokens.Background),
    ) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                val targetSource = mapStyles[mapStyleIndex].second
                if (view.tileProvider.tileSource.name() != targetSource.name()) {
                    view.setTileSource(targetSource)
                }
                view.overlays.clear()

                // 1. Active Navigation Route Polyline
                val routePts: List<LatLng> = state.navigationState.decodedPoints
                if (routePts.size >= 2) {
                    val line = Polyline(view).apply {
                        setPoints(routePts.map { GeoPoint(it.latitude, it.longitude) })
                        outlinePaint.color = AndroidColor.parseColor("#00DDB5")
                        outlinePaint.strokeWidth = 14f
                    }
                    view.overlays.add(line)
                }

                // 2. Recorded Driven Route Breadcrumb Polyline
                val breadcrumbs = state.tripBreadcrumbPoints
                if (breadcrumbs.size >= 2) {
                    val trail = Polyline(view).apply {
                        setPoints(breadcrumbs.map { GeoPoint(it.latitude, it.longitude) })
                        outlinePaint.color = AndroidColor.parseColor("#00B0FF")
                        outlinePaint.strokeWidth = 10f
                    }
                    view.overlays.add(trail)
                }

                // 3. Vehicle Directional Heading Chevron (always reflects live compass / GPS heading)
                if (fix != null) {
                    val pos = GeoPoint(fix.latitude, fix.longitude)
                    val headingDeg = if ((fix.speedKmh ?: 0f) >= 8f && fix.bearingDegrees != null) {
                        fix.bearingDegrees!!
                    } else {
                        state.compassReading.degrees.toFloat()
                    }
                    val marker = Marker(view).apply {
                        position = pos
                        title = state.currentRoadName?.let { "On $it (${state.compassReading.cardinal})" }
                            ?: "Heading ${state.compassReading.cardinal} ${state.compassReading.degrees} deg"
                        icon = headingArrowDrawable
                        rotation = -headingDeg
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    }
                    view.overlays.add(marker)
                    if (followVehicle) {
                        view.controller.animateTo(pos)
                    }
                }
                view.invalidate()
            },
        )

        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = Color(0xCC071019),
                shape = CircleShape,
                border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 14.dp),
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Text("Live Map", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }

            Surface(
                color = Color(0xCC071019),
                shape = CircleShape,
                border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
            ) {
                Text(
                    text = state.compassReading.formatted,
                    color = KoPilotClusterTokens.PrimaryAccent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        // Right Map Controls (Style, Zoom +/-, Follow/Recenter)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MapControlButton(
                icon = Icons.Default.Layers,
                desc = "Map Style (${mapStyles[mapStyleIndex].first})",
                onClick = { mapStyleIndex = (mapStyleIndex + 1) % mapStyles.size },
            )
            MapControlButton(
                icon = Icons.Default.Add,
                desc = "Zoom In",
                onClick = {
                    zoomLevel = (zoomLevel + 1.0).coerceAtMost(19.0)
                    mapView.controller.setZoom(zoomLevel)
                },
            )
            MapControlButton(
                icon = Icons.Default.Remove,
                desc = "Zoom Out",
                onClick = {
                    zoomLevel = (zoomLevel - 1.0).coerceAtLeast(4.0)
                    mapView.controller.setZoom(zoomLevel)
                },
            )
            MapControlButton(
                icon = Icons.Default.MyLocation,
                desc = "Follow / Recenter",
                tint = if (followVehicle) KoPilotClusterTokens.PrimaryAccent else Color.White,
                onClick = {
                    followVehicle = !followVehicle
                    if (fix != null) {
                        mapView.controller.animateTo(GeoPoint(fix.latitude, fix.longitude))
                    }
                },
            )
        }

        // Bottom Current Trip Telemetry Card
        val distKm = state.locationState.distanceTraveledMeters / 1000.0
        val started = state.tripStartedAtMillis
        val elapsedSec = if (started != null && state.isDrivingSessionActive) {
            ((System.currentTimeMillis() - started) / 1000L).coerceAtLeast(0L)
        } else 0L
        val durStr = String.format(Locale.US, "%02d:%02d", elapsedSec / 60L, elapsedSec % 60L)
        val avgKmh = if (elapsedSec > 5L) ((distKm / (elapsedSec / 3600.0))).toInt() else (fix?.speedKmh?.toInt() ?: 0)

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(14.dp),
            color = Color(0xEB0E1D28),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.currentRoadName ?: state.navigationState.destinationName ?: "Current Trip",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                        )
                        Text(
                            text = buildString {
                                append("Heading ${state.compassReading.cardinal} (${state.compassReading.degrees} deg)")
                                state.navigationState.activeRoute?.let { r ->
                                    append(" • Route ${r.durationSeconds / 60} min")
                                }
                            },
                            color = KoPilotClusterTokens.PrimaryAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        text = if (state.isDrivingSessionActive) "Recording" else "Standby • ${mapStyles[mapStyleIndex].first}",
                        color = if (state.isDrivingSessionActive) KoPilotClusterTokens.SmoothRoad else KoPilotClusterTokens.SecondaryText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    MapTripStat("Distance", "${"%.2f".format(Locale.US, distKm)} km")
                    MapTripStat("Duration", durStr)
                    MapTripStat("Speed", "${fix?.speedKmh?.toInt() ?: avgKmh} km/h")
                    MapTripStat("GPS Acc.", fix?.horizontalAccuracyMeters?.let { "±${it.toInt()}m" } ?: "--")
                }

                Text(
                    text = if (fix != null) {
                        "Lat ${"%.5f".format(Locale.US, fix.latitude)}, Lng ${"%.5f".format(Locale.US, fix.longitude)} • Offline tile cache active"
                    } else {
                        "Acquiring GPS fix..."
                    },
                    color = KoPilotClusterTokens.SecondaryText,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun MapControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = Color(0xCC071019),
        shape = CircleShape,
        border = BorderStroke(1.dp, KoPilotClusterTokens.Border),
        modifier = Modifier.size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun MapTripStat(label: String, value: String) {
    Column {
        Text(label, color = KoPilotClusterTokens.SecondaryText, fontSize = 11.sp)
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}
