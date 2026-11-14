package ke.co.kopilot.feature.trips

import ke.co.kopilot.core.database.dao.TripDao
import ke.co.kopilot.core.database.entity.TripEntity
import ke.co.kopilot.core.network.api.KoPilotApiService
import ke.co.kopilot.core.network.connectivity.ConnectivityObserver
import ke.co.kopilot.core.network.dto.TripCreateRequestDto
import ke.co.kopilot.core.security.SecureTokenStore
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TripRepository @Inject constructor(
    private val tripDao: TripDao,
    private val apiService: KoPilotApiService,
    private val connectivityObserver: ConnectivityObserver,
    private val tokenStore: SecureTokenStore,
) {
    val trips: Flow<List<TripEntity>> = tripDao.observeTrips()

    suspend fun saveCompletedTrip(
        originLabel: String?,
        destinationLabel: String?,
        startedAtMillis: Long,
        endedAtMillis: Long,
        distanceKm: Double,
        maxSpeedKmh: Float,
        avgSpeedKmh: Float,
        signsDetectedCount: Int,
        alertsTriggeredCount: Int,
    ) {
        val durationSec = ((endedAtMillis - startedAtMillis) / 1000L).coerceAtLeast(0L)
        val id = UUID.randomUUID().toString()
        var synced = false

        if (connectivityObserver.isCurrentlyOnline()) {
            runCatching {
                apiService.syncTrip(
                    TripCreateRequestDto(
                        deviceId = tokenStore.getOrCreateDeviceId(),
                        originLabel = originLabel,
                        destinationLabel = destinationLabel,
                        startedAt = Instant.ofEpochMilli(startedAtMillis).toString(),
                        endedAt = Instant.ofEpochMilli(endedAtMillis).toString(),
                        distanceKm = distanceKm,
                        durationSeconds = durationSec,
                        maxSpeedKmh = maxSpeedKmh,
                        avgSpeedKmh = avgSpeedKmh,
                        signsDetectedCount = signsDetectedCount,
                        alertsTriggeredCount = alertsTriggeredCount,
                    ),
                )
                synced = true
            }
        }

        tripDao.upsertTrip(
            TripEntity(
                id = id,
                originLabel = originLabel,
                destinationLabel = destinationLabel,
                startedAtMillis = startedAtMillis,
                endedAtMillis = endedAtMillis,
                distanceKm = distanceKm,
                durationSeconds = durationSec,
                maxSpeedKmh = maxSpeedKmh,
                avgSpeedKmh = avgSpeedKmh,
                signsDetectedCount = signsDetectedCount,
                alertsTriggeredCount = alertsTriggeredCount,
                syncedToBackend = synced,
            ),
        )
    }

    suspend fun deleteAllLocalTrips() {
        tripDao.deleteAllTrips()
    }
}
