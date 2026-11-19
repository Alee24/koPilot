package ke.co.kopilot.feature.sensors.wearable

import ke.co.kopilot.core.database.dao.WearableDao
import ke.co.kopilot.core.database.entity.HeartRateMeasurementEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

data class HeartRateSamplePoint(
    val bpm: Int,
    val timestampMillis: Long,
    val deviceAddress: String,
    val dataSource: String,
    val isGapAfter: Boolean = false,
)

data class TripHeartRateDisconnectGap(
    val disconnectedAtMillis: Long,
    val reconnectedAtMillis: Long?,
)

data class TripHeartRateSummary(
    val tripId: String?,
    val minBpm: Int?,
    val maxBpm: Int?,
    val avgBpm: Int?,
    val sampleCount: Int,
    val disconnectedGapsCount: Int,
    val disconnectedIntervals: List<TripHeartRateDisconnectGap>,
)

data class HeartRateTelemetryState(
    val currentBpm: Int? = null,
    val isLiveReading: Boolean = false,
    val isStale: Boolean = false,
    val lastMeasurementTimestampMillis: Long? = null,
    val deviceAddress: String? = null,
    val deviceName: String? = null,
    val dataSource: String = "NONE",
    val connectionStatus: String = "DISCONNECTED",
    val sensorContactSupported: Boolean = false,
    val sensorContactDetected: Boolean? = null,
    val energyExpendedKj: Int? = null,
    val latestRrIntervalMs: Int? = null,
    val minBpm: Int? = null,
    val maxBpm: Int? = null,
    val avgBpm: Int? = null,
    val totalValidSamplesInSession: Int = 0,
    val historyPoints: List<HeartRateSamplePoint> = emptyList(),
    val activeTripSummary: TripHeartRateSummary? = null,
    val invalidReadingNotice: String? = null,
) {
    /**
     * Returns the display string for BPM (`"--"` when disconnected, invalid, or stale).
     * Never returns an old measurement as if it were live.
     */
    val displayBpmText: String
        get() = if (isLiveReading && !isStale && currentBpm != null) "$currentBpm" else "--"

    val statusLabel: String
        get() = when {
            isLiveReading && !isStale && currentBpm != null -> "Live (${currentBpm} BPM)"
            isStale -> "No current reading (Stale)"
            invalidReadingNotice != null -> invalidReadingNotice
            else -> "No current reading"
        }
}

/**
 * Phase 3 & 8 — Live Heart-Rate Repository with Stale Detection, Trip Gap Tracking & Safe DB Batching.
 *
 * - Never displays an old measurement as if it were live: readings older than [STALE_THRESHOLD_MS] (15s)
 *   or after watch disconnection automatically transition to `isLiveReading = false, currentBpm = null`.
 * - Tracks per-trip min/max/avg BPM and disconnection gap intervals when a trip is active.
 * - Batches database inserts on `Dispatchers.IO` (flushed every 4 samples or 5 seconds, and immediately on trip end)
 *   so camera inference is never impacted by SQLite I/O.
 */
@Singleton
class HeartRateRepository @Inject constructor(
    private val wearableDao: WearableDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(HeartRateTelemetryState())
    val state: StateFlow<HeartRateTelemetryState> = _state.asStateFlow()

    val persistedRecentHeartRates = wearableDao.observeRecentHeartRates(limit = 120)

    private val sessionSamples = ArrayDeque<HeartRateSamplePoint>(MAX_CHART_POINTS)
    private var sessionMinBpm: Int? = null
    private var sessionMaxBpm: Int? = null
    private var sessionSumBpm: Long = 0L
    private var sessionCount: Int = 0

    // Active Trip tracking
    @Volatile
    private var activeTripId: String? = null
    private var tripMinBpm: Int? = null
    private var tripMaxBpm: Int? = null
    private var tripSumBpm: Long = 0L
    private var tripSampleCount: Int = 0
    private val tripDisconnectGaps = mutableListOf<TripHeartRateDisconnectGap>()
    private var openDisconnectStartMs: Long? = null

    // Batched DB queue to minimize disk writes during high-rate 1Hz BLE notifications
    private val pendingDbBatch = mutableListOf<HeartRateMeasurementEntity>()
    private var lastInsertedSecondEpoch: Long = -1L

    init {
        // Load persisted historical stats on startup (marked as historical, NOT live)
        scope.launch {
            wearableDao.observeRecentHeartRates(limit = 90).collect { entities ->
                if (entities.isNotEmpty() && sessionSamples.isEmpty()) {
                    val chronological = entities.reversed()
                    val pts = chronological.map { e ->
                        HeartRateSamplePoint(
                            bpm = e.bpm,
                            timestampMillis = e.timestampMillis,
                            deviceAddress = e.deviceAddress,
                            dataSource = e.dataSource,
                        )
                    }
                    val bpms = chronological.map { it.bpm }
                    val minVal = bpms.minOrNull()
                    val maxVal = bpms.maxOrNull()
                    val avgVal = if (bpms.isNotEmpty()) bpms.average().roundToInt() else null
                    val latest = entities.first()
                    val now = System.currentTimeMillis()
                    val fresh = (now - latest.timestampMillis) <= STALE_THRESHOLD_MS &&
                        _state.value.connectionStatus == SmartwatchConnectionState.SUBSCRIBED_LIVE.name

                    _state.value = _state.value.copy(
                        currentBpm = if (fresh) latest.bpm else null,
                        isLiveReading = fresh,
                        isStale = !fresh,
                        lastMeasurementTimestampMillis = latest.timestampMillis,
                        deviceAddress = latest.deviceAddress,
                        deviceName = latest.deviceName,
                        dataSource = latest.dataSource,
                        minBpm = sessionMinBpm ?: minVal,
                        maxBpm = sessionMaxBpm ?: maxVal,
                        avgBpm = if (sessionCount > 0) (sessionSumBpm.toDouble() / sessionCount).roundToInt() else avgVal,
                        historyPoints = pts,
                    )
                }
            }
        }

        // Background stale watchdog & periodic DB batch flusher
        scope.launch {
            while (true) {
                delay(3_000L)
                checkStaleness(System.currentTimeMillis())
                flushPendingDbBatch()
            }
        }
    }

    @Synchronized
    fun setActiveTripId(tripId: String?, nowMillis: Long = System.currentTimeMillis()) {
        if (tripId != null && tripId != activeTripId) {
            activeTripId = tripId
            tripMinBpm = null
            tripMaxBpm = null
            tripSumBpm = 0L
            tripSampleCount = 0
            tripDisconnectGaps.clear()
            val connected = _state.value.connectionStatus == SmartwatchConnectionState.SUBSCRIBED_LIVE.name
            openDisconnectStartMs = if (connected) null else nowMillis
            updateActiveTripSummaryState()
        } else if (tripId == null && activeTripId != null) {
            closeOpenDisconnectGap(nowMillis)
            flushPendingDbBatch()
            activeTripId = null
        }
    }

    @Synchronized
    fun startTripMonitoring(tripId: String, nowMillis: Long = System.currentTimeMillis()) {
        setActiveTripId(tripId, nowMillis)
    }

    @Synchronized
    fun finalizeTripMonitoring(nowMillis: Long = System.currentTimeMillis()): TripHeartRateSummary {
        closeOpenDisconnectGap(nowMillis)
        flushPendingDbBatch()
        val summary = buildCurrentTripSummary()
        activeTripId = null
        openDisconnectStartMs = null
        _state.value = _state.value.copy(activeTripSummary = summary)
        return summary
    }

    @Synchronized
    fun onConnectionStateChanged(
        stateName: String,
        deviceAddress: String?,
        deviceName: String?,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val isConnectedLive = (stateName == SmartwatchConnectionState.SUBSCRIBED_LIVE.name)
        if (!isConnectedLive) {
            // Immediately clear live indicator on disconnection so old BPM is never displayed as live
            if (activeTripId != null && openDisconnectStartMs == null) {
                openDisconnectStartMs = nowMillis
            }
            flushPendingDbBatch()
            _state.value = _state.value.copy(
                currentBpm = null,
                isLiveReading = false,
                isStale = _state.value.lastMeasurementTimestampMillis != null,
                connectionStatus = stateName,
                deviceAddress = deviceAddress ?: _state.value.deviceAddress,
                deviceName = deviceName ?: _state.value.deviceName,
            )
            updateActiveTripSummaryState()
        } else {
            closeOpenDisconnectGap(nowMillis)
            _state.value = _state.value.copy(
                connectionStatus = stateName,
                deviceAddress = deviceAddress ?: _state.value.deviceAddress,
                deviceName = deviceName ?: _state.value.deviceName,
            )
            updateActiveTripSummaryState()
        }
    }

    @Synchronized
    fun onBleHeartRateReceived(
        deviceAddress: String,
        deviceName: String,
        decoded: DecodedBleHeartRate,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        if (!decoded.isValid) {
            _state.value = _state.value.copy(
                currentBpm = null,
                isLiveReading = false,
                sensorContactSupported = decoded.sensorContactSupported,
                sensorContactDetected = decoded.sensorContactDetected,
                invalidReadingNotice = decoded.invalidReason ?: "Invalid heart-rate sample",
            )
            return
        }

        closeOpenDisconnectGap(timestampMillis)

        // Deduplicate sub-second duplicate packets from the same watch
        val secondEpoch = timestampMillis / 1000L
        val isDuplicateSecond = (secondEpoch == lastInsertedSecondEpoch) &&
            (_state.value.deviceAddress == deviceAddress) &&
            (_state.value.currentBpm == decoded.bpm)
        lastInsertedSecondEpoch = secondEpoch

        val bpm = decoded.bpm
        val prevSampleTs = sessionSamples.lastOrNull()?.timestampMillis
        val hadGap = prevSampleTs != null && (timestampMillis - prevSampleTs) > STALE_THRESHOLD_MS
        if (hadGap && sessionSamples.isNotEmpty()) {
            val last = sessionSamples.removeLast()
            sessionSamples.addLast(last.copy(isGapAfter = true))
        }

        val point = HeartRateSamplePoint(
            bpm = bpm,
            timestampMillis = timestampMillis,
            deviceAddress = deviceAddress,
            dataSource = SOURCE_BLE_GATT,
        )
        if (sessionSamples.size >= MAX_CHART_POINTS) {
            sessionSamples.removeFirst()
        }
        sessionSamples.addLast(point)

        sessionMinBpm = sessionMinBpm?.let { minOf(it, bpm) } ?: bpm
        sessionMaxBpm = sessionMaxBpm?.let { maxOf(it, bpm) } ?: bpm
        sessionSumBpm += bpm
        sessionCount += 1
        val sessionAvg = (sessionSumBpm.toDouble() / sessionCount.toDouble()).roundToInt()

        if (activeTripId != null) {
            tripMinBpm = tripMinBpm?.let { minOf(it, bpm) } ?: bpm
            tripMaxBpm = tripMaxBpm?.let { maxOf(it, bpm) } ?: bpm
            tripSumBpm += bpm
            tripSampleCount += 1
        }

        val tripSummary = if (activeTripId != null) buildCurrentTripSummary() else _state.value.activeTripSummary

        _state.value = _state.value.copy(
            currentBpm = bpm,
            isLiveReading = true,
            isStale = false,
            lastMeasurementTimestampMillis = timestampMillis,
            deviceAddress = deviceAddress,
            deviceName = deviceName,
            dataSource = SOURCE_BLE_GATT,
            connectionStatus = SmartwatchConnectionState.SUBSCRIBED_LIVE.name,
            sensorContactSupported = decoded.sensorContactSupported,
            sensorContactDetected = decoded.sensorContactDetected,
            energyExpendedKj = decoded.energyExpendedKj ?: _state.value.energyExpendedKj,
            latestRrIntervalMs = decoded.rrIntervalsMs.lastOrNull() ?: _state.value.latestRrIntervalMs,
            minBpm = sessionMinBpm,
            maxBpm = sessionMaxBpm,
            avgBpm = sessionAvg,
            totalValidSamplesInSession = sessionCount,
            historyPoints = sessionSamples.toList(),
            activeTripSummary = tripSummary,
            invalidReadingNotice = null,
        )

        if (!isDuplicateSecond) {
            val entity = HeartRateMeasurementEntity(
                deviceAddress = deviceAddress,
                deviceName = deviceName,
                tripId = activeTripId,
                bpm = bpm,
                timestampMillis = timestampMillis,
                dataSource = SOURCE_BLE_GATT,
                connectionStatus = SmartwatchConnectionState.SUBSCRIBED_LIVE.name,
                isValid = true,
                sensorContactSupported = decoded.sensorContactSupported,
                sensorContactDetected = decoded.sensorContactDetected,
                energyExpendedKj = decoded.energyExpendedKj,
                latestRrIntervalMs = decoded.rrIntervalsMs.lastOrNull(),
                createdAtMillis = timestampMillis,
            )
            pendingDbBatch.add(entity)
            if (pendingDbBatch.size >= DB_BATCH_SIZE) {
                flushPendingDbBatch()
            }
        }
    }

    @Synchronized
    fun checkStaleness(nowMillis: Long = System.currentTimeMillis()) {
        val lastTs = _state.value.lastMeasurementTimestampMillis ?: return
        val ageMs = nowMillis - lastTs
        if (ageMs > STALE_THRESHOLD_MS && (_state.value.isLiveReading || !_state.value.isStale)) {
            if (activeTripId != null && openDisconnectStartMs == null) {
                openDisconnectStartMs = lastTs + STALE_THRESHOLD_MS
            }
            _state.value = _state.value.copy(
                currentBpm = null,
                isLiveReading = false,
                isStale = true,
            )
            updateActiveTripSummaryState()
        }
    }

    private fun closeOpenDisconnectGap(nowMillis: Long) {
        val gapStart = openDisconnectStartMs ?: return
        if (nowMillis > gapStart) {
            tripDisconnectGaps.add(
                TripHeartRateDisconnectGap(
                    disconnectedAtMillis = gapStart,
                    reconnectedAtMillis = nowMillis,
                ),
            )
        }
        openDisconnectStartMs = null
    }

    private fun buildCurrentTripSummary(): TripHeartRateSummary {
        val avg = if (tripSampleCount > 0) (tripSumBpm.toDouble() / tripSampleCount.toDouble()).roundToInt() else null
        val gaps = buildList {
            addAll(tripDisconnectGaps)
            openDisconnectStartMs?.let { add(TripHeartRateDisconnectGap(it, null)) }
        }
        return TripHeartRateSummary(
            tripId = activeTripId,
            minBpm = tripMinBpm,
            maxBpm = tripMaxBpm,
            avgBpm = avg,
            sampleCount = tripSampleCount,
            disconnectedGapsCount = gaps.size,
            disconnectedIntervals = gaps,
        )
    }

    private fun updateActiveTripSummaryState() {
        if (activeTripId != null) {
            _state.value = _state.value.copy(activeTripSummary = buildCurrentTripSummary())
        }
    }

    @Synchronized
    private fun flushPendingDbBatch() {
        if (pendingDbBatch.isEmpty()) return
        val batchCopy = pendingDbBatch.toList()
        pendingDbBatch.clear()
        scope.launch {
            runCatching { wearableDao.insertHeartRateBatch(batchCopy) }
        }
    }

    @Synchronized
    fun clearAllInMemoryState() {
        sessionSamples.clear()
        sessionMinBpm = null
        sessionMaxBpm = null
        sessionSumBpm = 0L
        sessionCount = 0
        pendingDbBatch.clear()
        _state.value = HeartRateTelemetryState()
    }

    companion object {
        const val STALE_THRESHOLD_MS = 15_000L
        const val MAX_CHART_POINTS = 90
        const val DB_BATCH_SIZE = 4
        const val SOURCE_BLE_GATT = "BLE_GATT_0x180D"
        const val SOURCE_HEALTH_CONNECT = "HEALTH_CONNECT"
    }
}
