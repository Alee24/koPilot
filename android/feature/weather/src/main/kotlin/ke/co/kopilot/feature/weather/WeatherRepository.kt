package ke.co.kopilot.feature.weather

import ke.co.kopilot.core.database.dao.CachedWeatherDao
import ke.co.kopilot.core.database.entity.CachedWeatherEntity
import ke.co.kopilot.core.network.api.KoPilotApiService
import ke.co.kopilot.core.network.connectivity.ConnectivityObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton

data class WeatherState(
    val available: Boolean = false,
    val isStaleOfflineCache: Boolean = false,
    val conditionSummary: String = "Weather unavailable",
    val temperatureC: Double? = null,
    val windSpeedKmh: Double? = null,
    val rainProbabilityPercent: Int? = null,
    val precipitationMm: Double? = null,
    val coverageNote: String = "",
    val fetchedAtMillis: Long? = null,
    val advisoryMessage: String? = null,
)

@Singleton
class WeatherRepository @Inject constructor(
    private val apiService: KoPilotApiService,
    private val weatherDao: CachedWeatherDao,
    private val connectivityObserver: ConnectivityObserver,
) {
    private val _state = MutableStateFlow(WeatherState())
    val state: StateFlow<WeatherState> = _state.asStateFlow()

    private var lastFetchMillis = 0L

    suspend fun refreshWeather(latitude: Double, longitude: Double, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastFetchMillis) < MIN_REFRESH_INTERVAL_MS && _state.value.available) {
            return
        }

        if (!connectivityObserver.isCurrentlyOnline()) {
            loadCachedFallback()
            return
        }

        runCatching {
            val dto = apiService.getWeather(latitude = latitude, longitude = longitude)
            lastFetchMillis = now
            val advisory = buildAdvisory(dto.rainProbabilityPercent, dto.windSpeedKmh)
            weatherDao.upsertWeather(
                CachedWeatherEntity(
                    locationKey = "${"%.2f".format(latitude)},${"%.2f".format(longitude)}",
                    latitude = latitude,
                    longitude = longitude,
                    temperatureC = dto.temperatureC,
                    windSpeedKmh = dto.windSpeedKmh,
                    precipitationMm = dto.precipitationMm,
                    rainProbabilityPercent = dto.rainProbabilityPercent,
                    conditionSummary = dto.conditionSummary,
                    coverageNote = dto.coverageNote,
                    fetchedAtMillis = now,
                ),
            )
            _state.value = WeatherState(
                available = true,
                isStaleOfflineCache = false,
                conditionSummary = dto.conditionSummary,
                temperatureC = dto.temperatureC,
                windSpeedKmh = dto.windSpeedKmh,
                rainProbabilityPercent = dto.rainProbabilityPercent,
                precipitationMm = dto.precipitationMm,
                coverageNote = dto.coverageNote,
                fetchedAtMillis = now,
                advisoryMessage = advisory,
            )
        }.onFailure {
            loadCachedFallback()
        }
    }

    private suspend fun loadCachedFallback() {
        val cached = weatherDao.observeLatestWeather().firstOrNull()
        if (cached != null) {
            _state.value = WeatherState(
                available = true,
                isStaleOfflineCache = true,
                conditionSummary = "${cached.conditionSummary} (Cached offline)",
                temperatureC = cached.temperatureC,
                windSpeedKmh = cached.windSpeedKmh,
                rainProbabilityPercent = cached.rainProbabilityPercent,
                precipitationMm = cached.precipitationMm,
                coverageNote = cached.coverageNote,
                fetchedAtMillis = cached.fetchedAtMillis,
                advisoryMessage = null,
            )
        } else {
            _state.value = WeatherState(
                available = false,
                conditionSummary = "Weather unavailable offline",
            )
        }
    }

    private fun buildAdvisory(rainProb: Int?, windKmh: Double?): String? = when {
        (rainProb ?: 0) >= 60 -> "Rain is forecast near your location."
        (windKmh ?: 0.0) >= 40.0 -> "Strong winds are forecast along part of your route."
        else -> null
    }

    companion object {
        const val MIN_REFRESH_INTERVAL_MS = 10 * 60 * 1000L
    }
}
