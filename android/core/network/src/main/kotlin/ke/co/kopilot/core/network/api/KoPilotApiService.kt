package ke.co.kopilot.core.network.api

import ke.co.kopilot.core.network.dto.PlacesSearchResponseDto
import ke.co.kopilot.core.network.dto.RouteComputeRequestDto
import ke.co.kopilot.core.network.dto.RouteComputeResponseDto
import ke.co.kopilot.core.network.dto.TripCreateRequestDto
import ke.co.kopilot.core.network.dto.TripResponseDto
import ke.co.kopilot.core.network.dto.VoiceSessionRequestDto
import ke.co.kopilot.core.network.dto.VoiceSessionResponseDto
import ke.co.kopilot.core.network.dto.WeatherResponseDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface KoPilotApiService {
    @POST("api/v1/voice/session")
    suspend fun createVoiceSession(@Body request: VoiceSessionRequestDto): VoiceSessionResponseDto

    @POST("api/v1/routes/compute")
    suspend fun computeRoutes(@Body request: RouteComputeRequestDto): RouteComputeResponseDto

    @GET("api/v1/weather")
    suspend fun getWeather(
        @Query("lat") latitude: Double,
        @Query("lng") longitude: Double,
    ): WeatherResponseDto

    @GET("api/v1/places/search")
    suspend fun searchPlaces(
        @Query("q") query: String,
        @Query("lat") latitude: Double? = null,
        @Query("lng") longitude: Double? = null,
    ): PlacesSearchResponseDto

    @POST("api/v1/trips")
    suspend fun syncTrip(@Body trip: TripCreateRequestDto): TripResponseDto

    @DELETE("api/v1/account/data")
    suspend fun deleteAccountData()
}
