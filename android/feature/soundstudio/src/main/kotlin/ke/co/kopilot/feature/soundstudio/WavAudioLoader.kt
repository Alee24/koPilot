package ke.co.kopilot.feature.soundstudio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

data class ValidatedWavAudio(
    val fileName: String,
    val sampleRateHz: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val durationSec: Float,
    val monoPcm44100: FloatArray,
    val rawBytes: ByteArray,
)

sealed class WavValidationResult {
    data class Valid(val audio: ValidatedWavAudio) : WavValidationResult()
    data class Invalid(val reason: String) : WavValidationResult()
}

/**
 * Pure Kotlin RIFF/WAV parser and validator.
 * Rejects unsupported encodings, corrupt headers, oversized files (>15 MB),
 * and clips shorter than 0.3s or longer than 30s.
 */
object WavAudioLoader {
    const val TARGET_SAMPLE_RATE = 44_100
    const val MAX_FILE_BYTES = 15 * 1024 * 1024

    fun importFromUri(context: Context, uri: Uri): WavValidationResult {
        val fileName = resolveFileName(context, uri)
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val data = stream.readBytes()
                if (data.size > MAX_FILE_BYTES) {
                    return WavValidationResult.Invalid("File exceeds 15 MB limit (${data.size / (1024 * 1024)} MB).")
                }
                data
            } ?: return WavValidationResult.Invalid("Unable to open selected file.")
        }.getOrElse {
            return WavValidationResult.Invalid("Failed to read file: ${it.localizedMessage ?: "I/O error"}")
        }
        return parseAndValidateWav(fileName, bytes)
    }

    fun parseAndValidateWav(fileName: String, bytes: ByteArray): WavValidationResult {
        if (bytes.size < 44) return WavValidationResult.Invalid("File too small to be a valid WAV file.")
        if (bytes.size > MAX_FILE_BYTES) return WavValidationResult.Invalid("File exceeds 15 MB maximum.")

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val riff = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
        buf.int // chunk size
        val wave = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
        if (riff != "RIFF" || wave != "WAVE") {
            return WavValidationResult.Invalid("Not a valid RIFF/WAVE audio file.")
        }

        var audioFormat = 0
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataSize = 0

        while (buf.remaining() >= 8) {
            val chunkId = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
            val chunkSize = buf.int
            if (chunkSize < 0 || chunkSize > buf.remaining()) break

            when (chunkId) {
                "fmt " -> {
                    if (chunkSize < 16) return WavValidationResult.Invalid("Malformed WAV fmt chunk.")
                    audioFormat = buf.short.toInt() and 0xFFFF
                    channels = buf.short.toInt() and 0xFFFF
                    sampleRate = buf.int
                    buf.int // byteRate
                    buf.short // blockAlign
                    bitsPerSample = buf.short.toInt() and 0xFFFF
                    val extra = chunkSize - 16
                    if (extra > 0) buf.position(buf.position() + extra)
                }
                "data" -> {
                    dataOffset = buf.position()
                    dataSize = chunkSize
                    break
                }
                else -> {
                    buf.position(buf.position() + chunkSize)
                }
            }
        }

        if (dataOffset < 0 || dataSize <= 0) {
            return WavValidationResult.Invalid("WAV file contains no audio data chunk.")
        }
        if (audioFormat != 1 && audioFormat != 3) {
            return WavValidationResult.Invalid("Only uncompressed PCM (1) or IEEE Float (3) WAV is supported.")
        }
        if (channels !in 1..2) {
            return WavValidationResult.Invalid("Only mono or stereo WAV files are supported (found $channels channels).")
        }
        if (sampleRate !in 8000..96000) {
            return WavValidationResult.Invalid("Sample rate $sampleRate Hz outside supported 8kHz–96kHz range.")
        }
        if (bitsPerSample !in listOf(16, 24, 32)) {
            return WavValidationResult.Invalid("Unsupported bit depth $bitsPerSample-bit (use 16, 24, or 32-bit).")
        }

        val bytesPerSample = bitsPerSample / 8
        val frameSize = bytesPerSample * channels
        val totalFrames = dataSize / frameSize
        val durationSec = totalFrames.toFloat() / sampleRate.toFloat()
        if (durationSec < 0.3f || durationSec > 30.0f) {
            return WavValidationResult.Invalid("Clip duration ${"%.2f".format(durationSec)}s must be between 0.3s and 30.0s.")
        }

        val monoNative = FloatArray(totalFrames)
        val dataBuf = ByteBuffer.wrap(bytes, dataOffset, totalFrames * frameSize).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until totalFrames) {
            var sum = 0f
            for (ch in 0 until channels) {
                val sample = when {
                    audioFormat == 3 && bitsPerSample == 32 -> dataBuf.float.coerceIn(-1f, 1f)
                    bitsPerSample == 16 -> dataBuf.short.toFloat() / 32768f
                    bitsPerSample == 24 -> {
                        val b0 = dataBuf.get().toInt() and 0xFF
                        val b1 = dataBuf.get().toInt() and 0xFF
                        val b2 = dataBuf.get().toInt() // signed high byte
                        val v = (b2 shl 16) or (b1 shl 8) or b0
                        v.toFloat() / 8388608f
                    }
                    else -> dataBuf.int.toFloat() / 2147483648f
                }
                sum += sample
            }
            monoNative[i] = (sum / channels).coerceIn(-1f, 1f)
        }

        val mono44k = if (sampleRate == TARGET_SAMPLE_RATE) {
            monoNative
        } else {
            resampleLinear(monoNative, sampleRate, TARGET_SAMPLE_RATE)
        }

        return WavValidationResult.Valid(
            ValidatedWavAudio(
                fileName = fileName,
                sampleRateHz = sampleRate,
                channels = channels,
                bitsPerSample = bitsPerSample,
                durationSec = durationSec,
                monoPcm44100 = mono44k,
                rawBytes = bytes,
            ),
        )
    }

    private fun resampleLinear(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        val outLen = ((input.size.toLong() * dstRate) / srcRate).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        for (i in 0 until outLen) {
            val pos = i * ratio
            val idx0 = pos.toInt().coerceIn(0, input.lastIndex)
            val idx1 = (idx0 + 1).coerceIn(0, input.lastIndex)
            val frac = (pos - idx0).toFloat()
            out[i] = input[idx0] * (1f - frac) + input[idx1] * frac
        }
        return out
    }

    private fun resolveFileName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return c.getString(idx)
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "engine_sample.wav"
    }
}
