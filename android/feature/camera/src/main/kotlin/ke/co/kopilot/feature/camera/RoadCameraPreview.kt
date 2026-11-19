package ke.co.kopilot.feature.camera

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

@Composable
fun RoadCameraPreview(
    permissionGranted: Boolean,
    isDrivingSessionActive: Boolean,
    cameraSensorEnabled: Boolean = true,
    analysisIntervalMs: Long,
    zoomRatio: Float = 1.3f,
    performanceController: CameraPerformanceController,
    onFrameListener: RoadFrameListener,
    onStartDrivingClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    if (!permissionGranted) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(16.dp),
            ) {
                Text(
                    text = "Camera Unavailable",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Grant camera permission to enable road sign observation.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    if (!isDrivingSessionActive || !cameraSensorEnabled) {
        DisposableEffect(Unit) {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            performanceController.setCameraActive(
                false,
                if (!cameraSensorEnabled) "Camera sensor disabled in Settings" else "Standby — Press Start Driving",
            )
            onDispose {}
        }
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(20.dp),
            ) {
                Text(
                    text = if (!cameraSensorEnabled) "Camera Sensor Off" else "Sensors & Camera Standby",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (!cameraSensorEnabled) {
                        "Enable Camera in Settings > Sensors & Hardware to scan road signs."
                    } else {
                        "Tap Start Driving to activate the camera and enabled sensors."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!isDrivingSessionActive && cameraSensorEnabled && onStartDrivingClick != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = onStartDrivingClick) {
                        Text("Start Driving & Camera")
                    }
                }
            }
        }
        return
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
    }

    val cameraRef = remember { androidx.compose.runtime.mutableStateOf<androidx.camera.core.Camera?>(null) }

    DisposableEffect(zoomRatio, cameraRef.value) {
        cameraRef.value?.let { cam ->
            val maxZoom = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 2.0f
            val minZoom = cam.cameraInfo.zoomState.value?.minZoomRatio ?: 1.0f
            cam.cameraControl.setZoomRatio(zoomRatio.coerceIn(minZoom, maxZoom))
        }
        onDispose {}
    }

    DisposableEffect(lifecycleOwner, analysisIntervalMs) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val mainExecutor = ContextCompat.getMainExecutor(context)

        cameraProviderFuture.addListener({
            runCatching {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    val now = System.currentTimeMillis()
                    performanceController.recordResolution(imageProxy.width, imageProxy.height)
                    if (performanceController.shouldAnalyzeFrame(now, analysisIntervalMs)) {
                        onFrameListener.onFrame(
                            imageProxy = imageProxy,
                            rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                            timestampMillis = now,
                        )
                    } else {
                        imageProxy.close()
                    }
                }

                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                if (cameraProvider.hasCamera(selector)) {
                    val cam = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imageAnalysis)
                    cameraRef.value = cam
                    val maxZoom = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 2.0f
                    val minZoom = cam.cameraInfo.zoomState.value?.minZoomRatio ?: 1.0f
                    cam.cameraControl.setZoomRatio(zoomRatio.coerceIn(minZoom, maxZoom))
                    // Continuous road-corridor autofocus & auto-exposure metering (locked without 3s expiry)
                    runCatching {
                        val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
                        val roadPoint = factory.createPoint(0.5f, 0.42f)
                        val action = FocusMeteringAction.Builder(
                            roadPoint,
                            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
                        ).disableAutoCancel().build()
                        cam.cameraControl.startFocusAndMetering(action)
                    }
                    performanceController.setCameraActive(true, "Rear camera active (HD)")
                } else {
                    performanceController.recordError("Rear camera not found on this device")
                    performanceController.setCameraActive(false, "Rear camera not found on this device")
                }
            }.onFailure { err ->
                val msg = "Camera unavailable: ${err.localizedMessage ?: "hardware error"}"
                performanceController.recordError(msg)
                performanceController.setCameraActive(false, msg)
            }
        }, mainExecutor)

        onDispose {
            runCatching {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            }
            performanceController.setCameraActive(false, "Camera released")
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize(),
    )
}
