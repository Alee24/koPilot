package ke.co.kopilot.feature.diagnostics

object ObdPidParser {

    fun parseRpm(response: String): Int? {
        val bytes = extractPayloadBytes(response, expectedMode = "41", expectedPid = "0C") ?: return null
        if (bytes.size < 2) return null
        return ((bytes[0] * 256) + bytes[1]) / 4
    }

    fun parseCoolantTempC(response: String): Int? {
        val bytes = extractPayloadBytes(response, expectedMode = "41", expectedPid = "05") ?: return null
        if (bytes.isEmpty()) return null
        return bytes[0] - 40
    }

    fun parseVehicleSpeedKmh(response: String): Int? {
        val bytes = extractPayloadBytes(response, expectedMode = "41", expectedPid = "0D") ?: return null
        if (bytes.isEmpty()) return null
        return bytes[0]
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

    private fun extractPayloadBytes(response: String, expectedMode: String, expectedPid: String): List<Int>? {
        val tokens = response.trim()
            .replace(">", "")
            .split(Regex("\\s+"))
            .filter { it.length == 2 }
            .map { it.uppercase() }
        val start = tokens.indexOf(expectedMode)
        if (start < 0 || start + 1 >= tokens.size || tokens[start + 1] != expectedPid) {
            return null
        }
        return tokens.drop(start + 2).mapNotNull { it.toIntOrNull(16) }
    }
}
