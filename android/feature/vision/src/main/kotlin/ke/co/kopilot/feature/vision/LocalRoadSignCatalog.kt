package ke.co.kopilot.feature.vision

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import ke.co.kopilot.core.common.model.TrafficSignCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class LocalRoadSignEntry(
    val category: String,
    val code: String,
    val title: String,
    val spokenAdvice: String,
    val shape: String,
)

@Singleton
class LocalRoadSignCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val entriesByCategory: Map<String, LocalRoadSignEntry> by lazy {
        runCatching {
            val raw = context.assets.open("signs/kenyan_road_signs.json")
                .bufferedReader()
                .use { it.readText() }
            json.decodeFromString<List<LocalRoadSignEntry>>(raw).associateBy { it.category }
        }.getOrDefault(emptyMap())
    }

    fun getEntry(category: TrafficSignCategory): LocalRoadSignEntry? = entriesByCategory[category.name]

    fun getSpokenAdvice(category: TrafficSignCategory, customLabel: String? = null): String {
        if (category == TrafficSignCategory.GENERAL_WARNING && !customLabel.isNullOrBlank()) {
            return "Road sign observed: $customLabel."
        }
        return entriesByCategory[category.name]?.spokenAdvice ?: "${category.displayName} observed."
    }

    fun allEntries(): List<LocalRoadSignEntry> = entriesByCategory.values.toList()
}
