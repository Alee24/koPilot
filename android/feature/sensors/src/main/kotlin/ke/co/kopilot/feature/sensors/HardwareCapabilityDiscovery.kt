package ke.co.kopilot.feature.sensors

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.nfc.NfcAdapter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HardwareCapabilityDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun discoverCameras(): List<CameraCapabilitySummary> {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return emptyList()
        return runCatching {
            cm.cameraIdList.map { id ->
                val chars = cm.getCameraCharacteristics(id)
                val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "Rear"
                    CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
                    else -> "Unknown"
                }
                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.toList()
                    .orEmpty()
                val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
                val eisModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()

                CameraCapabilitySummary(
                    cameraId = id,
                    lensFacing = facing,
                    focalLengthsMm = focalLengths,
                    autofocusSupported = afModes.any { it != CameraCharacteristics.CONTROL_AF_MODE_OFF },
                    opticalStabilizationSupported = oisModes.any {
                        it != CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_OFF
                    },
                    videoStabilizationSupported = eisModes.any {
                        it != CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                    },
                )
            }
        }.getOrDefault(emptyList())
    }

    fun discoverAudio(): AudioCapabilitySummary {
        val pm = context.packageManager
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val inputs = am?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.map { "${deviceTypeLabel(it.type)} (${it.productName})" }
            ?.distinct()
            .orEmpty()
        val outputs = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.map { "${deviceTypeLabel(it.type)} (${it.productName})" }
            ?.distinct()
            .orEmpty()
        val btConnected = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        } == true

        return AudioCapabilitySummary(
            microphoneFeaturePresent = pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE),
            inputDevices = inputs,
            outputDevices = outputs,
            bluetoothAudioConnected = btConnected,
        )
    }

    fun discoverConnectivity(): ConnectivityCapabilities {
        val pm = context.packageManager
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btAdapter = btManager?.adapter
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeCaps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        val nfcAdapter = NfcAdapter.getDefaultAdapter(context)

        return ConnectivityCapabilities(
            bluetoothClassicSupported = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH),
            bluetoothEnabled = btAdapter?.isEnabled == true,
            bleSupported = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
            wifiSupported = pm.hasSystemFeature(PackageManager.FEATURE_WIFI),
            wifiConnected = activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            cellularAvailable = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) ||
                activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
            usbHostSupported = pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST),
            usbAccessorySupported = pm.hasSystemFeature(PackageManager.FEATURE_USB_ACCESSORY),
            attachedUsbDeviceCount = runCatching { usbManager?.deviceList?.size ?: 0 }.getOrDefault(0),
            nfcSupported = pm.hasSystemFeature(PackageManager.FEATURE_NFC) && nfcAdapter != null,
            nfcEnabled = nfcAdapter?.isEnabled == true,
        )
    }

    private fun deviceTypeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Mic"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE Headset"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio"
        else -> "Audio Device ($type)"
    }
}
