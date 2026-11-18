package ke.co.kopilot.feature.diagnostics

/**
 * Strict read-only OBD-II command firewall.
 * Permits only reviewed ELM327 adapter initialization/query AT commands and
 * read-only SAE J1979 diagnostic modes (01, 02, 03, 07, 09, 0A).
 *
 * Explicitly blocks Mode 04 (clear DTCs), ECU resets, security access (27),
 * memory/data writes, routine control, flashing, and raw CAN frame injection.
 */
object ObdReadOnlyFirewall {

    private val ALLOWED_AT_COMMANDS = setOf(
        "ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATI", "ATRV",
        "ATDP", "ATDPN",
        "ATSP0", "ATSP3", "ATSP4", "ATSP5", "ATSP6", "ATSP7", "ATSP8", "ATSP9",
    )

    private val MODE_01_REGEX = Regex("^01[0-9A-F]{2}$")
    private val MODE_02_REGEX = Regex("^02[0-9A-F]{4}$")
    private val MODE_09_REGEX = Regex("^09[0-9A-F]{2}$")

    data class FirewallDecision(
        val allowed: Boolean,
        val normalizedCommand: String,
        val reason: String,
    )

    fun evaluate(rawCommand: String): FirewallDecision {
        val norm = rawCommand.trim().uppercase().replace("\\s+".toRegex(), "")
        if (norm.isEmpty()) {
            return FirewallDecision(true, "", "Adapter wake-up newline")
        }
        if (norm in ALLOWED_AT_COMMANDS) {
            return FirewallDecision(true, norm, "Allowlisted ELM327 read/init AT command")
        }
        if (norm.startsWith("AT")) {
            return FirewallDecision(false, norm, "Unreviewed AT command blocked by read-only firewall")
        }
        if (norm == "03" || norm == "07" || norm == "0A") {
            return FirewallDecision(true, norm, "Read-only DTC scan (Mode $norm)")
        }
        if (norm == "04") {
            return FirewallDecision(false, norm, "Mode 04 fault-code clearing is prohibited (read-only ECU policy)")
        }
        if (MODE_01_REGEX.matches(norm) || MODE_02_REGEX.matches(norm) || MODE_09_REGEX.matches(norm)) {
            return FirewallDecision(true, norm, "Read-only SAE J1979 Mode ${norm.take(2)} request")
        }
        return FirewallDecision(false, norm, "Unsupported or write/control command '$norm' blocked by read-only firewall")
    }

    fun isAllowed(rawCommand: String): Boolean = evaluate(rawCommand).allowed
}
