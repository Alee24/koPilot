package ke.co.kopilot.intelligence

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import ke.co.kopilot.core.common.coroutines.DispatcherProvider
import ke.co.kopilot.core.database.dao.SensorHistoryDao
import ke.co.kopilot.core.database.dao.VehicleProfileDao
import ke.co.kopilot.core.database.entity.SensorHistoryEntity
import ke.co.kopilot.feature.diagnostics.ObdBluetoothManager
import ke.co.kopilot.feature.diagnostics.ObdLiveTelemetry
import ke.co.kopilot.feature.diagnostics.ObdPidCatalog
import ke.co.kopilot.feature.diagnostics.PidSupportStatus
import ke.co.kopilot.feature.location.LocationTracker
import ke.co.kopilot.feature.sensors.RealTimeDrivingIntelligence
import ke.co.kopilot.feature.sensors.SensorManagerRepository
import ke.co.kopilot.feature.vision.RoadVisionPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized single source of truth for live and cached vehicle telemetry.
 * Wraps the single shared ObdBluetoothManager scheduler alongside phone sensors,
 * driving intelligence, and Room sensor history without opening competing BT connections.
 */
@Singleton
class VehicleTelemetryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val obdManager: ObdBluetoothManager,
    private val sensorManagerRepo: SensorManagerRepository,
    private val drivingIntelligence: RealTimeDrivingIntelligence,
    private val locationTracker: LocationTracker,
    private val visionPipeline: RoadVisionPipeline,
    private val vehicleProfileDao: VehicleProfileDao,
    private val sensorHistoryDao: SensorHistoryDao,
    private val dispatchers: DispatcherProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val prefs = context.getSharedPreferences("kopilot_vehicle_capability_cache", Context.MODE_PRIVATE)

    private val _snapshot = MutableStateFlow(buildSnapshot(System.currentTimeMillis(), DEFAULT_VEHICLE_NAME))
    val snapshot: StateFlow<VehicleSnapshot> = _snapshot.asStateFlow()

    @Volatile
    private var cachedVehicleIdentity: String = DEFAULT_VEHICLE_NAME

    @Volatile
    private var cachedSupportedPids: Set<String> = loadCachedPids()

    private var lastHistoryWriteMs = 0L

    init {
        scope.launch {
            vehicleProfileDao.observeActiveVehicle().collect { profile ->
                cachedVehicleIdentity = if (profile != null) {
                    listOfNotNull(
                        profile.modelYear?.toString(),
                        profile.manufacturer,
                        profile.model,
                        profile.engineType.takeIf { it.isNotBlank() },
                        profile.transmission.takeIf { it.isNotBlank() },
                    ).joinToString(" ").ifBlank { DEFAULT_VEHICLE_NAME }
                } else {
                    DEFAULT_VEHICLE_NAME
                }
            }
        }

        scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val livePids = obdManager.liveTelemetry.value.supportedPidHexes
                if (livePids.isNotEmpty() && livePids != cachedSupportedPids) {
                    cachedSupportedPids = livePids
                    saveCachedPids(livePids)
                }

                val snap = buildSnapshot(now, cachedVehicleIdentity)
                _snapshot.value = snap

                // Record downsampled history every 15s when OBD is connected or vehicle is moving
                val moving = (snap.phoneSensors.gpsSpeedKmh ?: 0f) >= 4f
                if ((snap.obdConnected || moving) && (now - lastHistoryWriteMs) >= HISTORY_INTERVAL_MS) {
                    lastHistoryWriteMs = now
                    val tel = obdManager.liveTelemetry.value
                    runCatching {
                        sensorHistoryDao.insertSample(
                            SensorHistoryEntity(
                                timestampMillis = now,
                                tripId = null,
                                obdConnected = snap.obdConnected,
                                rpm = tel.engineRpm,
                                coolantTempC = tel.coolantTempC,
                                voltageV = tel.controlModuleVoltageV ?: tel.adapterVoltageV,
                                engineLoadPct = tel.engineLoadPct,
                                throttlePct = tel.throttlePositionPct,
                                intakeTempC = tel.intakeAirTempC,
                                mafGramsPerSec = tel.mafGramsPerSec,
                                fuelRateLph = tel.engineFuelRateLph,
                                speedKmh = snap.phoneSensors.gpsSpeedKmh ?: tel.vehicleSpeedKmh?.toFloat(),
                                safetyScore = snap.phoneSensors.drivingSafetyScore,
                                fuelScore = snap.phoneSensors.fuelEfficiencyScore,
                            ),
                        )
                        // Retain 30 days of downsampled history
                        val cutoff = now - RETENTION_MS
                        sensorHistoryDao.deleteOlderThan(cutoff)
                    }
                }
                delay(1_000L)
            }
        }
    }

    fun getVehicleSnapshot(nowMillis: Long = System.currentTimeMillis()): VehicleSnapshot {
        val snap = buildSnapshot(nowMillis, cachedVehicleIdentity)
        _snapshot.value = snap
        return snap
    }

    suspend fun performSafeReadOnlyDtcScan(): Boolean {
        val ok = obdManager.requestSafeDtcScan()
        getVehicleSnapshot()
        return ok
    }

    suspend fun getRecentHistory(sinceMillis: Long): List<SensorHistoryEntity> {
        return sensorHistoryDao.getSamplesSince(sinceMillis)
    }

    suspend fun getHistoryBetween(startMillis: Long, endMillis: Long): List<SensorHistoryEntity> {
        return sensorHistoryDao.getSamplesBetween(startMillis, endMillis)
    }

    private fun buildSnapshot(nowMillis: Long, vehicleIdentity: String): VehicleSnapshot {
        val diagState = obdManager.state.value
        val tel = obdManager.liveTelemetry.value
        val capabilities = obdManager.capabilities.value
        val obdConnected = diagState.obdConnected
        val activeSupported = if (tel.supportedPidHexes.isNotEmpty()) tel.supportedPidHexes else cachedSupportedPids

        val readings = linkedMapOf<String, MeasurementReading<Float>>()
        val updatedAt = tel.lastUpdatedMillis

        fun addPid(pidHex: String, rawValue: Float?, fastPid: Boolean = false) {
            val def = ObdPidCatalog.byPidHex[pidHex] ?: return
            val capStatus = capabilities.firstOrNull { it.pidHex == pidHex }?.status
            val isSupported = pidHex in activeSupported || capStatus == PidSupportStatus.SUPPORTED

            val validity = when {
                !obdConnected -> if (rawValue != null) MeasurementValidity.STALE else MeasurementValidity.TEMPORARILY_UNAVAILABLE
                activeSupported.isNotEmpty() && !isSupported -> MeasurementValidity.UNSUPPORTED
                rawValue == null -> if (updatedAt == null) MeasurementValidity.NOT_YET_CHECKED else MeasurementValidity.TEMPORARILY_UNAVAILABLE
                !ObdPidCatalog.isWithinValidRange(pidHex, rawValue) -> MeasurementValidity.INVALID
                updatedAt != null && (nowMillis - updatedAt) > (if (fastPid) 6_000L else 15_000L) -> MeasurementValidity.STALE
                else -> MeasurementValidity.AVAILABLE
            }

            readings[pidHex] = MeasurementReading(
                pidOrSensorId = pidHex,
                name = def.name,
                value = if (validity == MeasurementValidity.AVAILABLE || validity == MeasurementValidity.STALE) rawValue else null,
                unit = def.unit,
                timestampMillis = updatedAt,
                source = "OBD-II ($pidHex)",
                validity = validity,
                confidence = if (validity == MeasurementValidity.AVAILABLE) 1.0f else 0.5f,
            )
        }

        addPid("0104", tel.engineLoadPct, fastPid = true)
        addPid("0105", tel.coolantTempC?.toFloat())
        addPid("0106", tel.shortTermFuelTrimPct)
        addPid("0107", tel.longTermFuelTrimPct)
        addPid("0108", tel.shortTermFuelTrimB2Pct)
        addPid("0109", tel.longTermFuelTrimB2Pct)
        addPid("010A", tel.fuelPressureGaugeKpa?.toFloat())
        addPid("010B", tel.mapKpa?.toFloat())
        addPid("010C", tel.engineRpm?.toFloat(), fastPid = true)
        addPid("010D", tel.vehicleSpeedKmh?.toFloat(), fastPid = true)
        addPid("010E", tel.timingAdvanceDeg)
        addPid("010F", tel.intakeAirTempC?.toFloat())
        addPid("0110", tel.mafGramsPerSec)
        addPid("0111", tel.throttlePositionPct, fastPid = true)
        addPid("011F", tel.runtimeSinceStartSec?.toFloat())
        addPid("012F", tel.fuelLevelPct)
        addPid("0133", tel.barometricPressureKpa?.toFloat())
        addPid("0142", tel.controlModuleVoltageV ?: tel.adapterVoltageV)
        addPid("0143", tel.absoluteLoadPct)
        addPid("0145", tel.relativeThrottlePct)
        addPid("0146", tel.ambientAirTempC?.toFloat())
        addPid("015C", tel.engineOilTempC?.toFloat())
        addPid("015E", tel.engineFuelRateLph)

        val fuelSysValidity = when {
            !obdConnected -> MeasurementValidity.TEMPORARILY_UNAVAILABLE
            "0103" !in activeSupported && activeSupported.isNotEmpty() -> MeasurementValidity.UNSUPPORTED
            tel.fuelSystemStatus == null -> MeasurementValidity.NOT_YET_CHECKED
            else -> MeasurementValidity.AVAILABLE
        }
        val fuelSysReading = MeasurementReading(
            pidOrSensorId = "0103",
            name = "Fuel System Status",
            value = if (fuelSysValidity == MeasurementValidity.AVAILABLE) tel.fuelSystemStatus else null,
            unit = "",
            timestampMillis = updatedAt,
            source = "OBD-II (0103)",
            validity = fuelSysValidity,
        )

        val hub = sensorManagerRepo.state.value
        val intel = drivingIntelligence.state.value
        val fix = locationTracker.state.value.currentFix
        val vision = visionPipeline.state.value

        val phoneSensors = PhoneSensorSnapshot(
            gpsAvailable = fix != null,
            gpsSpeedKmh = fix?.speedKmh,
            gpsAccuracyMeters = fix?.horizontalAccuracyMeters,
            latitude = fix?.latitude,
            longitude = fix?.longitude,
            roadSmoothnessScore = hub.motionAnalysis.behaviourStats.roadSmoothnessScore,
            roadVibrationRmsMps2 = hub.motionAnalysis.verticalVibrationRmsMps2,
            drivingSafetyScore = intel.safetyScore,
            fuelEfficiencyScore = intel.fuelEfficiencyScore,
            drivingStateLabel = intel.drivingState.label,
            lastSignDetected = vision.lastObservation?.category?.displayName,
            lastSignConfidence = vision.lastObservation?.confidence,
        )

        val allDtcs = (diagState.dtcCodes + tel.pendingDtcCodes + tel.permanentDtcCodes).distinct()
        val dtcStatus = when {
            !obdConnected && !tel.dtcScanCompleted -> DtcScanStatus.UNAVAILABLE
            !tel.dtcScanCompleted -> DtcScanStatus.NOT_SCANNED
            allDtcs.isEmpty() -> DtcScanStatus.SCANNED_CLEAN
            else -> DtcScanStatus.SCANNED_FAULTS_FOUND
        }

        return VehicleSnapshot(
            timestampIso = Instant.ofEpochMilli(nowMillis).toString(),
            timestampMillis = nowMillis,
            vehicleIdentity = vehicleIdentity,
            obdConnected = obdConnected,
            obdProtocol = tel.protocolName ?: diagState.protocolName,
            adapterInfo = tel.adapterInfo ?: diagState.adapterName,
            supportedPidHexes = activeSupported,
            engineReadings = readings,
            fuelSystemStatus = fuelSysReading,
            phoneSensors = phoneSensors,
            diagnostics = VehicleDiagnosticsSnapshot(
                storedCodes = diagState.dtcCodes,
                pendingCodes = tel.pendingDtcCodes,
                permanentCodes = tel.permanentDtcCodes,
                scanStatus = dtcStatus,
                lastScanMillis = tel.lastDtcScanMillis,
            ),
        )
    }

    private fun loadCachedPids(): Set<String> {
        val raw = prefs.getString("supported_pids", "").orEmpty()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    private fun saveCachedPids(pids: Set<String>) {
        prefs.edit().putString("supported_pids", pids.sorted().joinToString(",")).apply()
    }

    companion object {
        const val DEFAULT_VEHICLE_NAME = "2012 Nissan Tiida 1.5L CVT"
        private const val HISTORY_INTERVAL_MS = 15_000L
        private const val RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
    }
}
