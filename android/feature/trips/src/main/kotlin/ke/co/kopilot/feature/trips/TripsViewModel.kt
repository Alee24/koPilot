package ke.co.kopilot.feature.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ke.co.kopilot.core.database.entity.TripEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.io.File
import javax.inject.Inject

@HiltViewModel
class TripsViewModel @Inject constructor(
    private val tripRepo: TripRepository,
    val tripActivityLogger: TripActivityLogger,
) : ViewModel() {

    val trips: StateFlow<List<TripEntity>> = tripRepo.trips.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val activeLogPath: StateFlow<String?> = tripActivityLogger.activeLogPath
    val activeLineCount: StateFlow<Int> = tripActivityLogger.activeLineCount

    fun findLogFile(startedAtMillis: Long): File? =
        tripActivityLogger.findLogFileForTrip(startedAtMillis)

    fun getActiveLogFile(): File? =
        tripActivityLogger.getActiveLogFile()
}
