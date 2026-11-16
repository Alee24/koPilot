package ke.co.kopilot.feature.navigation

import com.google.android.gms.maps.model.LatLng
import ke.co.kopilot.core.common.coroutines.DispatcherProvider
import ke.co.kopilot.core.network.api.KoPilotApiService
import ke.co.kopilot.core.network.connectivity.ConnectivityObserver
import ke.co.kopilot.core.network.dto.LatLngDto
import ke.co.kopilot.core.network.dto.PlaceItemDto
import ke.co.kopilot.core.network.dto.RouteComputeRequestDto
import ke.co.kopilot.core.network.dto.RouteOptionDto
import ke.co.kopilot.core.network.dto.RouteStepSummaryDto
import ke.co.kopilot.core.security.SecureTokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class NavigationUiState(
    val destinationName: String? = null,
    val destinationLatLng: LatLng? = null,
    val activeRoute: RouteOptionDto? = null,
    val alternativeRoutes: List<RouteOptionDto> = emptyList(),
    val decodedPoints: List<LatLng> = emptyList(),
    val currentStepIndex: Int = 0,
    val searchResults: List<PlaceItemDto> = emptyList(),
    val roadIntelligence: RoadMapIntelligence = RoadMapIntelligence(),
    val isSearching: Boolean = false,
    val isComputingRoute: Boolean = false,
    val errorMessage: String? = null,
)

@Singleton
class NavigationRepository @Inject constructor(
    private val apiService: KoPilotApiService,
    private val connectivityObserver: ConnectivityObserver,
    private val tokenStore: SecureTokenStore,
    private val osmRoadIntelligence: OsmRoadIntelligence,
    private val okHttpClient: OkHttpClient,
    private val dispatchers: DispatcherProvider,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(NavigationUiState())
    val state: StateFlow<NavigationUiState> = _state.asStateFlow()

    val kenyanCities: List<Pair<String, LatLng>> = listOf(
        "Nairobi CBD" to LatLng(-1.286389, 36.817223),
        "Mombasa" to LatLng(-4.043477, 39.668206),
        "Kisumu" to LatLng(-0.091702, 34.767956),
        "Nakuru" to LatLng(-0.303099, 36.080026),
        "Eldoret" to LatLng(0.514277, 35.269780),
        "Thika" to LatLng(-1.033200, 37.069300),
        "Kiambu" to LatLng(-1.171400, 36.835600),
        "Machakos" to LatLng(-1.517684, 37.263414),
        "Naivasha" to LatLng(-0.717178, 36.431026),
        "Nyeri" to LatLng(-0.419700, 36.947600),
    )

    suspend fun searchPlaces(query: String, lat: Double?, lng: Double?): List<PlaceItemDto> {
        if (!connectivityObserver.isCurrentlyOnline()) {
            _state.value = _state.value.copy(errorMessage = "Offline: destination search unavailable")
            return emptyList()
        }
        _state.value = _state.value.copy(isSearching = true, errorMessage = null)
        return runCatching {
            val directKey = tokenStore.getGoogleMapsApiKey()
            val results = if (directKey != null) {
                searchPlacesDirect(directKey, query, lat, lng)
            } else {
                apiService.searchPlaces(query = query, latitude = lat, longitude = lng).results
            }
            _state.value = _state.value.copy(searchResults = results, isSearching = false)
            results
        }.getOrElse { err ->
            _state.value = _state.value.copy(
                isSearching = false,
                errorMessage = "Places search unavailable: ${err.localizedMessage ?: "network error"}",
            )
            emptyList()
        }
    }

    suspend fun navigateTo(
        originLat: Double,
        originLng: Double,
        destName: String,
        destLat: Double,
        destLng: Double,
        avoidTolls: Boolean,
    ): Boolean {
        if (!connectivityObserver.isCurrentlyOnline()) {
            _state.value = _state.value.copy(errorMessage = "Offline: cloud routing unavailable")
            return false
        }
        _state.value = _state.value.copy(
            isComputingRoute = true,
            destinationName = destName,
            destinationLatLng = LatLng(destLat, destLng),
            errorMessage = null,
        )
        return runCatching {
            val directKey = tokenStore.getGoogleMapsApiKey()
            val routes = if (directKey != null) {
                computeRoutesDirect(directKey, originLat, originLng, destLat, destLng, avoidTolls)
            } else {
                apiService.computeRoutes(
                    RouteComputeRequestDto(
                        origin = LatLngDto(originLat, originLng),
                        destination = LatLngDto(destLat, destLng),
                        avoidTolls = avoidTolls,
                        computeAlternatives = true,
                    ),
                ).routes
            }
            val primary = routes.firstOrNull()
            if (primary == null) {
                _state.value = _state.value.copy(
                    isComputingRoute = false,
                    errorMessage = "No route returned by routing provider",
                )
                return false
            }
            _state.value = _state.value.copy(
                activeRoute = primary,
                alternativeRoutes = routes.drop(1),
                decodedPoints = decodePolyline(primary.encodedPolyline),
                currentStepIndex = 0,
                isComputingRoute = false,
            )
            true
        }.getOrElse { err ->
            _state.value = _state.value.copy(
                isComputingRoute = false,
                errorMessage = "Routing unavailable: ${err.localizedMessage ?: "provider error"}",
            )
            false
        }
    }

    private suspend fun searchPlacesDirect(
        apiKey: String,
        query: String,
        lat: Double?,
        lng: Double?,
    ): List<PlaceItemDto> = withContext(dispatchers.io) {
        val bias = if (lat != null && lng != null) {
            ""","locationBias":{"circle":{"center":{"latitude":$lat,"longitude":$lng},"radius":15000.0}}"""
        } else {
            ""
        }
        val body = """{"textQuery":"${query.replace("\"", "")}","regionCode":"KE","maxResultCount":10$bias}"""
        val req = Request.Builder()
            .url("https://places.googleapis.com/v1/places:searchText")
            .addHeader("X-Goog-Api-Key", apiKey)
            .addHeader("X-Goog-FieldMask", "places.id,places.displayName,places.formattedAddress,places.location,places.types")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val raw = okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Google Places HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        val arr = json.parseToJsonElement(raw).jsonObject["places"]?.jsonArray ?: return@withContext emptyList()
        arr.map { el ->
            val o = el.jsonObject
            val loc = o["location"]?.jsonObject
            PlaceItemDto(
                placeId = o["id"]?.jsonPrimitive?.content.orEmpty(),
                name = o["displayName"]?.jsonObject?.get("text")?.jsonPrimitive?.content ?: "Place",
                formattedAddress = o["formattedAddress"]?.jsonPrimitive?.content.orEmpty(),
                latitude = loc?.get("latitude")?.jsonPrimitive?.doubleOrNull ?: 0.0,
                longitude = loc?.get("longitude")?.jsonPrimitive?.doubleOrNull ?: 0.0,
            )
        }
    }

    private suspend fun computeRoutesDirect(
        apiKey: String,
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        avoidTolls: Boolean,
    ): List<RouteOptionDto> = withContext(dispatchers.io) {
        val body = """
            {
              "origin":{"location":{"latLng":{"latitude":$originLat,"longitude":$originLng}}},
              "destination":{"location":{"latLng":{"latitude":$destLat,"longitude":$destLng}}},
              "travelMode":"DRIVE",
              "routingPreference":"TRAFFIC_AWARE",
              "computeAlternativeRoutes":true,
              "routeModifiers":{"avoidTolls":$avoidTolls}
            }
        """.trimIndent()
        val req = Request.Builder()
            .url("https://routes.googleapis.com/directions/v2:computeRoutes")
            .addHeader("X-Goog-Api-Key", apiKey)
            .addHeader("X-Goog-FieldMask", "routes.description,routes.distanceMeters,routes.duration,routes.staticDuration,routes.polyline.encodedPolyline,routes.legs.steps.navigationInstruction,routes.legs.steps.distanceMeters")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val raw = okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Google Routes HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        val arr = json.parseToJsonElement(raw).jsonObject["routes"]?.jsonArray ?: return@withContext emptyList()
        arr.mapIndexed { idx, el ->
            val r = el.jsonObject
            val durS = r["duration"]?.jsonPrimitive?.content?.trimEnd('s')?.toDoubleOrNull()?.toInt() ?: 0
            val staticS = r["staticDuration"]?.jsonPrimitive?.content?.trimEnd('s')?.toDoubleOrNull()?.toInt() ?: 0
            val steps = r["legs"]?.jsonArray?.firstOrNull()?.jsonObject?.get("steps")?.jsonArray?.map { sEl ->
                val sObj = sEl.jsonObject
                RouteStepSummaryDto(
                    instruction = sObj["navigationInstruction"]?.jsonObject?.get("instructions")?.jsonPrimitive?.content ?: "Continue",
                    distanceMeters = sObj["distanceMeters"]?.jsonPrimitive?.intOrNull ?: 0,
                    durationSeconds = 0,
                )
            }.orEmpty()
            RouteOptionDto(
                routeId = "route_$idx",
                summary = r["description"]?.jsonPrimitive?.content ?: "Route ${idx + 1}",
                distanceMeters = r["distanceMeters"]?.jsonPrimitive?.intOrNull ?: 0,
                durationSeconds = durS,
                staticDurationSeconds = staticS,
                trafficDelaySeconds = (durS - staticS).coerceAtLeast(0),
                encodedPolyline = r["polyline"]?.jsonObject?.get("encodedPolyline")?.jsonPrimitive?.content.orEmpty(),
                steps = steps,
            )
        }
    }

    fun switchToAlternative(route: RouteOptionDto) {
        val current = _state.value.activeRoute
        val remainingAlts = _state.value.alternativeRoutes.filter { it.routeId != route.routeId } +
            listOfNotNull(current)
        _state.value = _state.value.copy(
            activeRoute = route,
            alternativeRoutes = remainingAlts,
            decodedPoints = decodePolyline(route.encodedPolyline),
            currentStepIndex = 0,
        )
    }

    fun isOffRoute(lat: Double, lng: Double, thresholdMeters: Double = 85.0): Boolean {
        val pts = _state.value.decodedPoints
        if (pts.isEmpty()) return false
        val minDist = pts.minOf { haversineMeters(lat, lng, it.latitude, it.longitude) }
        return minDist > thresholdMeters
    }

    fun clearRoute() {
        _state.value = NavigationUiState()
    }

    suspend fun refreshRoadIntelligence(lat: Double, lng: Double) {
        if (!connectivityObserver.isCurrentlyOnline()) return
        val intel = osmRoadIntelligence.queryNear(lat, lng)
        _state.value = _state.value.copy(roadIntelligence = intel)
    }

    companion object {
        fun decodePolyline(encoded: String): List<LatLng> {
            if (encoded.isEmpty()) return emptyList()
            val poly = mutableListOf<LatLng>()
            var index = 0
            var lat = 0
            var lng = 0
            while (index < encoded.length) {
                var b: Int
                var shift = 0
                var result = 0
                do {
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20 && index < encoded.length)
                val dlat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
                lat += dlat

                shift = 0
                result = 0
                do {
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f) shl shift)
                    shift += 5
                } while (b >= 0x20 && index < encoded.length)
                val dlng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
                lng += dlng

                poly.add(LatLng(lat / 1E5, lng / 1E5))
            }
            return poly
        }

        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            return 2 * r * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
