package ke.co.kopilot.feature.voice

import kotlinx.serialization.json.JsonObject

interface VoiceToolExecutor {
    suspend fun executeTool(name: String, arguments: JsonObject): String
}

object VoiceToolSchemaRegistry {
    val toolNames = listOf(
        "get_current_location",
        "get_current_speed",
        "get_active_route",
        "get_traffic_conditions",
        "find_alternative_routes",
        "get_weather_forecast",
        "search_nearby_places",
        "get_recent_road_observations",
        "get_vehicle_diagnostics",
        "get_trip_summary",
    )

    fun buildSessionUpdateJson(language: String): String {
        val langNote = if (language == "sw") {
            "Respond concisely in Kiswahili."
        } else {
            "Respond concisely in English, or switch to Kiswahili if requested."
        }
        return """
        {
          "type": "session.update",
          "session": {
            "type": "realtime",
            "instructions": "You are KoPilot, a calm driving assistant for Kenyan motorists. Always call tools for live data and never invent readings. $langNote",
            "audio": {
              "input": {
                "turn_detection": {
                  "type": "server_vad",
                  "interrupt_response": true
                }
              }
            },
            "tools": [
              {"type":"function","name":"get_current_location","description":"Get current GPS coordinates and signal status.","parameters":{"type":"object","properties":{}}},
              {"type":"function","name":"get_current_speed","description":"Get current GPS speed in km/h and observed speed limit.","parameters":{"type":"object","properties":{}}},
              {"type":"function","name":"get_active_route","description":"Get active navigation destination, ETA, distance remaining, and next step.","parameters":{"type":"object","properties":{}}},
              {"type":"function","name":"get_traffic_conditions","description":"Get live traffic delay along the active route.","parameters":{"type":"object","properties":{}}},
              {"type":"function","name":"find_alternative_routes","description":"Check if an alternative route saves meaningful time.","parameters":{"type":"object","properties":{}}},
              {"type":"function","name":"get_weather_forecast","description":"Get current and forecast weather near the driver or destination.","parameters":{"type":"object","properties":{}}},
              {"type":"function","name":"search_nearby_places","description":"Search nearby places in Kenya such as petrol stations or hospitals.","parameters":{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}},
              {"type":"function","name":"get_recent_road_observations","description":"Get recently detected road signs from the camera.","parameters":{"type":"object","properties":{}}},
              {"type":"function","name":"get_vehicle_diagnostics","description":"Get OBD-II engine RPM, coolant temperature, and DTC status.","parameters":{"type":"object","properties":{}}},
              {"type":"function","name":"get_trip_summary","description":"Get current or latest trip distance, duration, and speed summary.","parameters":{"type":"object","properties":{}}}
            ]
          }
        }
        """.trimIndent()
    }
}
