package ke.co.kopilot.feature.sensors.wearable

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import ke.co.kopilot.core.database.dao.WearableDao
import ke.co.kopilot.core.database.entity.HeartRateMeasurementEntity
import ke.co.kopilot.core.database.entity.SleepSessionEntity
import ke.co.kopilot.core.database.entity.SleepStageEntity
import ke.co.kopilot.core.database.entity.WearableSyncHistoryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

enum class HealthConnectAvailabilityState(val displayName: String) {
    AVAILABLE("Available"),
    PROVIDER_UPDATE_REQUIRED("Health Connect Update Required"),
    NOT_INSTALLED("Health Connect Not Installed"),
    UNSUPPORTED_ANDROID_VERSION("Unsupported Android Version"),
}

data class RawSleepStageInput(
    val stageType: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
)

data class ComputedSleepMetrics(
    val totalSleepDurationMinutes: Int,
    val timeInBedMinutes: Int,
    val sleepEfficiencyPercent: Int,
    val deepSleepMinutes: Int?,
    val lightSleepMinutes: Int?,
    val remSleepMinutes: Int?,
    val awakeMinutes: Int?,
    val interruptionsCount: Int,
    val stagesAvailable: Boolean,
)

data class CompanionWearableAppInfo(
    val packageName: String,
    val appName: String,
    val installed: Boolean,
    val healthConnectBridgeSupported: Boolean,
)

data class SleepTrendAnalysis(
    val averageDurationMinutesLast7Days: Int?,
    val shortestDurationMinutesLast7Days: Int?,
    val longestDurationMinutesLast7Days: Int?,
    val sessionsCountLast7Days: Int,
    val trendSummary: String,
    val preDriveContextText: String?,
)

data class SleepAnalyticsUiState(
    val healthConnectAvailability: HealthConnectAvailabilityState = HealthConnectAvailabilityState.NOT_INSTALLED,
    val sleepPermissionGranted: Boolean = false,
    val heartRatePermissionGranted: Boolean = false,
    val isSyncing: Boolean = false,
    val lastSyncTimestampMillis: Long? = null,
    val lastSyncStatusMessage: String = "Not synchronized yet",
    val activeDataSourceLabel: String = "Local Cache / Health Connect",
    val latestSession: SleepSessionEntity? = null,
    val latestSessionStages: List<SleepStageEntity> = emptyList(),
    val sevenDayHistory: List<SleepSessionEntity> = emptyList(),
    val trendAnalysis: SleepTrendAnalysis = SleepTrendAnalysis(
        averageDurationMinutesLast7Days = null,
        shortestDurationMinutesLast7Days = null,
        longestDurationMinutesLast7Days = null,
        sessionsCountLast7Days = 0,
        trendSummary = "No synchronized sleep sessions yet.",
        preDriveContextText = null,
    ),
    val installedCompanionApps: List<CompanionWearableAppInfo> = emptyList(),
    val directBleSleepProtocolStatus: String = "Standard BLE GATT (0x180D/0x180F/0x180A) does not define a universal sleep-session service. Sleep sessions are retrieved via Android Health Connect from your watch's companion app.",
)

/**
 * Pure deterministic calculator for sleep duration, sleep efficiency, stage durations,
 * interruption counts, and 7-day trends so all sleep math is unit-testable on the JVM.
 *
 * Strictly distinguishes unavailable sleep stages (`stagesAvailable = false`, stage fields = `null`)
 * from zero-duration stages (`0 minutes` when stages ARE supplied by the source record).
 */
object SleepAnalyticsCalculator {
    const val STAGE_AWAKE = "AWAKE"
    const val STAGE_LIGHT = "LIGHT"
    const val STAGE_DEEP = "DEEP"
    const val STAGE_REM = "REM"
    const val STAGE_SLEEPING_GENERIC = "SLEEPING"
    const val STAGE_OUT_OF_BED = "OUT_OF_BED"

    fun computeSessionMetrics(
        sessionStartMillis: Long,
        sessionEndMillis: Long,
        stages: List<RawSleepStageInput>,
    ): ComputedSleepMetrics {
        val timeInBedMinutes = (((sessionEndMillis - sessionStartMillis) / 60_000L).toInt()).coerceAtLeast(1)

        if (stages.isEmpty()) {
            return ComputedSleepMetrics(
                totalSleepDurationMinutes = timeInBedMinutes,
                timeInBedMinutes = timeInBedMinutes,
                sleepEfficiencyPercent = 100,
                deepSleepMinutes = null,
                lightSleepMinutes = null,
                remSleepMinutes = null,
                awakeMinutes = null,
                interruptionsCount = 0,
                stagesAvailable = false,
            )
        }

        var deepMins = 0
        var lightMins = 0
        var remMins = 0
        var awakeMins = 0
        var genericSleepMins = 0
        var interruptions = 0
        var hasDetailedStages = false

        val sortedStages = stages.sortedBy { it.startTimeMillis }
        for ((index, stage) in sortedStages.withIndex()) {
            val durMins = (((stage.endTimeMillis - stage.startTimeMillis) / 60_000L).toInt()).coerceAtLeast(0)
            when (stage.stageType) {
                STAGE_DEEP -> {
                    deepMins += durMins
                    hasDetailedStages = true
                }
                STAGE_LIGHT -> {
                    lightMins += durMins
                    hasDetailedStages = true
                }
                STAGE_REM -> {
                    remMins += durMins
                    hasDetailedStages = true
                }
                STAGE_AWAKE, STAGE_OUT_OF_BED -> {
                    awakeMins += durMins
                    // Count mid-sleep wake episodes (excluding initial sleep latency at index 0)
                    if (index > 0 && index < sortedStages.lastIndex && durMins >= 2) {
                        interruptions += 1
                    }
                }
                else -> {
                    genericSleepMins += durMins
                }
            }
        }

        val totalAsleepMinutes = (deepMins + lightMins + remMins + genericSleepMins)
            .let { if (it > 0) it else (timeInBedMinutes - awakeMins).coerceAtLeast(1) }

        val efficiency = ((totalAsleepMinutes.toDouble() * 100.0) / timeInBedMinutes.toDouble())
            .roundToInt()
            .coerceIn(1, 100)

        return ComputedSleepMetrics(
            totalSleepDurationMinutes = totalAsleepMinutes,
            timeInBedMinutes = timeInBedMinutes,
            sleepEfficiencyPercent = efficiency,
            deepSleepMinutes = if (hasDetailedStages) deepMins else null,
            lightSleepMinutes = if (hasDetailedStages) lightMins else null,
            remSleepMinutes = if (hasDetailedStages) remMins else null,
            awakeMinutes = awakeMins,
            interruptionsCount = interruptions,
            stagesAvailable = hasDetailedStages,
        )
    }

    fun computeSevenDayTrend(sessions: List<SleepSessionEntity>): SleepTrendAnalysis {
        if (sessions.isEmpty()) {
            return SleepTrendAnalysis(
                averageDurationMinutesLast7Days = null,
                shortestDurationMinutesLast7Days = null,
                longestDurationMinutesLast7Days = null,
                sessionsCountLast7Days = 0,
                trendSummary = "No recorded sleep sessions available.",
                preDriveContextText = null,
            )
        }

        val recent = sessions.sortedByDescending { it.endTimeMillis }.take(7)
        val durations = recent.map { it.totalSleepDurationMinutes }
        val avgMins = durations.average().roundToInt()
        val minMins = durations.minOrNull()
        val maxMins = durations.maxOrNull()
        val latest = recent.first()

        val latestFormatted = formatDurationHoursMinutes(latest.totalSleepDurationMinutes)
        val avgFormatted = formatDurationHoursMinutes(avgMins)

        val trendText = if (recent.size >= 2) {
            val delta = latest.totalSleepDurationMinutes - avgMins
            when {
                delta >= 30 -> "Latest sleep ($latestFormatted) is ${delta}m above your 7-day average ($avgFormatted)."
                delta <= -30 -> "Latest sleep ($latestFormatted) is ${-delta}m below your 7-day average ($avgFormatted)."
                else -> "Latest sleep ($latestFormatted) is consistent with your 7-day average ($avgFormatted)."
            }
        } else {
            "1 recorded session ($latestFormatted). Sync additional nights for a 7-day trend."
        }

        return SleepTrendAnalysis(
            averageDurationMinutesLast7Days = avgMins,
            shortestDurationMinutesLast7Days = minMins,
            longestDurationMinutesLast7Days = maxMins,
            sessionsCountLast7Days = recent.size,
            trendSummary = trendText,
            preDriveContextText = "Last recorded sleep: $latestFormatted (Source: ${latest.sourceAppName})",
        )
    }

    fun formatDurationHoursMinutes(totalMinutes: Int): String {
        val hrs = totalMinutes / 60
        val mins = totalMinutes % 60
        return if (hrs > 0) "${hrs}h ${mins}m" else "${mins}m"
    }
}

/**
 * Phase 4 — Production Sleep Monitoring & Android Health Connect Repository.
 *
 * - Queries Android Health Connect for `SleepSessionRecord` and `HeartRateRecord` when authorized.
 * - Detects installed wearable companion apps (Samsung Health, Google Fit, Zepp/Amazfit, Garmin Connect, Fitbit, Huawei Health).
 * - Persists all imported sleep sessions and sleep stages into Room (`sleep_sessions`, `sleep_stages`) so
 *   sleep analytics remain 100% available offline after synchronization.
 * - Never fabricates sleep sessions or infers sleep stages from heart rate alone.
 */
@Singleton
class SleepAnalyticsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wearableDao: WearableDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(SleepAnalyticsUiState())
    val state: StateFlow<SleepAnalyticsUiState> = _state.asStateFlow()

    val syncHistory = wearableDao.observeSyncHistory(limit = 20)

    val requiredHealthPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
    )

    init {
        refreshAvailabilityAndCompanionApps()
        scope.launch {
            wearableDao.observeSleepSessions(limit = 14).collect { sessions ->
                val latest = sessions.firstOrNull()
                val stages = if (latest != null) {
                    wearableDao.getStagesForSession(latest.sessionId)
                } else {
                    emptyList()
                }
                val sevenDay = sessions.take(7)
                val trend = SleepAnalyticsCalculator.computeSevenDayTrend(sevenDay)
                _state.value = _state.value.copy(
                    latestSession = latest,
                    latestSessionStages = stages,
                    sevenDayHistory = sevenDay,
                    trendAnalysis = trend,
                )
            }
        }
    }

    fun refreshAvailabilityAndCompanionApps() {
        val sdkStatus = runCatching {
            HealthConnectClient.getSdkStatus(context, HEALTH_CONNECT_PROVIDER_PACKAGE)
        }.getOrDefault(HealthConnectClient.SDK_UNAVAILABLE)

        val availability = when (sdkStatus) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailabilityState.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                HealthConnectAvailabilityState.PROVIDER_UPDATE_REQUIRED
            else -> HealthConnectAvailabilityState.NOT_INSTALLED
        }

        val companions = KNOWN_COMPANION_APPS.map { (pkg, label) ->
            val installed = isPackageInstalled(pkg)
            CompanionWearableAppInfo(
                packageName = pkg,
                appName = label,
                installed = installed,
                healthConnectBridgeSupported = true,
            )
        }

        _state.value = _state.value.copy(
            healthConnectAvailability = availability,
            installedCompanionApps = companions,
        )

        if (availability == HealthConnectAvailabilityState.AVAILABLE) {
            scope.launch {
                checkPermissionsAndSync(autoTriggered = true)
            }
        }
    }

    suspend fun checkPermissionsAndSync(autoTriggered: Boolean = false) {
        val availability = _state.value.healthConnectAvailability
        if (availability != HealthConnectAvailabilityState.AVAILABLE) {
            _state.value = _state.value.copy(
                lastSyncStatusMessage = when (availability) {
                    HealthConnectAvailabilityState.PROVIDER_UPDATE_REQUIRED ->
                        "Android Health Connect needs an update to read smartwatch sleep sessions."
                    HealthConnectAvailabilityState.NOT_INSTALLED ->
                        "Android Health Connect is not installed on this device. Install Health Connect to sync smartwatch sleep sessions."
                    else -> "Health Connect unavailable on this Android version."
                },
            )
            return
        }

        val client = runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull() ?: return
        val granted = runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
        val sleepReadPerm = HealthPermission.getReadPermission(SleepSessionRecord::class)
        val hrReadPerm = HealthPermission.getReadPermission(HeartRateRecord::class)

        val hasSleepPerm = granted.contains(sleepReadPerm)
        val hasHrPerm = granted.contains(hrReadPerm)

        _state.value = _state.value.copy(
            sleepPermissionGranted = hasSleepPerm,
            heartRatePermissionGranted = hasHrPerm,
        )

        if (!hasSleepPerm && !hasHrPerm) {
            _state.value = _state.value.copy(
                lastSyncStatusMessage = "Health Connect permissions not granted yet. Tap 'Authorize Health Connect' to grant Sleep & Heart Rate read access.",
            )
            return
        }

        syncFromHealthConnect(client, hasSleepPerm, hasHrPerm, autoTriggered)
    }

    private suspend fun syncFromHealthConnect(
        client: HealthConnectClient,
        hasSleepPerm: Boolean,
        hasHrPerm: Boolean,
        autoTriggered: Boolean,
    ) {
        _state.value = _state.value.copy(
            isSyncing = true,
            lastSyncStatusMessage = "Synchronizing sleep and heart-rate records from Health Connect...",
        )

        val now = Instant.now()
        val fourteenDaysAgo = now.minus(14, ChronoUnit.DAYS)
        var importedSleepCount = 0
        var importedHrCount = 0
        val errors = mutableListOf<String>()

        if (hasSleepPerm) {
            runCatching {
                val request = ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(fourteenDaysAgo, now),
                )
                val response = client.readRecords(request)
                for (record in response.records) {
                    val startMs = record.startTime.toEpochMilli()
                    val endMs = record.endTime.toEpochMilli()
                    if (endMs <= startMs) continue

                    val rawStages = record.stages.map { st ->
                        RawSleepStageInput(
                            stageType = mapHealthConnectStageType(st.stage),
                            startTimeMillis = st.startTime.toEpochMilli(),
                            endTimeMillis = st.endTime.toEpochMilli(),
                        )
                    }
                    val computed = SleepAnalyticsCalculator.computeSessionMetrics(
                        sessionStartMillis = startMs,
                        sessionEndMillis = endMs,
                        stages = rawStages,
                    )
                    val pkg = record.metadata.dataOrigin.packageName
                    val appLabel = resolveSourceAppName(pkg)
                    val sessionId = record.metadata.id.ifBlank { "hc_sleep_${startMs}_$endMs" }
                    val offsetSec = (record.startZoneOffset ?: ZoneId.systemDefault().rules.getOffset(record.startTime)).totalSeconds

                    val entity = SleepSessionEntity(
                        sessionId = sessionId,
                        startTimeMillis = startMs,
                        endTimeMillis = endMs,
                        timeZoneOffsetSeconds = offsetSec,
                        totalSleepDurationMinutes = computed.totalSleepDurationMinutes,
                        timeInBedMinutes = computed.timeInBedMinutes,
                        sleepEfficiencyPercent = computed.sleepEfficiencyPercent,
                        deepSleepMinutes = computed.deepSleepMinutes,
                        lightSleepMinutes = computed.lightSleepMinutes,
                        remSleepMinutes = computed.remSleepMinutes,
                        awakeMinutes = computed.awakeMinutes,
                        interruptionsCount = computed.interruptionsCount,
                        stagesAvailable = computed.stagesAvailable,
                        sourcePackage = pkg,
                        sourceAppName = appLabel,
                        title = record.title,
                        notes = record.notes,
                        syncedAtMillis = System.currentTimeMillis(),
                    )
                    wearableDao.upsertSleepSession(entity)
                    wearableDao.deleteStagesForSession(sessionId)
                    if (rawStages.isNotEmpty()) {
                        val stageEntities = rawStages.map { st ->
                            SleepStageEntity(
                                sessionId = sessionId,
                                stageType = st.stageType,
                                stageStartMillis = st.startTimeMillis,
                                stageEndMillis = st.endTimeMillis,
                                durationMinutes = (((st.endTimeMillis - st.startTimeMillis) / 60_000L).toInt()).coerceAtLeast(1),
                            )
                        }
                        wearableDao.insertSleepStages(stageEntities)
                    }
                    importedSleepCount += 1
                }
            }.onFailure { err ->
                errors.add("Sleep sync error: ${err.localizedMessage ?: "read failed"}")
            }
        }

        if (hasHrPerm) {
            runCatching {
                val hrStart = now.minus(24, ChronoUnit.HOURS)
                val hrReq = ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(hrStart, now),
                )
                val hrResp = client.readRecords(hrReq)
                val entities = mutableListOf<HeartRateMeasurementEntity>()
                for (rec in hrResp.records) {
                    val pkg = rec.metadata.dataOrigin.packageName
                    val appName = resolveSourceAppName(pkg)
                    for (sample in rec.samples) {
                        val bpm = sample.beatsPerMinute.toInt()
                        if (bpm in BleHeartRateParser.MIN_VALID_BPM..BleHeartRateParser.MAX_VALID_BPM) {
                            entities.add(
                                HeartRateMeasurementEntity(
                                    deviceAddress = "HC:$pkg",
                                    deviceName = appName,
                                    tripId = null,
                                    bpm = bpm,
                                    timestampMillis = sample.time.toEpochMilli(),
                                    dataSource = HeartRateRepository.SOURCE_HEALTH_CONNECT,
                                    connectionStatus = "SYNCHRONIZED",
                                    isValid = true,
                                    sensorContactSupported = false,
                                    sensorContactDetected = null,
                                    energyExpendedKj = null,
                                    latestRrIntervalMs = null,
                                    createdAtMillis = System.currentTimeMillis(),
                                ),
                            )
                        }
                    }
                }
                if (entities.isNotEmpty()) {
                    wearableDao.insertHeartRateBatch(entities)
                    importedHrCount = entities.size
                }
            }.onFailure { err ->
                errors.add("Heart-rate history sync error: ${err.localizedMessage ?: "read failed"}")
            }
        }

        val syncTime = System.currentTimeMillis()
        val statusMsg = when {
            errors.isNotEmpty() -> errors.joinToString(" | ")
            importedSleepCount > 0 -> "Synchronized $importedSleepCount sleep session(s) and $importedHrCount heart-rate record(s) from Health Connect."
            else -> "Health Connect sync succeeded, but 0 sleep sessions were found in the last 14 days. Ensure your watch companion app (e.g. Samsung Health, Zepp, Google Fit) has Health Connect sync enabled."
        }

        if (!autoTriggered || importedSleepCount > 0 || errors.isNotEmpty()) {
            wearableDao.insertSyncRecord(
                WearableSyncHistoryEntity(
                    syncSource = "ANDROID_HEALTH_CONNECT",
                    syncType = "SLEEP_AND_HR",
                    status = if (errors.isEmpty()) "SUCCESS" else "PARTIAL_ERROR",
                    recordsImportedCount = importedSleepCount + importedHrCount,
                    detailMessage = statusMsg,
                    syncedAtMillis = syncTime,
                ),
            )
        }

        _state.value = _state.value.copy(
            isSyncing = false,
            lastSyncTimestampMillis = syncTime,
            lastSyncStatusMessage = statusMsg,
            activeDataSourceLabel = if (importedSleepCount > 0) "Android Health Connect (Verified)" else "Android Health Connect (0 Records Found)",
        )
    }

    fun buildOpenHealthConnectSettingsIntent(): Intent {
        return runCatching {
            Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
        }.getOrElse {
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("market://details?id=$HEALTH_CONNECT_PROVIDER_PACKAGE"),
            )
        }
    }

    private fun mapHealthConnectStageType(stage: Int): String = when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE,
        SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> SleepAnalyticsCalculator.STAGE_AWAKE
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> SleepAnalyticsCalculator.STAGE_OUT_OF_BED
        SleepSessionRecord.STAGE_TYPE_LIGHT -> SleepAnalyticsCalculator.STAGE_LIGHT
        SleepSessionRecord.STAGE_TYPE_DEEP -> SleepAnalyticsCalculator.STAGE_DEEP
        SleepSessionRecord.STAGE_TYPE_REM -> SleepAnalyticsCalculator.STAGE_REM
        else -> SleepAnalyticsCalculator.STAGE_SLEEPING_GENERIC
    }

    private fun resolveSourceAppName(packageName: String): String {
        KNOWN_COMPANION_APPS[packageName]?.let { return it }
        return runCatching {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            true
        }.getOrDefault(false)
    }

    companion object {
        const val HEALTH_CONNECT_PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

        val KNOWN_COMPANION_APPS = linkedMapOf(
            "com.sec.android.app.shealth" to "Samsung Health",
            "com.google.android.apps.fitness" to "Google Fit",
            "com.huami.watch.hmwatchmanager" to "Zepp (Amazfit)",
            "com.garmin.android.apps.connectmobile" to "Garmin Connect",
            "com.fitbit.FitbitMobile" to "Fitbit",
            "com.huawei.health" to "Huawei Health",
        )
    }
}
