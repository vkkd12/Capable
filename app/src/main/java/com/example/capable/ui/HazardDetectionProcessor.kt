package com.example.capable.ui

import android.graphics.Bitmap
import android.util.Log
import com.example.capable.audio.TTSManager
import com.example.capable.camera.FrameAnalyzer
import com.example.capable.detection.DepthEstimatorHelper
import com.example.capable.detection.ObjectDetectorHelper
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult

/**
 * Combines object detection and depth estimation to provide
 * meaningful audio feedback to visually impaired users
 */
class HazardDetectionProcessor(
    private val objectDetector: ObjectDetectorHelper,
    private val depthEstimator: DepthEstimatorHelper?,
    private val ttsManager: TTSManager,
    private val onDetectionUpdate: (List<DetectedHazard>) -> Unit = {}
) : FrameAnalyzer.FrameProcessor {

    // High-priority objects that need immediate attention
    private val highPriorityObjects = setOf(
        "car", "truck", "bus", "motorcycle", "bicycle",
        "person", "dog", "cat",
        "chair", "bench", "fire hydrant", "stop sign",
        "traffic light", "parking meter"
    )

    // Very dangerous objects - immediate warning needed
    private val dangerousObjects = setOf(
        "car", "truck", "bus", "motorcycle", "bicycle"
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
        "bicycle" to "vehicle"
    )

    private var frameProcessCount = 0L
    
    // Detection stability tracking
    private data class RegionDetection(
        var label: String,
        val group: String,
        val direction: TTSManager.Direction,
        var frameCount: Int = 1,
        var lastSeenTime: Long = System.currentTimeMillis(),
        var announced: Boolean = false
    )
    
    private val activeRegions = mutableMapOf<TTSManager.Direction, RegionDetection>()
    private val STABILITY_FRAMES = 3 // Require 3 consistent frames before announcing
    private val REGION_TIMEOUT_MS = 2000L // Clear region after 2 seconds of not seeing it
    private val REANNOUNCE_INTERVAL_MS = 8000L // Don't re-announce same region for 8 seconds
    
    override fun processFrame(bitmap: Bitmap, timestamp: Long) {
        try {
            frameProcessCount++
            
            // Run object detection
            val detectionResult = objectDetector.detect(bitmap)

            // Run depth estimation (if available)
            val depthMap = depthEstimator?.estimateDepth(bitmap)

            // Process results
            val hazards = processDetections(detectionResult, depthMap, bitmap.width, bitmap.height)

            // Update UI - this should trigger FPS calculation
            onDetectionUpdate(hazards)

            // Announce hazards via TTS
            announceHazards(hazards)
            
            // Log progress periodically
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
        imageWidth: Int,
        imageHeight: Int
    ): List<DetectedHazard> {
        if (result == null) return emptyList()

        val hazards = mutableListOf<DetectedHazard>()

        for (detection in result.detections()) {
            val category = detection.categories().firstOrNull() ?: continue
            val label = category.categoryName().lowercase()
            val confidence = category.score()

            // Skip low confidence detections
            if (confidence < 0.5f) continue

            val boundingBox = detection.boundingBox()
            val centerX = (boundingBox.left + boundingBox.right) / 2 / imageWidth
            val centerY = (boundingBox.top + boundingBox.bottom) / 2 / imageHeight

            // Determine direction based on horizontal position
            val direction = when {
                centerX < 0.33f -> TTSManager.Direction.LEFT
                centerX > 0.66f -> TTSManager.Direction.RIGHT
                else -> TTSManager.Direction.CENTER
            }

            // Estimate distance using depth map or bounding box size
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
                // Fallback: estimate distance from bounding box size
                val boxArea = (boundingBox.right - boundingBox.left) *
                             (boundingBox.bottom - boundingBox.top)
                val relativeSize = boxArea / (imageWidth * imageHeight)
                sizeToDistance(relativeSize)
            }

            val priority = calculatePriority(label, distance)

            hazards.add(DetectedHazard(
                label = label,
                confidence = confidence,
                distance = distance,
                direction = direction,
                priority = priority,
                boundingBox = BoundingBox(
                    boundingBox.left / imageWidth,
                    boundingBox.top / imageHeight,
                    boundingBox.right / imageWidth,
                    boundingBox.bottom / imageHeight
                )
            ))
        }

        return hazards.sortedByDescending { it.priority }
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

    private fun calculatePriority(label: String, distance: TTSManager.Distance): Int {
        var priority = 0

        // Distance factor
        priority += when (distance) {
            TTSManager.Distance.VERY_CLOSE -> 100
            TTSManager.Distance.CLOSE -> 50
            TTSManager.Distance.MEDIUM -> 20
            TTSManager.Distance.FAR -> 5
        }

        // Object type factor
        priority += when {
            label in dangerousObjects -> 50
            label in highPriorityObjects -> 25
            else -> 10
        }

        return priority
    }

    private fun announceHazards(hazards: List<DetectedHazard>) {
        val now = System.currentTimeMillis()
        
        // Clean up stale regions
        activeRegions.entries.removeIf { now - it.value.lastSeenTime > REGION_TIMEOUT_MS }
        
        // Track which directions have detections this frame
        val currentDirections = mutableSetOf<TTSManager.Direction>()
        
        // Only consider hazards worth announcing
        val relevantHazards = hazards
            .filter { it.distance != TTSManager.Distance.FAR || it.label in dangerousObjects }
            .take(2)
        
        for (hazard in relevantHazards) {
            val direction = hazard.direction
            val group = objectGroups[hazard.label] ?: hazard.label
            currentDirections.add(direction)
            
            val existing = activeRegions[direction]
            
            if (existing != null) {
                // Same group in same region - update tracking
                if (existing.group == group) {
                    existing.frameCount++
                    existing.lastSeenTime = now
                    existing.label = hazard.label // Update to latest label
                    
                    // Announce if stable and not recently announced
                    if (existing.frameCount >= STABILITY_FRAMES && 
                        (!existing.announced || now - existing.lastSeenTime > REANNOUNCE_INTERVAL_MS)) {
                        
                        if (!existing.announced) {
                            doAnnounce(hazard)
                            existing.announced = true
                        }
                    }
                } else {
                    // Different group - reset tracking (object changed)
                    activeRegions[direction] = RegionDetection(
                        label = hazard.label,
                        group = group,
                        direction = direction
                    )
                }
            } else {
                // New detection in this region
                activeRegions[direction] = RegionDetection(
                    label = hazard.label,
                    group = group,
                    direction = direction
                )
            }
        }
        
        // Reset announced flag for regions that lost detection (object moved away)
        activeRegions.forEach { (dir, region) ->
            if (dir !in currentDirections) {
                // Object left this region, allow re-announcement when it returns
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
