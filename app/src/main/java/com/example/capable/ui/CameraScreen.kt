package com.example.capable.ui

import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.capable.audio.TTSManager
import com.example.capable.camera.FrameAnalyzer
import com.example.capable.detection.DepthAnythingHelper
import com.example.capable.detection.SegFormerHelper
import com.example.capable.detection.YoloV8Detector
import com.example.capable.ocr.OCRManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

@Composable
fun CameraScreen(
    modifier: Modifier = Modifier,
    onNavigateToContacts: () -> Unit = {},
    onTTSReady: (TTSManager) -> Unit = {}
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
    
    // Notify parent about TTS readiness for SOS
    LaunchedEffect(ttsManager) {
        onTTSReady(ttsManager)
    }

    // Lazy initialization of ML models
    var yoloDetector by remember { mutableStateOf<YoloV8Detector?>(null) }
    var depthHelper by remember { mutableStateOf<DepthAnythingHelper?>(null) }
    var segHelper by remember { mutableStateOf<SegFormerHelper?>(null) }
    var hazardProcessor by remember { mutableStateOf<HazardDetectionProcessor?>(null) }
    
    // OCR state
    var isScanning by remember { mutableStateOf(false) }
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val ocrManager = remember { OCRManager() }
    val scope = rememberCoroutineScope()

    // Initialize ML models in LaunchedEffect (off the main composition)
    LaunchedEffect(Unit) {
        android.util.Log.d("CameraScreen", "Initializing ML models (ONNX)...")
        try {
            yoloDetector = YoloV8Detector(context)
            statusMessage = "YOLOv8 ready"
            android.util.Log.i("CameraScreen", "YOLOv8 Nano initialized")
        } catch (e: Exception) {
            statusMessage = "Detection unavailable: ${e.message?.take(50)}"
            android.util.Log.e("CameraScreen", "YOLOv8 init failed", e)
        }

        try {
            depthHelper = DepthAnythingHelper(context)
            android.util.Log.i("CameraScreen", "Depth Anything V2 initialized")
        } catch (e: Exception) {
            android.util.Log.w("CameraScreen", "Depth Anything init failed (optional)", e)
        }

        try {
            segHelper = SegFormerHelper(context)
            android.util.Log.i("CameraScreen", "SegFormer B0 initialized")
        } catch (e: Exception) {
            android.util.Log.w("CameraScreen", "SegFormer init failed (optional)", e)
        }

        isInitialized = true
        android.util.Log.d("CameraScreen", "ML models initialization complete")
    }

    // FPS tracking state
    var frameCount by remember { mutableIntStateOf(0) }
    var lastFpsUpdateTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Create hazard processor when detector is ready
    LaunchedEffect(yoloDetector, isInitialized) {
        if (yoloDetector != null && isInitialized) {
            android.util.Log.d("CameraScreen", "Creating HazardDetectionProcessor...")
            hazardProcessor = HazardDetectionProcessor(
                detector = yoloDetector!!,
                depthHelper = depthHelper,
                segHelper = segHelper,
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
                },
                onFrameCaptured = { bitmap ->
                    // Store latest frame for OCR
                    currentBitmap = bitmap
                }
            )
            statusMessage = "Ready - Point camera forward"
            android.util.Log.i("CameraScreen", "HazardDetectionProcessor ready")
            ttsManager.speak("Capable started. Point camera forward to detect obstacles.")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            yoloDetector?.close()
            depthHelper?.close()
            segHelper?.close()
            ocrManager.close()
            ttsManager.shutdown()
        }
    }
    
    // Scanning voice loop - says "scanning" softly while OCR is processing
    LaunchedEffect(isScanning) {
        while (isScanning) {
            ttsManager.speakSoft("scanning")
            delay(1500L)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(isScanning) {
                detectTapGestures(
                    onDoubleTap = {
                        if (!isDetectionActive) {
                            // Double-tap to resume detection (OCR done or stopped)
                            android.util.Log.d("CameraScreen", "Double tap detected - resuming detection")
                            isScanning = false
                            ttsManager.stop()
                            ttsManager.speak("Resuming detection", TTSManager.Priority.NORMAL)
                            isDetectionActive = true
                        } else {
                            // Capture bitmap BEFORE stopping detection
                            val capturedBitmap = currentBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            
                            // Stop object detection first
                            isDetectionActive = false
                            isScanning = true
                            
                            android.util.Log.d("CameraScreen", "Double tap detected - starting OCR, bitmap=${capturedBitmap != null}")
                            
                            scope.launch {
                                // Small delay to let "scanning" start
                                delay(500)
                                
                                if (capturedBitmap != null) {
                                    ocrManager.processImage(
                                        bitmap = capturedBitmap,
                                        onResult = { text ->
                                            isScanning = false
                                            ttsManager.stop()
                                            if (text.isNotEmpty()) {
                                                // Limit text length for TTS
                                                val readText = if (text.length > 500) {
                                                    text.take(500) + "... text truncated"
                                                } else {
                                                    text
                                                }
                                                ttsManager.speak("Text found: $readText. Double tap to resume detection.", TTSManager.Priority.HIGH)
                                            } else {
                                                ttsManager.speak("No text detected. Double tap to resume detection.", TTSManager.Priority.NORMAL)
                                            }
                                            // Do NOT auto-resume, user must double-tap again
                                        },
                                        onError = { e ->
                                            isScanning = false
                                            ttsManager.stop()
                                            ttsManager.speak("Could not read text. Double tap to resume detection.", TTSManager.Priority.NORMAL)
                                            // Do NOT auto-resume, user must double-tap again
                                            android.util.Log.e("CameraScreen", "OCR error", e)
                                        }
                                    )
                                } else {
                                    isScanning = false
                                    ttsManager.stop()
                                    ttsManager.speak("Camera not ready", TTSManager.Priority.NORMAL)
                                    isDetectionActive = true // Resume object detection
                                }
                            }
                        }
                    }
                )
            }
    ) {
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
            onSosContacts = onNavigateToContacts,
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
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var isBound by remember { mutableStateOf(false) }
    val previewViewRef = remember { mutableStateOf<PreviewView?>(null) }

    // Get camera provider once
    LaunchedEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        cameraProvider = future.get()
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraProvider?.unbindAll()
            cameraExecutor.shutdown()
        }
    }

    // Handle camera binding/unbinding based on isActive
    LaunchedEffect(isActive, hazardProcessor, cameraProvider) {
        val provider = cameraProvider ?: return@LaunchedEffect
        val previewView = previewViewRef.value
        
        if (!isActive || hazardProcessor == null) {
            if (isBound) {
                android.util.Log.d("CameraScreen", "STOPPING detection - unbinding camera")
                provider.unbindAll()
                isBound = false
            }
            return@LaunchedEffect
        }
        
        // Need to bind camera
        if (!isBound && previewView != null) {
            android.util.Log.d("CameraScreen", "STARTING detection - binding camera")
            
            val preview = Preview.Builder()
                .build()
                .also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(
                        cameraExecutor,
                        FrameAnalyzer(hazardProcessor, targetFps = 10)
                    )
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )
                isBound = true
                android.util.Log.i("CameraScreen", "Camera bound successfully")
            } catch (e: Exception) {
                android.util.Log.e("CameraScreen", "Camera binding failed", e)
                e.printStackTrace()
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                previewViewRef.value = this
            }
        },
        modifier = modifier.fillMaxSize(),
        update = { previewView ->
            // Just update reference, binding handled by LaunchedEffect
            if (previewViewRef.value != previewView) {
                previewViewRef.value = previewView
            }
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
    onSosContacts: () -> Unit,
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

            Button(
                onClick = onSosContacts,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF5722)
                ),
                modifier = Modifier.size(80.dp)
            ) {
                Text("SOS", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
