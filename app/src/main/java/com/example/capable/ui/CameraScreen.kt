package com.example.capable.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.capable.audio.TTSManager
import com.example.capable.camera.FrameAnalyzer
import com.example.capable.detection.DepthEstimatorHelper
import com.example.capable.detection.ObjectDetectorHelper
import java.util.concurrent.Executors

@Composable
fun CameraScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var detectedHazards by remember { mutableStateOf<List<HazardDetectionProcessor.DetectedHazard>>(emptyList()) }
    var isDetectionActive by remember { mutableStateOf(true) }
    var fps by remember { mutableStateOf(0f) }
    var statusMessage by remember { mutableStateOf("Initializing...") }
    var isInitialized by remember { mutableStateOf(false) }

    // Initialize TTS - this is safe and won't crash
    val ttsManager = remember {
        TTSManager(context) {
            statusMessage = "Ready - Point camera forward"
        }
    }

    // Lazy initialization of detectors to avoid crash during composition
    var objectDetector by remember { mutableStateOf<ObjectDetectorHelper?>(null) }
    var depthEstimator by remember { mutableStateOf<DepthEstimatorHelper?>(null) }
    var hazardProcessor by remember { mutableStateOf<HazardDetectionProcessor?>(null) }

    // Initialize ML models in LaunchedEffect (off the main composition)
    LaunchedEffect(Unit) {
        android.util.Log.d("CameraScreen", "Initializing ML models...")
        try {
            objectDetector = ObjectDetectorHelper(context)
            statusMessage = "Object detector ready"
            android.util.Log.i("CameraScreen", "ObjectDetector initialized successfully")
        } catch (e: Exception) {
            statusMessage = "Detection unavailable: ${e.message?.take(50)}"
            android.util.Log.e("CameraScreen", "ObjectDetector init failed", e)
        }

        try {
            depthEstimator = DepthEstimatorHelper(context)
            android.util.Log.i("CameraScreen", "DepthEstimator initialized successfully")
        } catch (e: Exception) {
            // Depth estimation is optional, ignore errors
            android.util.Log.w("CameraScreen", "DepthEstimator init failed (optional)", e)
        }

        isInitialized = true
        android.util.Log.d("CameraScreen", "ML models initialization complete")
    }

    // FPS tracking state
    var frameCount by remember { mutableIntStateOf(0) }
    var lastFpsUpdateTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Create hazard processor when detector is ready
    LaunchedEffect(objectDetector, isInitialized) {
        if (objectDetector != null && isInitialized) {
            android.util.Log.d("CameraScreen", "Creating HazardDetectionProcessor...")
            hazardProcessor = HazardDetectionProcessor(
                objectDetector = objectDetector!!,
                depthEstimator = depthEstimator,
                ttsManager = ttsManager,
                onDetectionUpdate = { hazards ->
                    detectedHazards = hazards
                    
                    // Calculate FPS based on frame count over time
                    frameCount++
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastFpsUpdateTime
                    if (elapsed >= 1000) {
                        fps = frameCount * 1000f / elapsed
                        frameCount = 0
                        lastFpsUpdateTime = now
                        android.util.Log.d("CameraScreen", "FPS: $fps, Hazards: ${hazards.size}")
                    }
                }
            )
            statusMessage = "Ready - Point camera forward"
            android.util.Log.i("CameraScreen", "HazardDetectionProcessor ready")
            ttsManager.speak("Capable started. Point camera forward to detect obstacles.")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            objectDetector?.close()
            depthEstimator?.close()
            ttsManager.shutdown()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Camera Preview
        CameraPreview(
            hazardProcessor = hazardProcessor,
            isActive = isDetectionActive
        )

        // Detection Overlay
        DetectionOverlay(
            hazards = detectedHazards,
            modifier = Modifier.fillMaxSize()
        )

        // Status Bar at top
        StatusBar(
            fps = fps,
            hazardCount = detectedHazards.size,
            statusMessage = statusMessage,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
        )

        // Control buttons at bottom
        ControlPanel(
            isActive = isDetectionActive,
            onToggleDetection = { isDetectionActive = !isDetectionActive },
            onSpeak = {
                if (detectedHazards.isEmpty()) {
                    ttsManager.speak("Path appears clear", TTSManager.Priority.NORMAL)
                } else {
                    val summary = "${detectedHazards.size} objects detected"
                    ttsManager.speak(summary, TTSManager.Priority.NORMAL)
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        )
    }
}

@Composable
private fun CameraPreview(
    hazardProcessor: HazardDetectionProcessor?,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            }
        },
        modifier = modifier.fillMaxSize(),
        update = { previewView ->
            android.util.Log.d("CameraScreen", "CameraPreview update: isActive=$isActive, hazardProcessor=${hazardProcessor != null}")
            if (!isActive || hazardProcessor == null) return@AndroidView

            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

            cameraProviderFuture.addListener({
                android.util.Log.d("CameraScreen", "Camera provider ready, binding camera...")
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also {
                        it.setAnalyzer(
                            cameraExecutor,
                            FrameAnalyzer(hazardProcessor, targetFps = 10)
                        )
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalyzer
                    )
                    android.util.Log.i("CameraScreen", "Camera bound successfully")
                } catch (e: Exception) {
                    android.util.Log.e("CameraScreen", "Camera binding failed", e)
                    e.printStackTrace()
                }
            }, ContextCompat.getMainExecutor(context))
        }
    )
}

@Composable
private fun DetectionOverlay(
    hazards: List<HazardDetectionProcessor.DetectedHazard>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        for (hazard in hazards) {
            val left = hazard.boundingBox.left * size.width
            val top = hazard.boundingBox.top * size.height
            val right = hazard.boundingBox.right * size.width
            val bottom = hazard.boundingBox.bottom * size.height

            val color = when (hazard.distance) {
                TTSManager.Distance.VERY_CLOSE -> Color.Red
                TTSManager.Distance.CLOSE -> Color(0xFFFF9800) // Orange
                TTSManager.Distance.MEDIUM -> Color.Yellow
                TTSManager.Distance.FAR -> Color.Green
            }

            // Draw bounding box
            drawRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = 4f)
            )
        }
    }
}

@Composable
private fun StatusBar(
    fps: Float,
    hazardCount: Int,
    statusMessage: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.7f)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = statusMessage,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "FPS: ${fps.toInt()}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Objects: $hazardCount",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun ControlPanel(
    isActive: Boolean,
    onToggleDetection: () -> Unit,
    onSpeak: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.7f)
    ) {
        Row(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = onToggleDetection,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isActive) Color.Red else Color.Green
                ),
                modifier = Modifier.size(80.dp)
            ) {
                Text(if (isActive) "Stop" else "Start")
            }

            Button(
                onClick = onSpeak,
                modifier = Modifier.size(80.dp)
            ) {
                Text("Status")
            }
        }
    }
}
