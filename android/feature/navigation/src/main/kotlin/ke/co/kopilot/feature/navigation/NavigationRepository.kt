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
        val trimmed = query.trim()
        val offlineMatches = kenyanCities
            .filter { (name, _) -> trimmed.isBlank() || name.contains(trimmed, ignoreCase = true) }
            .map { (name, coords) ->
                PlaceItemDto(
                    placeId = "ke_${name.lowercase()}",
                    name = name,
                    formattedAddress = "$name, Kenya (Offline Preset)",
                    latitude = coords.latitude,
                    longitude = coords.longitude,
                )
            }
        if (!connectivityObserver.isCurrentlyOnline()) {
            _state.value = _state.value.copy(
                searchResults = offlineMatches,
                isSearching = false,
                errorMessage = if (offlineMatches.isEmpty()) "Offline: no matching cached city found" else null,
            )
            return offlineMatches
        }
        _state.value = _state.value.copy(isSearching = true, errorMessage = null)
        return runCatching {
            val nominatimResults = runCatching { searchPlacesNominatim(trimmed, lat, lng) }.getOrDefault(emptyList())
            val results = if (nominatimResults.isNotEmpty()) {
                nominatimResults
            } else {
                val directKey = tokenStore.getGoogleMapsApiKey()
                if (directKey != null) {
                    searchPlacesDirect(directKey, trimmed, lat, lng)
                } else {
                    runCatching {
                        apiService.searchPlaces(query = trimmed, latitude = lat, longitude = lng).results
                    }.getOrDefault(offlineMatches)
                }
            }
            _state.value = _state.value.copy(searchResults = results, isSearching = false)
            results
        }.getOrElse { err ->
            _state.value = _state.value.copy(
                searchResults = offlineMatches,
                isSearching = false,
                errorMessage = if (offlineMatches.isEmpty()) {
                    "Places search unavailable: ${err.localizedMessage ?: "network error"}"
                } else null,
            )
            offlineMatches
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
            _state.value = _state.value.copy(errorMessage = "Offline: turn-by-turn routing requires internet")
            return false
        }
        _state.value = _state.value.copy(
            isComputingRoute = true,
            destinationName = destName,
            destinationLatLng = LatLng(destLat, destLng),
            errorMessage = null,
        )
        return runCatching {
            val osrmRoutes = runCatching {
                computeRoutesOsrm(originLat, originLng, destLat, destLng, avoidTolls)
            }.getOrDefault(emptyList())
            val routes = if (osrmRoutes.isNotEmpty()) {
                osrmRoutes
            } else {
                val directKey = tokenStore.getGoogleMapsApiKey()
                if (directKey != null) {
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
            }
            val primary = routes.firstOrNull()
            if (primary == null) {
                _state.value = _state.value.copy(
                    isComputingRoute = false,
                    errorMessage = "No route returned by OpenStreetMap OSRM",
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

    private suspend fun searchPlacesNominatim(
        query: String,
        lat: Double?,
        lng: Double?,
    ): List<PlaceItemDto> = withContext(dispatchers.io) {
        if (query.isBlank()) return@withContext emptyList()
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val viewBoxParam = if (lat != null && lng != null) {
            val delta = 0.6
            "&viewbox=${lng - delta},${lat + delta},${lng + delta},${lat - delta}"
        } else {
            ""
        }
        val url = "https://nominatim.openstreetmap.org/search?format=json&addressdetails=1&countrycodes=ke&limit=8&q=$encodedQuery$viewBoxParam"
        val req = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "KoPilot/2.9.5 (Android OpenStreetMap Navigation)")
            .addHeader("Accept-Language", "en")
            .get()
            .build()
        val raw = okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Nominatim HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        parseNominatimPlaces(raw, json)
    }

    private suspend fun computeRoutesOsrm(
        originLat: Double,
        originLng: Double,
        destLat: Double,
        destLng: Double,
        avoidTolls: Boolean,
    ): List<RouteOptionDto> = withContext(dispatchers.io) {
        val excludeParam = if (avoidTolls) "&exclude=toll" else ""
        val url = "https://router.project-osrm.org/route/v1/driving/$originLng,$originLat;$destLng,$destLat?overview=full&geometries=geojson&steps=true&alternatives=true$excludeParam"
        val req = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "KoPilot/2.9.5 (Android OpenStreetMap Navigation)")
            .get()
            .build()
        val raw = okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("OSRM HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        parseOsrmRoutes(raw, json)
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
        fun parseNominatimPlaces(
            raw: String,
            jsonParser: Json = Json { ignoreUnknownKeys = true },
        ): List<PlaceItemDto> {
            if (raw.isBlank()) return emptyList()
            val arr = jsonParser.parseToJsonElement(raw).jsonArray
            return arr.mapNotNull { el ->
                val o = el.jsonObject
                val lat = o["lat"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
                val lon = o["lon"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@mapNotNull null
                val displayName = o["display_name"]?.jsonPrimitive?.content.orEmpty()
                val shortName = o["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: displayName.substringBefore(",").trim().ifBlank { "Destination" }
                PlaceItemDto(
                    placeId = "osm_${o["place_id"]?.jsonPrimitive?.content ?: "${lat}_$lon"}",
                    name = shortName,
                    formattedAddress = displayName.ifBlank { "$shortName, Kenya" },
                    latitude = lat,
                    longitude = lon,
                )
            }
        }

        fun parseOsrmRoutes(
            raw: String,
            jsonParser: Json = Json { ignoreUnknownKeys = true },
        ): List<RouteOptionDto> {
            if (raw.isBlank()) return emptyList()
            val root = jsonParser.parseToJsonElement(raw).jsonObject
            val routesArr = root["routes"]?.jsonArray ?: return emptyList()
            return routesArr.mapIndexedNotNull { idx, el ->
                val r = el.jsonObject
                val coords = r["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return@mapIndexedNotNull null
                val points = coords.mapNotNull { ptEl ->
                    val pair = ptEl.jsonArray
                    if (pair.size >= 2) {
                        val lon = pair[0].jsonPrimitive.doubleOrNull
                        val lat = pair[1].jsonPrimitive.doubleOrNull
                        if (lat != null && lon != null) LatLng(lat, lon) else null
                    } else null
                }
                if (points.isEmpty()) return@mapIndexedNotNull null

                val distanceM = r["distance"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: 0
                val durationS = r["duration"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: 0
                val firstLeg = r["legs"]?.jsonArray?.firstOrNull()?.jsonObject
                val legSummary = firstLeg?.get("summary")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val steps = firstLeg?.get("steps")?.jsonArray?.map { sEl ->
                    val sObj = sEl.jsonObject
                    val maneuver = sObj["maneuver"]?.jsonObject
                    val mType = maneuver?.get("type")?.jsonPrimitive?.content.orEmpty()
                    val mMod = maneuver?.get("modifier")?.jsonPrimitive?.content.orEmpty()
                    val street = sObj["name"]?.jsonPrimitive?.content.orEmpty()
                    RouteStepSummaryDto(
                        instruction = formatOsrmManeuver(mType, mMod, street),
                        distanceMeters = sObj["distance"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: 0,
                        durationSeconds = sObj["duration"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: 0,
                    )
                }.orEmpty()

                RouteOptionDto(
                    routeId = "osrm_$idx",
                    summary = legSummary?.let { "Via $it (OSM)" } ?: "OSM Route ${idx + 1}",
                    distanceMeters = distanceM,
                    durationSeconds = durationS,
                    staticDurationSeconds = durationS,
                    trafficDelaySeconds = 0,
                    encodedPolyline = encodePolyline(points),
                    steps = steps,
                )
            }
        }

        private fun formatOsrmManeuver(type: String, modifier: String, streetName: String): String {
            val onRoad = if (streetName.isNotBlank()) " onto $streetName" else ""
            val dir = modifier.replace('-', ' ').trim()
            return when (type) {
                "depart" -> if (streetName.isNotBlank()) "Start on $streetName" else "Start driving"
                "arrive" -> "Arrive at destination"
                "turn", "end of road", "fork", "off ramp", "on ramp" -> {
                    val action = if (dir.isNotBlank()) "Turn $dir" else "Turn"
                    "$action$onRoad"
                }
                "roundabout", "rotary" -> "Take the roundabout$onRoad"
                "merge" -> "Merge${if (dir.isNotBlank()) " $dir" else ""}$onRoad"
                "new name", "continue" -> if (streetName.isNotBlank()) "Continue onto $streetName" else "Continue straight"
                else -> if (streetName.isNotBlank()) "Continue on $streetName" else "Continue"
            }
        }

        fun encodePolyline(points: List<LatLng>): String {
            val result = StringBuilder()
            var prevLat = 0
            var prevLng = 0
            for (pt in points) {
                val lat = Math.round(pt.latitude * 1E5).toInt()
                val lng = Math.round(pt.longitude * 1E5).toInt()
                encodeSignedValue(lat - prevLat, result)
                encodeSignedValue(lng - prevLng, result)
                prevLat = lat
                prevLng = lng
            }
            return result.toString()
        }

        private fun encodeSignedValue(value: Int, out: StringBuilder) {
            var v = if (value < 0) (value shl 1).inv() else (value shl 1)
            while (v >= 0x20) {
                out.append(((0x20 or (v and 0x1f)) + 63).toChar())
                v = v shr 5
            }
            out.append((v + 63).toChar())
        }

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
