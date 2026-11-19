package ke.co.kopilot.feature.sensors.wearable

import kotlin.math.roundToInt

/**
 * Decoded BLE Heart Rate Measurement (Characteristic UUID `0x2A37`, Service UUID `0x180D`).
 * Follows Bluetooth SIG GATT Heart Rate Service Specification v1.0.
 */
data class DecodedBleHeartRate(
    val bpm: Int,
    val is16BitFormat: Boolean,
    val sensorContactSupported: Boolean,
    val sensorContactDetected: Boolean?,
    val energyExpendedKj: Int?,
    val rrIntervalsMs: List<Int>,
    val isValid: Boolean,
    val invalidReason: String? = null,
)

object BleHeartRateParser {
    const val MIN_VALID_BPM = 25
    const val MAX_VALID_BPM = 240

    /**
     * Decodes a raw byte payload from BLE Heart Rate Measurement characteristic (`0x2A37`).
     *
     * Byte 0 (Flags):
     * - Bit 0: Heart Rate Value Format (0 = UINT8, 1 = UINT16 little-endian)
     * - Bits 1..2: Sensor Contact Status (00/01 = Not supported, 10 = Supported but no contact, 11 = Supported and contact detected)
     * - Bit 3: Energy Expended Status (1 = Present as UINT16 in kJ)
     * - Bit 4: RR-Interval Status (1 = One or more UINT16 values present in units of 1/1024 second)
     */
    fun decodeHeartRateMeasurement(bytes: ByteArray?): DecodedBleHeartRate {
        if (bytes == null || bytes.size < 2) {
            return DecodedBleHeartRate(
                bpm = 0,
                is16BitFormat = false,
                sensorContactSupported = false,
                sensorContactDetected = null,
                energyExpendedKj = null,
                rrIntervalsMs = emptyList(),
                isValid = false,
                invalidReason = "Payload shorter than minimum 2 bytes",
            )
        }

        val flags = bytes[0].toInt() and 0xFF
        val is16Bit = (flags and 0x01) != 0
        val contactBits = (flags shr 1) and 0x03
        val sensorContactSupported = (contactBits == 0x02 || contactBits == 0x03)
        val sensorContactDetected: Boolean? = when (contactBits) {
            0x02 -> false
            0x03 -> true
            else -> null
        }
        val energyExpendedPresent = (flags and 0x08) != 0
        val rrIntervalPresent = (flags and 0x10) != 0

        var offset = 1
        val bpm: Int
        if (is16Bit) {
            if (bytes.size < offset + 2) {
                return DecodedBleHeartRate(
                    bpm = 0,
                    is16BitFormat = true,
                    sensorContactSupported = sensorContactSupported,
                    sensorContactDetected = sensorContactDetected,
                    energyExpendedKj = null,
                    rrIntervalsMs = emptyList(),
                    isValid = false,
                    invalidReason = "Truncated 16-bit heart-rate value",
                )
            }
            bpm = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
        } else {
            bpm = bytes[offset].toInt() and 0xFF
            offset += 1
        }

        var energyExpendedKj: Int? = null
        if (energyExpendedPresent) {
            if (bytes.size >= offset + 2) {
                energyExpendedKj = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
                offset += 2
            }
        }

        val rrIntervalsMs = mutableListOf<Int>()
        if (rrIntervalPresent) {
            while (bytes.size >= offset + 2) {
                val rawRr = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
                offset += 2
                if (rawRr > 0) {
                    val rrMs = ((rawRr * 1000.0) / 1024.0).roundToInt()
                    if (rrMs in 200..2500) {
                        rrIntervalsMs.add(rrMs)
                    }
                }
            }
        }

        val contactValid = !sensorContactSupported || (sensorContactDetected == true)
        val rangeValid = bpm in MIN_VALID_BPM..MAX_VALID_BPM
        val isValid = contactValid && rangeValid
        val invalidReason = when {
            !contactValid -> "Watch sensor reports no skin contact"
            !rangeValid -> "Heart rate $bpm BPM outside valid physiological range ($MIN_VALID_BPM..$MAX_VALID_BPM BPM)"
            else -> null
        }

        return DecodedBleHeartRate(
            bpm = bpm,
            is16BitFormat = is16Bit,
            sensorContactSupported = sensorContactSupported,
            sensorContactDetected = sensorContactDetected,
            energyExpendedKj = energyExpendedKj,
            rrIntervalsMs = rrIntervalsMs,
            isValid = isValid,
            invalidReason = invalidReason,
        )
    }

    /**
     * Decodes BLE Body Sensor Location characteristic (`0x2A38`).
     */
    fun decodeBodySensorLocation(bytes: ByteArray?): String {
        if (bytes == null || bytes.isEmpty()) return "Unknown"
        return when (bytes[0].toInt() and 0xFF) {
            0 -> "Other"
            1 -> "Chest"
            2 -> "Wrist"
            3 -> "Finger"
            4 -> "Hand"
            5 -> "Ear Lobe"
            6 -> "Foot"
            else -> "Unspecified"
        }
    }
}
