package ke.co.kopilot.feature.diagnostics

/**
 * Versioned SAE J1979 standard Mode 01 PID catalogue.
 * Only standard, verified PIDs are listed; manufacturer-specific commands are never invented.
 */
object ObdPidCatalog {
    const val CATALOG_VERSION = "1.0.0-j1979"

    data class PidDefinition(
        val pidHex: String,
        val name: String,
        val unit: String,
        val minValid: Float,
        val maxValid: Float,
        val formulaNote: String,
    )

    val definitions: List<PidDefinition> = listOf(
        PidDefinition("0103", "Fuel System Status", "", 0f, 255f, "Bitfield"),
        PidDefinition("0104", "Calculated Engine Load", "%", 0f, 100f, "A * 100 / 255"),
        PidDefinition("0105", "Engine Coolant Temperature", "°C", -40f, 215f, "A - 40"),
        PidDefinition("0106", "Short-Term Fuel Trim (Bank 1)", "%", -100f, 99.2f, "(A - 128) * 100 / 128"),
        PidDefinition("0107", "Long-Term Fuel Trim (Bank 1)", "%", -100f, 99.2f, "(A - 128) * 100 / 128"),
        PidDefinition("0108", "Short-Term Fuel Trim (Bank 2)", "%", -100f, 99.2f, "(A - 128) * 100 / 128"),
        PidDefinition("0109", "Long-Term Fuel Trim (Bank 2)", "%", -100f, 99.2f, "(A - 128) * 100 / 128"),
        PidDefinition("010A", "Fuel Pressure (Gauge)", "kPa", 0f, 765f, "A * 3"),
        PidDefinition("010B", "Intake Manifold Pressure", "kPa", 0f, 255f, "A"),
        PidDefinition("010C", "Engine RPM", "rpm", 0f, 16383.75f, "((256 * A) + B) / 4"),
        PidDefinition("010D", "Vehicle Speed", "km/h", 0f, 255f, "A"),
        PidDefinition("010E", "Ignition Timing Advance", "°", -64f, 63.5f, "(A / 2) - 64"),
        PidDefinition("010F", "Intake Air Temperature", "°C", -40f, 215f, "A - 40"),
        PidDefinition("0110", "Mass Airflow Rate (MAF)", "g/s", 0f, 655.35f, "((256 * A) + B) / 100"),
        PidDefinition("0111", "Throttle Position", "%", 0f, 100f, "A * 100 / 255"),
        PidDefinition("0114", "O2 Sensor Voltage (B1S1)", "V", 0f, 1.275f, "A / 200"),
        PidDefinition("011F", "Engine Runtime Since Start", "s", 0f, 65535f, "(256 * A) + B"),
        PidDefinition("0123", "Fuel Rail Gauge Pressure", "kPa", 0f, 655350f, "((256 * A) + B) * 10"),
        PidDefinition("012F", "Fuel Tank Level", "%", 0f, 100f, "A * 100 / 255"),
        PidDefinition("0133", "Barometric Pressure", "kPa", 0f, 255f, "A"),
        PidDefinition("0142", "Control Module Voltage", "V", 0f, 65.535f, "((256 * A) + B) / 1000"),
        PidDefinition("0143", "Absolute Engine Load", "%", 0f, 25700f, "((256 * A) + B) * 100 / 255"),
        PidDefinition("0145", "Relative Throttle Position", "%", 0f, 100f, "A * 100 / 255"),
        PidDefinition("0146", "Ambient Air Temperature", "°C", -40f, 215f, "A - 40"),
        PidDefinition("015C", "Engine Oil Temperature", "°C", -40f, 210f, "A - 40"),
        PidDefinition("015E", "Engine Fuel Rate", "L/h", 0f, 3212.75f, "((256 * A) + B) / 20"),
    )

    val byPidHex: Map<String, PidDefinition> = definitions.associateBy { it.pidHex }

    fun isWithinValidRange(pidHex: String, value: Float): Boolean {
        val def = byPidHex[pidHex] ?: return true
        return value in def.minValid..def.maxValid
    }
}
