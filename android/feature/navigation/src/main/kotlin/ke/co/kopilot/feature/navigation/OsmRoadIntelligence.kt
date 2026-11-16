package ke.co.kopilot.feature.navigation

import ke.co.kopilot.core.common.coroutines.DispatcherProvider
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

data class RoadMapIntelligence(
    val roadName: String? = null,
    val mappedSpeedLimitKmh: Int? = null,
    val nearestSpeedCameraMeters: Int? = null,
    val speedCamerasWithin1500m: Int = 0,
    val fetchedAtMillis: Long = 0L,
)

@Singleton
class OsmRoadIntelligence @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val dispatchers: DispatcherProvider,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var lastQueryLat = 0.0
    private var lastQueryLng = 0.0
    private var lastQueryMs = 0L
    private var cached: RoadMapIntelligence = RoadMapIntelligence()

    suspend fun queryNear(latitude: Double, longitude: Double): RoadMapIntelligence = withContext(dispatchers.io) {
        val now = System.currentTimeMillis()
        val movedMeters = NavigationRepository.haversineMeters(lastQueryLat, lastQueryLng, latitude, longitude)
        if ((now - lastQueryMs) < 45_000L && movedMeters < 150.0 && lastQueryMs > 0L) {
            return@withContext cached
        }

        val query = """
            [out:json][timeout:6];
            (
              way(around:55,$latitude,$longitude)[highway][maxspeed];
              node(around:1500,$latitude,$longitude)[highway=speed_camera];
            );
            out tags center;
        """.trimIndent()

        val result = runCatching {
            val req = Request.Builder()
                .url("https://overpass-api.de/api/interpreter")
                .post("data=$query".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            val body = okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Overpass HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
            val elements = json.parseToJsonElement(body).jsonObject["elements"]?.jsonArray
                ?: return@runCatching RoadMapIntelligence(fetchedAtMillis = now)

            var roadName: String? = null
            var maxSpeed: Int? = null
            var camCount = 0
            var minCamDist: Int? = null

            for (el in elements) {
                val obj = el.jsonObject
                val type = obj["type"]?.jsonPrimitive?.content
                val tags = obj["tags"]?.jsonObject
                if (type == "way" && tags != null) {
                    val speedRaw = tags["maxspeed"]?.jsonPrimitive?.content.orEmpty()
                    val parsedSpeed = Regex("\\d+").find(speedRaw)?.value?.toIntOrNull()
                    if (parsedSpeed != null && maxSpeed == null) {
                        maxSpeed = parsedSpeed
                        roadName = tags["name"]?.jsonPrimitive?.content
                    }
                } else if (type == "node" && tags?.get("highway")?.jsonPrimitive?.content == "speed_camera") {
                    camCount++
                    val camLat = obj["lat"]?.jsonPrimitive?.doubleOrNull
                    val camLng = obj["lon"]?.jsonPrimitive?.doubleOrNull
                    if (camLat != null && camLng != null) {
                        val d = NavigationRepository.haversineMeters(latitude, longitude, camLat, camLng).toInt()
                        if (minCamDist == null || d < minCamDist) minCamDist = d
                    }
                }
            }
            RoadMapIntelligence(
                roadName = roadName,
                mappedSpeedLimitKmh = maxSpeed,
                nearestSpeedCameraMeters = minCamDist,
                speedCamerasWithin1500m = camCount,
                fetchedAtMillis = now,
            )
        }.getOrDefault(cached)

        lastQueryLat = latitude
        lastQueryLng = longitude
        lastQueryMs = now
        cached = result
        result
    }
}
