package ke.co.kopilot.feature.vehicle

import ke.co.kopilot.core.common.model.VehicleProfile
import ke.co.kopilot.core.database.dao.VehicleProfileDao
import ke.co.kopilot.core.database.entity.VehicleProfileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VehicleProfileRepository @Inject constructor(
    private val dao: VehicleProfileDao,
) {
    val activeProfile: Flow<VehicleProfile> = dao.observeActiveVehicle().map { entity ->
        if (entity == null) {
            VehicleProfile()
        } else {
            VehicleProfile(
                id = entity.id,
                manufacturer = entity.manufacturer,
                model = entity.model,
                modelYear = entity.modelYear,
                engineType = entity.engineType,
                transmission = entity.transmission,
                fuelType = entity.fuelType,
                obdAdapterAddress = entity.obdAdapterAddress,
                coolantWarningThresholdC = entity.coolantWarningThresholdC,
            )
        }
    }

    suspend fun saveProfile(profile: VehicleProfile) {
        dao.upsertVehicle(
            VehicleProfileEntity(
                id = profile.id,
                manufacturer = profile.manufacturer,
                model = profile.model,
                modelYear = profile.modelYear,
                engineType = profile.engineType,
                transmission = profile.transmission,
                fuelType = profile.fuelType,
                obdAdapterAddress = profile.obdAdapterAddress,
                coolantWarningThresholdC = profile.coolantWarningThresholdC,
                isActive = true,
            ),
        )
    }
}
