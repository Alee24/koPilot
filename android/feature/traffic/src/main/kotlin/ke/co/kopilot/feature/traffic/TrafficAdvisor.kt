package ke.co.kopilot.feature.traffic

import ke.co.kopilot.core.network.dto.RouteOptionDto
import javax.inject.Inject
import javax.inject.Singleton

data class TrafficEvaluation(
    val delayMinutes: Int,
    val statusLabel: String,
    val recommendedAlternative: RouteOptionDto?,
    val estimatedMinutesSaved: Int,
    val announcementText: String?,
)

@Singleton
class TrafficAdvisor @Inject constructor() {

    fun evaluate(
        activeRoute: RouteOptionDto?,
        alternatives: List<RouteOptionDto>,
    ): TrafficEvaluation {
        if (activeRoute == null) {
            return TrafficEvaluation(
                delayMinutes = 0,
                statusLabel = "No active route",
                recommendedAlternative = null,
                estimatedMinutesSaved = 0,
                announcementText = null,
            )
        }

        val delayMinutes = (activeRoute.trafficDelaySeconds / 60).coerceAtLeast(0)
        val statusLabel = when {
            delayMinutes >= 10 -> "Heavy delay (+$delayMinutes min)"
            delayMinutes >= 4 -> "Moderate traffic (+$delayMinutes min)"
            else -> "Traffic normal"
        }

        val bestAlt = alternatives
            .filter { it.routeId != activeRoute.routeId && it.durationSeconds > 0 }
            .minByOrNull { it.durationSeconds }

        val savedSeconds = if (bestAlt != null) activeRoute.durationSeconds - bestAlt.durationSeconds else 0
        val savedMinutes = savedSeconds / 60

        return if (bestAlt != null && savedSeconds >= MIN_MEANINGFUL_SAVING_SECONDS) {
            TrafficEvaluation(
                delayMinutes = delayMinutes,
                statusLabel = statusLabel,
                recommendedAlternative = bestAlt,
                estimatedMinutesSaved = savedMinutes,
                announcementText = "Traffic is increasing along your route. An alternative route via ${bestAlt.summary} may save approximately $savedMinutes minutes. Would you like to switch?",
            )
        } else {
            TrafficEvaluation(
                delayMinutes = delayMinutes,
                statusLabel = statusLabel,
                recommendedAlternative = null,
                estimatedMinutesSaved = 0,
                announcementText = null,
            )
        }
    }

    companion object {
        const val MIN_MEANINGFUL_SAVING_SECONDS = 180
    }
}
