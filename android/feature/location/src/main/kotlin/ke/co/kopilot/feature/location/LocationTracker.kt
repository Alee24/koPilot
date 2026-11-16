package ke.co.kopilot.feature.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import ke.co.kopilot.core.common.model.GpsFix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class LocationTrackingState(
    val currentFix: GpsFix? = null,
    val distanceTraveledMeters: Double = 0.0,
    val isTracking: Boolean = false,
    val permissionGranted: Boolean = false,
    val satellitesInView: Int = 0,
    val satellitesUsedInFix: Int = 0,
    val averageCn0DbHz: Float = 0f,
    val statusMessage: String = "GPS idle",
)

@Singleton
class LocationTracker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _state = MutableStateFlow(LocationTrackingState())
    val state: StateFlow<LocationTrackingState> = _state.asStateFlow()

    private var lastAcceptedLocation: Location? = null
    private var locationCallback: LocationCallback? = null
    private var gnssCallback: GnssStatus.Callback? = null

    fun setPermissionGranted(granted: Boolean) {
        _state.value = _state.value.copy(
            permissionGranted = granted,
            statusMessage = if (granted) "GPS ready" else "Location permission required",
        )
    }

    @SuppressLint("MissingPermission")
    fun startTracking(highAccuracy: Boolean = true) {
        if (!_state.value.permissionGranted || locationCallback != null) return

        val priority = if (highAccuracy) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val request = LocationRequest.Builder(priority, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMinUpdateDistanceMeters(1f)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                processLocation(loc)
            }
        }
        locationCallback = callback
        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        registerGnssStatus()
        _state.value = _state.value.copy(isTracking = true, statusMessage = "Acquiring GPS fix...")
    }

    fun stopTracking() {
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        locationCallback = null
        unregisterGnssStatus()
        lastAcceptedLocation = null
        _state.value = _state.value.copy(isTracking = false, statusMessage = "GPS stopped")
    }

    @SuppressLint("MissingPermission")
    private fun registerGnssStatus() {
        if (gnssCallback != null || locationManager == null) return
        val cb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val total = status.satelliteCount
                var used = 0
                var cn0Sum = 0f
                for (i in 0 until total) {
                    if (status.usedInFix(i)) used += 1
                    cn0Sum += status.getCn0DbHz(i)
                }
                val avgCn0 = if (total > 0) cn0Sum / total else 0f
                _state.value = _state.value.copy(
                    satellitesInView = total,
                    satellitesUsedInFix = used,
                    averageCn0DbHz = avgCn0,
                )
            }
        }
        gnssCallback = cb
        runCatching {
            locationManager?.registerGnssStatusCallback(cb, Handler(Looper.getMainLooper()))
        }
    }

    private fun unregisterGnssStatus() {
        gnssCallback?.let { cb ->
            runCatching { locationManager?.unregisterGnssStatusCallback(cb) }
        }
        gnssCallback = null
    }

    fun resetTripDistance() {
        lastAcceptedLocation = null
        _state.value = _state.value.copy(distanceTraveledMeters = 0.0)
    }

    private fun processLocation(loc: Location) {
        val prev = lastAcceptedLocation
        var addedDistance = 0.0
        if (prev != null && loc.hasAccuracy() && loc.accuracy <= 35f) {
            val deltaMeters = prev.distanceTo(loc).toDouble()
            val deltaSeconds = ((loc.time - prev.time).coerceAtLeast(100L)) / 1000.0
            val impliedSpeedMps = deltaMeters / deltaSeconds
            // Reject physically implausible position jumps (> 65 m/s ~ 234 km/h)
            if (impliedSpeedMps <= 65.0 && deltaMeters >= 1.0) {
                addedDistance = deltaMeters
                lastAcceptedLocation = loc
            }
        } else if (loc.hasAccuracy() && loc.accuracy <= 35f) {
            lastAcceptedLocation = loc
        }

        val speedAcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && loc.hasSpeedAccuracy()) {
            loc.speedAccuracyMetersPerSecond
        } else {
            null
        }

        val fix = GpsFix(
            latitude = loc.latitude,
            longitude = loc.longitude,
            speedMps = if (loc.hasSpeed()) loc.speed else null,
            speedAccuracyMps = speedAcc,
            horizontalAccuracyMeters = if (loc.hasAccuracy()) loc.accuracy else null,
            bearingDegrees = if (loc.hasBearing()) loc.bearing else null,
            timestampMillis = loc.time,
            isStale = false,
        )

        _state.value = _state.value.copy(
            currentFix = fix,
            distanceTraveledMeters = _state.value.distanceTraveledMeters + addedDistance,
            statusMessage = "GPS active",
        )
    }
}
