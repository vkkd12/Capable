package com.example.capable.ui

import android.graphics.Bitmap
import android.util.Log
import com.example.capable.audio.TTSManager
import com.example.capable.camera.FrameAnalyzer
import com.example.capable.detection.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Integrates YOLOv8 + ByteTrack + Depth Anything V2 + SegFormer B0
 * for hazard detection with prioritised TTS announcements.
 *
 * Pipeline per frame:
 *   Every frame  → YOLOv8 detect → ByteTrack update → announce
 *   Every 3rd    → Depth Anything V2 depth map
 *   Every 5th    → SegFormer B0 segmentation → wall/obstacle analysis
 *
 * Priority Order:
 * 1. CRITICAL (200+): Moving vehicles
 * 2. HIGH    (100‑199): Moving objects / very close obstacles
 * 3. NORMAL  (50‑99):  OCR (handled externally)
 * 4. LOW     (20‑49):  Stationary objects
 * 5. BG      (0‑19):   Walls / static surfaces
 */
class HazardDetectionProcessor(
    private val detector: YoloV8Detector,
    private val depthHelper: DepthAnythingHelper?,
    private val segHelper: SegFormerHelper?,
    private val ttsManager: TTSManager,
    private val onDetectionUpdate: (List<DetectedHazard>) -> Unit = {},
    private val onFrameCaptured: (Bitmap) -> Unit = {}
) : FrameAnalyzer.FrameProcessor {

    private val tracker = ByteTracker()

    // Background thread for slow models (depth + segmentation)
    private val heavyExecutor = Executors.newSingleThreadExecutor()
    private val heavyRunning = AtomicBoolean(false)

    // Cached results from slower models (written on background, read on worker)
    @Volatile private var latestDepthMap: FloatArray? = null
    @Volatile private var latestSegMap: IntArray? = null

    private var frameCount = 0L

    /* ---- announcement stability ---- */

    private data class RegionState(
        var label: String,
        var group: String,
        var direction: TTSManager.Direction,
        var frames: Int = 1,
        var lastSeen: Long = System.currentTimeMillis(),
        var announced: Boolean = false,
        var isMoving: Boolean = false
    )
    private val regions = mutableMapOf<TTSManager.Direction, RegionState>()

    /* ---- constants ---- */

    companion object {
        private const val TAG = "HazardDetectionProcessor"
        private const val STABILITY_FRAMES = 3
        private const val REGION_TIMEOUT_MS = 2000L
        private const val REANNOUNCE_MS = 8000L
        private const val DEPTH_INTERVAL = 5
        private const val SEG_INTERVAL = 8

        private val VEHICLES = setOf("car","truck","bus","motorcycle","bicycle","train")
        private val MOVABLE  = VEHICLES + setOf("person","dog","cat","bird","horse")
        private val STATIONARY = setOf(
            "chair","bench","fire hydrant","stop sign","traffic light",
            "parking meter","potted plant","dining table","toilet","tv",
            "laptop","refrigerator"
        )
        private val GROUP = mapOf(
            "bed" to "furniture","couch" to "furniture","sofa" to "furniture",
            "chair" to "seating","bench" to "seating",
            "car" to "vehicle","truck" to "vehicle","bus" to "vehicle",
            "motorcycle" to "vehicle","bicycle" to "vehicle",
            "obstacle ahead" to "obstacle","obstacle left" to "obstacle","obstacle right" to "obstacle",
            "wall ahead" to "wall","wall left" to "wall","wall right" to "wall"
        )
    }

    /* ================================================================== */
    /*  Main frame entry point                                            */
    /* ================================================================== */

    override fun processFrame(bitmap: Bitmap, timestamp: Long) {
        try {
            frameCount++
            // Copy bitmap every 5th frame for OCR / overlay (safe copy for cross-thread use)
            if (frameCount % 5 == 0L) {
                onFrameCaptured(bitmap.copy(Bitmap.Config.ARGB_8888, false))
            }

            // ---- YOLOv8 (every frame, fast path) ----
            val detections = detector.detect(bitmap)

            // ---- ByteTrack (every frame) ----
            val tracks = tracker.update(detections)

            // ---- Depth + SegFormer on background thread (non-blocking) ----
            val runDepth = frameCount % DEPTH_INTERVAL == 0L && depthHelper?.isAvailable == true
            val runSeg   = frameCount % SEG_INTERVAL == 0L && segHelper?.isAvailable == true
            if ((runDepth || runSeg) && heavyRunning.compareAndSet(false, true)) {
                val bmpCopy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                heavyExecutor.execute {
                    try {
                        if (runDepth) latestDepthMap = depthHelper?.estimateDepth(bmpCopy)
                        if (runSeg)   latestSegMap = segHelper?.segment(bmpCopy)
                    } catch (e: Exception) {
                        Log.e(TAG, "Heavy model error: ${e.message}")
                    } finally {
                        bmpCopy.recycle()
                        heavyRunning.set(false)
                    }
                }
            }

            // ---- Build & announce hazards ----
            val hazards = buildHazards(tracks)
            onDetectionUpdate(hazards)
            announceHazards(hazards)

            if (frameCount % 30 == 0L)
                Log.d(TAG, "Frame $frameCount -- ${detections.size} dets, ${tracks.size} tracks, ${hazards.size} hazards")
        } catch (e: Exception) {
            Log.e(TAG, "Processing error: ${e.message}", e)
        }
    }

    /* ================================================================== */
    /*  Build hazard list from tracks + depth + segmentation              */
    /* ================================================================== */

    private fun buildHazards(tracks: List<ByteTracker.Track>): List<DetectedHazard> {
        val hazards = mutableListOf<DetectedHazard>()
        val covered = mutableSetOf<TTSManager.Direction>()
        val depth = latestDepthMap
        val seg   = latestSegMap

        // ---- tracked objects ----
        for (t in tracks) {
            val dir = when {
                t.centerX < 0.33f -> TTSManager.Direction.LEFT
                t.centerX > 0.66f -> TTSManager.Direction.RIGHT
                else               -> TTSManager.Direction.CENTER
            }
            covered.add(dir)

            val dist = if (depth != null && depthHelper != null) {
                depthToDistance(depthHelper.getRegionDepth(depth, t.bbox[0], t.bbox[1], t.bbox[2], t.bbox[3]))
            } else {
                sizeToDistance(t.width * t.height)
            }

            val moving = t.isMoving
            val lbl = if (moving && t.label in VEHICLES) "moving ${t.label}" else t.label

            hazards.add(DetectedHazard(
                label      = lbl,
                confidence = t.score,
                distance   = dist,
                direction  = dir,
                priority   = calculatePriority(t.label, dist, moving),
                isMoving   = moving,
                trackId    = t.id,
                speed      = t.speed,
                boundingBox = BoundingBox(t.bbox[0], t.bbox[1], t.bbox[2], t.bbox[3])
            ))
        }

        // ---- wall / obstacle from SegFormer ----
        if (seg != null && segHelper != null) {
            val defs = listOf(
                Triple(0.00f to 0.33f, TTSManager.Direction.LEFT,   "left"),
                Triple(0.33f to 0.66f, TTSManager.Direction.CENTER, "ahead"),
                Triple(0.66f to 1.00f, TTSManager.Direction.RIGHT,  "right")
            )
            for ((xr, dir, tag) in defs) {
                if (dir in covered) continue
                val surf = segHelper.analyzeRegion(seg, xr.first, 0.3f, xr.second, 0.7f)
                val d = if (depth != null && depthHelper != null)
                    depthHelper.getRegionDepth(depth, xr.first, 0.3f, xr.second, 0.7f) else 0f

                if (surf.isWall && d > 0.65f) {
                    hazards.add(DetectedHazard("wall $tag", surf.dominantClassPct,
                        depthToDistance(d), dir, 30, false, -1, 0f,
                        BoundingBox(xr.first, 0.3f, xr.second, 0.7f)))
                } else if (surf.hasObstacle && d > 0.55f) {
                    hazards.add(DetectedHazard("obstacle $tag", surf.dominantClassPct,
                        depthToDistance(d), dir, 25, false, -1, 0f,
                        BoundingBox(xr.first, 0.3f, xr.second, 0.7f)))
                }
            }
        }

        return hazards.sortedByDescending { it.priority }
    }

    /* ================================================================== */
    /*  Distance helpers                                                  */
    /* ================================================================== */

    private fun depthToDistance(depth: Float) = when {
        depth > 0.8f -> TTSManager.Distance.VERY_CLOSE
        depth > 0.6f -> TTSManager.Distance.CLOSE
        depth > 0.3f -> TTSManager.Distance.MEDIUM
        else         -> TTSManager.Distance.FAR
    }

    private fun sizeToDistance(area: Float) = when {
        area > 0.25f -> TTSManager.Distance.VERY_CLOSE
        area > 0.10f -> TTSManager.Distance.CLOSE
        area > 0.03f -> TTSManager.Distance.MEDIUM
        else         -> TTSManager.Distance.FAR
    }

    /* ================================================================== */
    /*  Priority                                                          */
    /* ================================================================== */

    private fun calculatePriority(label: String, dist: TTSManager.Distance, moving: Boolean): Int {
        fun distBonus(vc: Int, c: Int, m: Int, f: Int) = when (dist) {
            TTSManager.Distance.VERY_CLOSE -> vc
            TTSManager.Distance.CLOSE      -> c
            TTSManager.Distance.MEDIUM     -> m
            TTSManager.Distance.FAR        -> f
        }
        return when {
            moving && label in VEHICLES   -> 200 + distBonus(50, 30, 15, 5)
            moving && label in MOVABLE    -> 100 + distBonus(40, 25, 10, 0)
            label in STATIONARY           ->  20 + distBonus(25, 15,  5, 0)
            label.contains("wall") ||
            label.contains("obstacle")    ->   5 + distBonus(15, 10,  5, 0)
            else                          ->  30 + distBonus(20, 10,  5, 0)
        }
    }

    /* ================================================================== */
    /*  TTS announcements with region stability                           */
    /* ================================================================== */

    private fun announceHazards(hazards: List<DetectedHazard>) {
        val now = System.currentTimeMillis()
        regions.entries.removeIf { now - it.value.lastSeen > REGION_TIMEOUT_MS }

        val sorted = hazards.sortedByDescending { it.priority }

        // Moving vehicles bypass stability
        val mv = sorted.find { it.isMoving && it.label.startsWith("moving") }
        if (mv != null && mv.distance != TTSManager.Distance.FAR) { doAnnounce(mv); return }

        val dirs = mutableSetOf<TTSManager.Direction>()
        val relevant = sorted.filter { it.distance != TTSManager.Distance.FAR || it.isMoving }.take(2)

        for (h in relevant) {
            val base = h.label.removePrefix("moving ")
            val grp = GROUP[base] ?: base
            dirs.add(h.direction)

            val ex = regions[h.direction]
            if (ex != null && ex.group == grp) {
                ex.frames++; ex.lastSeen = now; ex.label = h.label; ex.isMoving = h.isMoving
                val need = if (h.isMoving) 2 else STABILITY_FRAMES
                if (ex.frames >= need && (!ex.announced || now - ex.lastSeen > REANNOUNCE_MS)) {
                    if (!ex.announced) { doAnnounce(h); ex.announced = true }
                }
            } else {
                regions[h.direction] = RegionState(h.label, grp, h.direction, isMoving = h.isMoving)
            }
        }
        regions.forEach { (d, r) -> if (d !in dirs) r.frames = 0 }
    }

    private fun doAnnounce(h: DetectedHazard) {
        ttsManager.announceObject(h.label, h.distance, h.direction)
    }

    /* ================================================================== */
    /*  Data classes                                                      */
    /* ================================================================== */

    data class DetectedHazard(
        val label: String,
        val confidence: Float,
        val distance: TTSManager.Distance,
        val direction: TTSManager.Direction,
        val priority: Int,
        val isMoving: Boolean = false,
        val trackId: Int = -1,
        val speed: Float = 0f,
        val boundingBox: BoundingBox
    )

    data class BoundingBox(
        val left: Float, val top: Float,
        val right: Float, val bottom: Float
    )
}
