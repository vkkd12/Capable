package com.example.capable.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.example.capable.audio.TTSManager
import com.example.capable.camera.FrameAnalyzer
import com.example.capable.detection.DepthEstimatorHelper
import com.example.capable.detection.ObjectDetectorHelper
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Combines object detection, depth estimation, and motion tracking
 * to provide meaningful audio feedback to visually impaired users
 * 
 * Priority Order:
 * 1. CRITICAL: Moving vehicles (car, bus, motorcycle approaching)
 * 2. HIGH: Other moving objects or very close obstacles
 * 3. NORMAL: OCR results (handled externally)
 * 4. LOW: Stationary objects
 * 5. BACKGROUND: Walls/static surfaces
 */
class HazardDetectionProcessor(
    private val objectDetector: ObjectDetectorHelper,
    private val depthEstimator: DepthEstimatorHelper?,
    private val ttsManager: TTSManager,
    private val onDetectionUpdate: (List<DetectedHazard>) -> Unit = {},
    private val onFrameCaptured: (Bitmap) -> Unit = {}
) : FrameAnalyzer.FrameProcessor {

    // Vehicle objects - highest priority when moving
    private val vehicleObjects = setOf(
        "car", "truck", "bus", "motorcycle", "bicycle"
    )
    
    // Objects that can move and are dangerous
    private val movableObjects = setOf(
        "car", "truck", "bus", "motorcycle", "bicycle",
        "person", "dog", "cat", "bird", "horse"
    )
    
    // Stationary objects that are obstacles
    private val stationaryObjects = setOf(
        "chair", "bench", "fire hydrant", "stop sign",
        "traffic light", "parking meter", "potted plant",
        "dining table", "toilet", "tv", "laptop", "refrigerator"
    )
    
    // Similar objects grouped together to prevent oscillation announcements
    private val objectGroups = mapOf(
        "bed" to "furniture",
        "couch" to "furniture", 
        "sofa" to "furniture",
        "chair" to "seating",
        "bench" to "seating",
        "car" to "vehicle",
        "truck" to "vehicle",
        "bus" to "vehicle",
        "motorcycle" to "vehicle",
        "bicycle" to "vehicle",
        "obstacle ahead" to "obstacle",
        "obstacle on left" to "obstacle",
        "obstacle on right" to "obstacle",
        "wall ahead" to "wall",
        "wall on left" to "wall",
        "wall on right" to "wall"
    )

    private var frameProcessCount = 0L
    
    // Motion tracking - store previous frame positions
    private data class TrackedObject(
        val label: String,
        val centerX: Float,
        val centerY: Float,
        val timestamp: Long
    )
    private var previousFrameObjects = mutableListOf<TrackedObject>()
    private val MOTION_THRESHOLD = 0.05f // 5% of screen movement = moving
    
    // Detection stability tracking
    private data class RegionDetection(
        var label: String,
        val group: String,
        val direction: TTSManager.Direction,
        var frameCount: Int = 1,
        var lastSeenTime: Long = System.currentTimeMillis(),
        var announced: Boolean = false,
        var isMoving: Boolean = false
    )
    
    private val activeRegions = mutableMapOf<TTSManager.Direction, RegionDetection>()
    private val STABILITY_FRAMES = 3
    private val REGION_TIMEOUT_MS = 2000L
    private val REANNOUNCE_INTERVAL_MS = 8000L
    
    override fun processFrame(bitmap: Bitmap, timestamp: Long) {
        try {
            frameProcessCount++
            
            // Provide bitmap for OCR
            onFrameCaptured(bitmap.copy(Bitmap.Config.ARGB_8888, false))
            
            // Run object detection
            val detectionResult = objectDetector.detect(bitmap)

            // Run depth estimation (if available)
            val depthMap = depthEstimator?.estimateDepth(bitmap)

            // Process results with motion detection
            val hazards = processDetections(detectionResult, depthMap, bitmap, timestamp)

            // Update UI
            onDetectionUpdate(hazards)

            // Announce hazards via TTS with priority ordering
            announceHazards(hazards)
            
            if (frameProcessCount % 30 == 0L) {
                Log.d(TAG, "Processed $frameProcessCount frames, detected ${hazards.size} hazards")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Processing error: ${e.message}", e)
        }
    }

    private fun processDetections(
        result: ObjectDetectorResult?,
        depthMap: FloatArray?,
        bitmap: Bitmap,
        timestamp: Long
    ): List<DetectedHazard> {
        val hazards = mutableListOf<DetectedHazard>()
        val detectedDirections = mutableSetOf<TTSManager.Direction>()
        val currentFrameObjects = mutableListOf<TrackedObject>()
        val imageWidth = bitmap.width.toFloat()
        val imageHeight = bitmap.height.toFloat()

        // Process detected objects
        if (result != null) {
            for (detection in result.detections()) {
                val category = detection.categories().firstOrNull() ?: continue
                val label = category.categoryName().lowercase()
                val confidence = category.score()

                if (confidence < 0.5f) continue

                val boundingBox = detection.boundingBox()
                val centerX = (boundingBox.left + boundingBox.right) / 2 / imageWidth
                val centerY = (boundingBox.top + boundingBox.bottom) / 2 / imageHeight

                // Track object for motion detection
                currentFrameObjects.add(TrackedObject(label, centerX, centerY, timestamp))
                
                // Check if this object is moving by comparing to previous frame
                val isMoving = detectMotion(label, centerX, centerY)

                val direction = when {
                    centerX < 0.33f -> TTSManager.Direction.LEFT
                    centerX > 0.66f -> TTSManager.Direction.RIGHT
                    else -> TTSManager.Direction.CENTER
                }
                
                detectedDirections.add(direction)

                val distance = if (depthMap != null) {
                    val depth = depthEstimator?.getRegionDepth(
                        depthMap,
                        boundingBox.left / imageWidth,
                        boundingBox.top / imageHeight,
                        boundingBox.right / imageWidth,
                        boundingBox.bottom / imageHeight
                    ) ?: 0.5f
                    depthToDistance(depth)
                } else {
                    val boxArea = (boundingBox.right - boundingBox.left) *
                                 (boundingBox.bottom - boundingBox.top)
                    val relativeSize = boxArea / (imageWidth * imageHeight)
                    sizeToDistance(relativeSize)
                }

                // Calculate priority based on movement and object type
                val priority = calculatePriority(label, distance, isMoving)

                hazards.add(DetectedHazard(
                    label = if (isMoving && label in vehicleObjects) "moving $label" else label,
                    confidence = confidence,
                    distance = distance,
                    direction = direction,
                    priority = priority,
                    isMoving = isMoving,
                    boundingBox = BoundingBox(
                        boundingBox.left / imageWidth,
                        boundingBox.top / imageHeight,
                        boundingBox.right / imageWidth,
                        boundingBox.bottom / imageHeight
                    )
                ))
            }
        }
        
        // Update tracked objects for next frame
        previousFrameObjects = currentFrameObjects
        
        // Detect walls using depth + color uniformity where no object detected
        if (depthMap != null && depthEstimator != null) {
            val regions = listOf(
                Triple(0.0f to 0.33f, TTSManager.Direction.LEFT, "wall on left"),
                Triple(0.33f to 0.66f, TTSManager.Direction.CENTER, "wall ahead"),
                Triple(0.66f to 1.0f, TTSManager.Direction.RIGHT, "wall on right")
            )
            
            for ((xRange, direction, wallLabel) in regions) {
                if (direction in detectedDirections) continue
                
                val depth = depthEstimator.getRegionDepth(
                    depthMap,
                    xRange.first,
                    0.3f,
                    xRange.second,
                    0.7f
                )
                
                // Check color uniformity to distinguish wall from open space
                val isUniformColor = checkColorUniformity(
                    bitmap,
                    (xRange.first * imageWidth).toInt(),
                    (0.3f * imageHeight).toInt(),
                    (xRange.second * imageWidth).toInt(),
                    (0.7f * imageHeight).toInt()
                )
                
                // Wall detection: close depth + uniform color
                if (depth > 0.7f && isUniformColor) {
                    hazards.add(DetectedHazard(
                        label = wallLabel,
                        confidence = depth,
                        distance = TTSManager.Distance.VERY_CLOSE,
                        direction = direction,
                        priority = 30, // Lower priority than moving objects
                        isMoving = false,
                        boundingBox = BoundingBox(xRange.first, 0.3f, xRange.second, 0.7f)
                    ))
                } else if (depth > 0.6f) {
                    // Generic obstacle if close but not uniform (could be clutter)
                    val obstacleLabel = when (direction) {
                        TTSManager.Direction.LEFT -> "obstacle on left"
                        TTSManager.Direction.CENTER -> "obstacle ahead"
                        TTSManager.Direction.RIGHT -> "obstacle on right"
                    }
                    hazards.add(DetectedHazard(
                        label = obstacleLabel,
                        confidence = depth,
                        distance = TTSManager.Distance.CLOSE,
                        direction = direction,
                        priority = 25,
                        isMoving = false,
                        boundingBox = BoundingBox(xRange.first, 0.3f, xRange.second, 0.7f)
                    ))
                }
            }
        }

        return hazards.sortedByDescending { it.priority }
    }
    
    /**
     * Detect motion by comparing object position to previous frame
     */
    private fun detectMotion(label: String, currentX: Float, currentY: Float): Boolean {
        // Only track movable objects for motion
        if (label !in movableObjects) return false
        
        // Find same object type in previous frame
        val prevObject = previousFrameObjects.find { it.label == label }
            ?: return false
        
        // Calculate movement distance
        val dx = abs(currentX - prevObject.centerX)
        val dy = abs(currentY - prevObject.centerY)
        val movement = sqrt(dx * dx + dy * dy)
        
        return movement > MOTION_THRESHOLD
    }
    
    /**
     * Check if a region has uniform color (likely a wall/flat surface)
     * Low variance in color = wall, high variance = textured/multiple objects
     */
    private fun checkColorUniformity(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Boolean {
        val safeLeft = left.coerceIn(0, bitmap.width - 1)
        val safeTop = top.coerceIn(0, bitmap.height - 1)
        val safeRight = right.coerceIn(safeLeft + 1, bitmap.width)
        val safeBottom = bottom.coerceIn(safeTop + 1, bitmap.height)
        
        val width = safeRight - safeLeft
        val height = safeBottom - safeTop
        
        if (width <= 0 || height <= 0) return false
        
        // Sample pixels for efficiency (every 10th pixel)
        val sampleStep = 10
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0
        
        for (y in safeTop until safeBottom step sampleStep) {
            for (x in safeLeft until safeRight step sampleStep) {
                val pixel = bitmap.getPixel(x, y)
                sumR += Color.red(pixel)
                sumG += Color.green(pixel)
                sumB += Color.blue(pixel)
                count++
            }
        }
        
        if (count == 0) return false
        
        val avgR = sumR / count
        val avgG = sumG / count
        val avgB = sumB / count
        
        // Calculate variance
        var varianceSum = 0L
        for (y in safeTop until safeBottom step sampleStep) {
            for (x in safeLeft until safeRight step sampleStep) {
                val pixel = bitmap.getPixel(x, y)
                val dr = Color.red(pixel) - avgR
                val dg = Color.green(pixel) - avgG
                val db = Color.blue(pixel) - avgB
                varianceSum += dr * dr + dg * dg + db * db
            }
        }
        
        val variance = varianceSum / count
        
        // Low variance = uniform color = likely a wall
        // Threshold determined empirically
        return variance < 2000
    }

    private fun depthToDistance(depth: Float): TTSManager.Distance {
        // Depth values: 0 = far, 1 = near (normalized MiDaS output)
        return when {
            depth > 0.8f -> TTSManager.Distance.VERY_CLOSE
            depth > 0.6f -> TTSManager.Distance.CLOSE
            depth > 0.3f -> TTSManager.Distance.MEDIUM
            else -> TTSManager.Distance.FAR
        }
    }

    private fun sizeToDistance(relativeSize: Float): TTSManager.Distance {
        // Larger bounding box = closer object
        return when {
            relativeSize > 0.25f -> TTSManager.Distance.VERY_CLOSE
            relativeSize > 0.10f -> TTSManager.Distance.CLOSE
            relativeSize > 0.03f -> TTSManager.Distance.MEDIUM
            else -> TTSManager.Distance.FAR
        }
    }

    /**
     * Calculate priority for TTS announcement
     * Priority Order:
     * 1. CRITICAL (200+): Moving vehicles very close
     * 2. HIGH (100-199): Moving objects or very close obstacles  
     * 3. NORMAL (50-99): OCR handled separately
     * 4. LOW (20-49): Stationary objects
     * 5. BACKGROUND (0-19): Walls, static surfaces
     */
    private fun calculatePriority(label: String, distance: TTSManager.Distance, isMoving: Boolean): Int {
        var priority = 0

        // Base: Moving vehicles get highest priority
        if (isMoving && label in vehicleObjects) {
            priority = 200 // CRITICAL - moving vehicle
            priority += when (distance) {
                TTSManager.Distance.VERY_CLOSE -> 50
                TTSManager.Distance.CLOSE -> 30
                TTSManager.Distance.MEDIUM -> 15
                TTSManager.Distance.FAR -> 5
            }
            return priority
        }
        
        // Moving non-vehicle objects (person, dog, etc)
        if (isMoving && label in movableObjects) {
            priority = 100 // HIGH
            priority += when (distance) {
                TTSManager.Distance.VERY_CLOSE -> 40
                TTSManager.Distance.CLOSE -> 25
                TTSManager.Distance.MEDIUM -> 10
                TTSManager.Distance.FAR -> 0
            }
            return priority
        }
        
        // Stationary obstacles that are close
        if (label in stationaryObjects) {
            priority = 20 // LOW
            priority += when (distance) {
                TTSManager.Distance.VERY_CLOSE -> 25
                TTSManager.Distance.CLOSE -> 15
                TTSManager.Distance.MEDIUM -> 5
                TTSManager.Distance.FAR -> 0
            }
            return priority
        }
        
        // Walls and obstacles (lowest priority)
        if (label.contains("wall") || label.contains("obstacle")) {
            priority = 5 // BACKGROUND
            priority += when (distance) {
                TTSManager.Distance.VERY_CLOSE -> 15
                TTSManager.Distance.CLOSE -> 10
                TTSManager.Distance.MEDIUM -> 5
                TTSManager.Distance.FAR -> 0
            }
            return priority
        }
        
        // Default for other detected objects
        priority = 30
        priority += when (distance) {
            TTSManager.Distance.VERY_CLOSE -> 20
            TTSManager.Distance.CLOSE -> 10
            TTSManager.Distance.MEDIUM -> 5
            TTSManager.Distance.FAR -> 0
        }

        return priority
    }

    private fun announceHazards(hazards: List<DetectedHazard>) {
        val now = System.currentTimeMillis()
        
        // Clean up stale regions
        activeRegions.entries.removeIf { now - it.value.lastSeenTime > REGION_TIMEOUT_MS }
        
        val currentDirections = mutableSetOf<TTSManager.Direction>()
        
        // Sort by priority - moving vehicles first
        val sortedHazards = hazards.sortedByDescending { it.priority }
        
        // Moving vehicles get immediate announcement (no stability required)
        val movingVehicle = sortedHazards.find { it.isMoving && it.label.contains("moving") }
        if (movingVehicle != null && movingVehicle.distance != TTSManager.Distance.FAR) {
            doAnnounce(movingVehicle)
            return // Don't announce other things when moving vehicle detected
        }
        
        // Other hazards need stability before announcing
        val relevantHazards = sortedHazards
            .filter { it.distance != TTSManager.Distance.FAR || it.isMoving }
            .take(2)
        
        for (hazard in relevantHazards) {
            val direction = hazard.direction
            val baseLabel = hazard.label.removePrefix("moving ")
            val group = objectGroups[baseLabel] ?: baseLabel
            currentDirections.add(direction)
            
            val existing = activeRegions[direction]
            
            if (existing != null) {
                if (existing.group == group) {
                    existing.frameCount++
                    existing.lastSeenTime = now
                    existing.label = hazard.label
                    existing.isMoving = hazard.isMoving
                    
                    // Fewer frames required for moving objects
                    val requiredFrames = if (hazard.isMoving) 2 else STABILITY_FRAMES
                    
                    if (existing.frameCount >= requiredFrames && 
                        (!existing.announced || now - existing.lastSeenTime > REANNOUNCE_INTERVAL_MS)) {
                        
                        if (!existing.announced) {
                            doAnnounce(hazard)
                            existing.announced = true
                        }
                    }
                } else {
                    activeRegions[direction] = RegionDetection(
                        label = hazard.label,
                        group = group,
                        direction = direction,
                        isMoving = hazard.isMoving
                    )
                }
            } else {
                activeRegions[direction] = RegionDetection(
                    label = hazard.label,
                    group = group,
                    direction = direction,
                    isMoving = hazard.isMoving
                )
            }
        }
        
        activeRegions.forEach { (dir, region) ->
            if (dir !in currentDirections) {
                region.frameCount = 0
            }
        }
    }
    
    private fun doAnnounce(hazard: DetectedHazard) {
        ttsManager.announceObject(
            objectName = hazard.label,
            distance = hazard.distance,
            direction = hazard.direction
        )
    }

    data class DetectedHazard(
        val label: String,
        val confidence: Float,
        val distance: TTSManager.Distance,
        val direction: TTSManager.Direction,
        val priority: Int,
        val isMoving: Boolean = false,
        val boundingBox: BoundingBox
    )

    data class BoundingBox(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    companion object {
        private const val TAG = "HazardDetectionProcessor"
    }
}
