package ke.co.kopilot.feature.diagnostics

object ObdPidParser {

    fun isAdapterError(response: String): Boolean {
        val upper = response.uppercase()
        return upper.contains("NO DATA") ||
            upper.contains("STOPPED") ||
            upper.contains("BUS INIT") ||
            upper.contains("UNABLE TO CONNECT") ||
            upper.contains("CAN ERROR") ||
            upper.contains("ERROR") ||
            upper.trim() == "?"
    }

    /**
     * Decodes Mode 01 PID support bitmaps (0100, 0120, 0140) into supported PID hex strings
     * such as "0104", "0105", "010C", "010D".
     */
    fun parseSupportedPidsBitmap(response: String, basePid: Int): Set<String> {
        val pidHex = basePid.toString(16).uppercase().padStart(2, '0')
        val bytes = extractPayloadBytes(response, expectedMode = "41", expectedPid = pidHex) ?: return emptySet()
        if (bytes.size < 4) return emptySet()
        val supported = mutableSetOf<String>()
        for (byteIndex in 0 until 4) {
            val b = bytes[byteIndex]
            for (bitIndex in 0 until 8) {
                val mask = 1 shl (7 - bitIndex)
                if ((b and mask) != 0) {
                    val pidNum = basePid + (byteIndex * 8) + bitIndex + 1
                    supported.add("01" + pidNum.toString(16).uppercase().padStart(2, '0'))
                }
            }
        }
        return supported
    }

    fun parseAdapterVoltage(response: String): Float? {
        val match = Regex("(\\d{1,2}(?:\\.\\d{1,2})?)\\s*V?").find(response.uppercase()) ?: return null
        return match.groupValues[1].toFloatOrNull()?.takeIf { it in 5f..32f }
    }

    fun parseFuelSystemStatus(response: String): String? {
        val bytes = extractPayloadBytes(response, "41", "03") ?: return null
        if (bytes.isEmpty()) return null
        return when (bytes[0]) {
            1 -> "Open loop (insufficient temp)"
            2 -> "Closed loop (O2 feedback)"
            4 -> "Open loop (engine load/decel)"
            8 -> "Open loop (system fault)"
            16 -> "Closed loop (O2 fault)"
            else -> "Status 0x${bytes[0].toString(16).uppercase()}"
        }
    }

    fun parseEngineLoadPct(response: String): Float? {
        val bytes = extractPayloadBytes(response, "41", "04") ?: return null
        return bytes.firstOrNull()?.let { (it * 100f) / 255f }
    }

    fun parseCoolantTempC(response: String): Int? {
        val bytes = extractPayloadBytes(response, "41", "05") ?: return null
        return bytes.firstOrNull()?.let { it - 40 }
    }

    fun parseShortTermFuelTrimPct(response: String): Float? {
        val bytes = extractPayloadBytes(response, "41", "06") ?: return null
        return bytes.firstOrNull()?.let { (it - 128) * 100f / 128f }
    }

    fun parseLongTermFuelTrimPct(response: String): Float? {
        val bytes = extractPayloadBytes(response, "41", "07") ?: return null
        return bytes.firstOrNull()?.let { (it - 128) * 100f / 128f }
    }

    fun parseMapKpa(response: String): Int? {
        val bytes = extractPayloadBytes(response, "41", "0B") ?: return null
        return bytes.firstOrNull()
    }

    fun parseRpm(response: String): Int? {
        val bytes = extractPayloadBytes(response, "41", "0C") ?: return null
        if (bytes.size < 2) return null
        return ((bytes[0] * 256) + bytes[1]) / 4
    }

    fun parseVehicleSpeedKmh(response: String): Int? {
        val bytes = extractPayloadBytes(response, "41", "0D") ?: return null
        return bytes.firstOrNull()
    }

    fun parseTimingAdvanceDeg(response: String): Float? {
        val bytes = extractPayloadBytes(response, "41", "0E") ?: return null
        return bytes.firstOrNull()?.let { (it / 2f) - 64f }
    }

    fun parseIntakeAirTempC(response: String): Int? {
        val bytes = extractPayloadBytes(response, "41", "0F") ?: return null
        return bytes.firstOrNull()?.let { it - 40 }
    }

    fun parseMafGramsPerSec(response: String): Float? {
        val bytes = extractPayloadBytes(response, "41", "10") ?: return null
        if (bytes.size < 2) return null
        return ((bytes[0] * 256) + bytes[1]) / 100f
    }

    fun parseThrottlePosPct(response: String): Float? {
        val bytes = extractPayloadBytes(response, "41", "11") ?: return null
        return bytes.firstOrNull()?.let { (it * 100f) / 255f }
    }

    fun parseO2Voltage(response: String): Float? {
        val bytes = extractPayloadBytes(response, "41", "14") ?: return null
        return bytes.firstOrNull()?.let { it / 200f }
    }

    fun parseRuntimeSec(response: String): Int? {
        val bytes = extractPayloadBytes(response, "41", "1F") ?: return null
        if (bytes.size < 2) return null
        return (bytes[0] * 256) + bytes[1]
    }

    fun parseFuelPressureKpa(response: String): Int? {
        val bytes = extractPayloadBytes(response, "41", "23") ?: return null
        if (bytes.size < 2) return null
        return ((bytes[0] * 256) + bytes[1]) * 10
    }

    fun parseFuelLevelPct(response: String): Float? {
        val bytes = extractPayloadBytes(response, "41", "2F") ?: return null
        return bytes.firstOrNull()?.let { (it * 100f) / 255f }
    }

    fun parseControlModuleVoltage(response: String): Float? {
        val bytes = extractPayloadBytes(response, "41", "42") ?: return null
        if (bytes.size < 2) return null
        return ((bytes[0] * 256) + bytes[1]) / 1000f
    }

    fun parseAmbientTempC(response: String): Int? {
        val bytes = extractPayloadBytes(response, "41", "46") ?: return null
        return bytes.firstOrNull()?.let { it - 40 }
    }

    fun parseDtcCodes(response: String): List<String> {
        val clean = response.replace("\\s+".toRegex(), "").uppercase()
        val idx = clean.indexOf("43")
        if (idx < 0) return emptyList()
        val hex = clean.substring(idx + 2)
        val codes = mutableListOf<String>()
        var i = 0
        while (i + 3 < hex.length) {
            val b1 = hex.substring(i, i + 2).toIntOrNull(16) ?: break
            val b2 = hex.substring(i + 2, i + 4).toIntOrNull(16) ?: break
            i += 4
            if (b1 == 0 && b2 == 0) continue
            val prefix = when ((b1 shr 6) and 0x03) {
                0 -> 'P'
                1 -> 'C'
                2 -> 'B'
                else -> 'U'
            }
            val d1 = (b1 shr 4) and 0x03
            val d2 = b1 and 0x0F
            val d3 = (b2 shr 4) and 0x0F
            val d4 = b2 and 0x0F
            codes.add("$prefix$d1${d2.toString(16).uppercase()}${d3.toString(16).uppercase()}${d4.toString(16).uppercase()}")
        }
        return codes
    }

    fun filterTransmissionDtcs(codes: List<String>): List<String> {
        return codes.filter { it.startsWith("P07") || it.startsWith("P08") || it.startsWith("P09") }
    }

    private fun extractPayloadBytes(response: String, expectedMode: String, expectedPid: String): List<Int>? {
        if (isAdapterError(response)) return null
        val hexOnly = response.uppercase()
            .replace(">", "")
            .replace(Regex("[^0-9A-F]"), "")
        val header = "$expectedMode$expectedPid"
        val start = hexOnly.indexOf(header)
        if (start < 0) return null
        val payloadHex = hexOnly.substring(start + header.length)
        val bytes = mutableListOf<Int>()
        var i = 0
        while (i + 1 < payloadHex.length) {
            val byteVal = payloadHex.substring(i, i + 2).toIntOrNull(16) ?: break
            bytes.add(byteVal)
            i += 2
        }
        return bytes
    }
}
