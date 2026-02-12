package com.example.capable.detection

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * YOLOv8 Nano Object Detector using ONNX Runtime (FP32, 320x320)
 * Input:  [1, 3, 320, 320] RGB normalized [0,1]
 * Output: [1, 84, 2100] -- 4 bbox coords + 80 class scores x 2100 anchors
 *
 * Optimisations over the naive approach:
 *  - 320x320 instead of 640x640 (4x fewer pixels, ~3x faster inference)
 *  - Direct ByteBuffer for zero-copy OnnxTensor creation
 *  - Pre-allocated buffers reused across frames (no GC pressure)
 *  - Bulk FloatBuffer.get() into FloatArray for post-processing
 *  - XNNPACK delegate for faster CPU inference
 */
class YoloV8Detector(
    private val context: Context,
    private val confThreshold: Float = 0.40f,
    private val iouThreshold: Float = 0.5f,
    private val maxResults: Int = 10
) {
    private var session: OrtSession? = null
    private val env = OrtEnvironment.getEnvironment()
    private var inputName = "images"

    // Pre-allocated reusable buffers (avoids GC each frame)
    private val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val inputBuf: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 3 * INPUT_SIZE * INPUT_SIZE)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    // CHW float arrays for fast sequential writes
    private val rCh = FloatArray(INPUT_SIZE * INPUT_SIZE)
    private val gCh = FloatArray(INPUT_SIZE * INPUT_SIZE)
    private val bCh = FloatArray(INPUT_SIZE * INPUT_SIZE)

    val isAvailable: Boolean get() = session != null

    init { setupSession() }

    private fun setupSession() {
        try {
            val bytes = context.assets.open(MODEL_FILE).readBytes()
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(4)
                try {
                    addConfigEntry("session.use_xnnpack", "1")
                    Log.i(TAG, "XNNPACK enabled")
                } catch (_: Exception) { }
            }
            session = env.createSession(bytes, opts)
            inputName = session!!.inputNames.first()
            Log.i(TAG, "YOLOv8n loaded (320x320) -- input=$inputName")
        } catch (e: Exception) {
            Log.e(TAG, "Model load failed: ${e.message}")
        }
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        val sess = session ?: return emptyList()
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        preprocess(resized)
        val tensor = OnnxTensor.createTensor(env, inputBuf, longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()))
        return try {
            val result = sess.run(mapOf(inputName to tensor))
            val outTensor = result[0] as OnnxTensor
            val dets = postProcess(outTensor.floatBuffer)
            result.close()
            dets
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed: ${e.message}")
            emptyList()
        } finally {
            tensor.close()
        }
    }

    /* ---- preprocessing (reuses pre-allocated buffers) ---- */

    private fun preprocess(bitmap: Bitmap) {
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        // Decode ARGB pixels into separate R, G, B float arrays
        for (i in pixels.indices) {
            val p = pixels[i]
            rCh[i] = ((p shr 16) and 0xFF) * INV_255
            gCh[i] = ((p shr  8) and 0xFF) * INV_255
            bCh[i] = ( p         and 0xFF) * INV_255
        }
        // Write CHW interleaved into the direct buffer (sequential = fast)
        inputBuf.rewind()
        inputBuf.put(rCh)
        inputBuf.put(gCh)
        inputBuf.put(bCh)
        inputBuf.rewind()
    }

    /* ---- post-processing with NMS ---- */

    private fun postProcess(output: FloatBuffer): List<Detection> {
        // Bulk-copy output to a float array (random access on FloatArray ≈ 10x faster than FloatBuffer)
        output.rewind()
        val total = 84 * NUM_PREDS
        val data = FloatArray(total)
        output.get(data)

        val raw = mutableListOf<RawDet>()
        for (i in 0 until NUM_PREDS) {
            // Find best class score
            var best = 0f; var cls = 0
            for (c in 0 until NUM_CLASSES) {
                val s = data[(4 + c) * NUM_PREDS + i]
                if (s > best) { best = s; cls = c }
            }
            if (best < confThreshold) continue

            val cx = data[i]                    // row 0
            val cy = data[NUM_PREDS + i]        // row 1
            val w  = data[2 * NUM_PREDS + i]    // row 2
            val h  = data[3 * NUM_PREDS + i]    // row 3

            raw.add(RawDet(
                ((cx - w * 0.5f) / INPUT_SIZE).coerceIn(0f, 1f),
                ((cy - h * 0.5f) / INPUT_SIZE).coerceIn(0f, 1f),
                ((cx + w * 0.5f) / INPUT_SIZE).coerceIn(0f, 1f),
                ((cy + h * 0.5f) / INPUT_SIZE).coerceIn(0f, 1f),
                best, cls
            ))
        }
        return nms(raw).take(maxResults).map {
            Detection(it.cls, COCO_LABELS.getOrElse(it.cls) { "unknown" },
                it.score, it.x1, it.y1, it.x2, it.y2)
        }
    }

    private fun nms(dets: List<RawDet>): List<RawDet> {
        val sorted = dets.sortedByDescending { it.score }.toMutableList()
        val kept = mutableListOf<RawDet>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeFirst()
            kept.add(best)
            sorted.removeAll { iou(best, it) > iouThreshold }
        }
        return kept
    }

    private fun iou(a: RawDet, b: RawDet): Float {
        val ix1 = maxOf(a.x1, b.x1); val iy1 = maxOf(a.y1, b.y1)
        val ix2 = minOf(a.x2, b.x2); val iy2 = minOf(a.y2, b.y2)
        val inter = maxOf(0f, ix2 - ix1) * maxOf(0f, iy2 - iy1)
        val union = (a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - inter
        return if (union > 0f) inter / union else 0f
    }

    fun close() { session?.close(); session = null }

    private data class RawDet(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val score: Float, val cls: Int
    )

    companion object {
        private const val TAG = "YoloV8Detector"
        private const val MODEL_FILE = "yolov8n_fp16.onnx"
        private const val INPUT_SIZE = 320
        private const val NUM_CLASSES = 80
        private const val NUM_PREDS = 2100
        private const val INV_255 = 1f / 255f

        val COCO_LABELS = arrayOf(
            "person","bicycle","car","motorcycle","airplane","bus","train","truck",
            "boat","traffic light","fire hydrant","stop sign","parking meter","bench",
            "bird","cat","dog","horse","sheep","cow","elephant","bear","zebra","giraffe",
            "backpack","umbrella","handbag","tie","suitcase","frisbee","skis","snowboard",
            "sports ball","kite","baseball bat","baseball glove","skateboard","surfboard",
            "tennis racket","bottle","wine glass","cup","fork","knife","spoon","bowl",
            "banana","apple","sandwich","orange","broccoli","carrot","hot dog","pizza",
            "donut","cake","chair","couch","potted plant","bed","dining table","toilet",
            "tv","laptop","mouse","remote","keyboard","cell phone","microwave","oven",
            "toaster","sink","refrigerator","book","clock","vase","scissors","teddy bear",
            "hair drier","toothbrush"
        )
    }
}

/** Single detected object — bbox coordinates are normalized 0‑1 */
data class Detection(
    val classId: Int,
    val label: String,
    val confidence: Float,
    val x1: Float, val y1: Float,
    val x2: Float, val y2: Float
) {
    val centerX get() = (x1 + x2) / 2f
    val centerY get() = (y1 + y2) / 2f
    val width   get() = x2 - x1
    val height  get() = y2 - y1
    val area    get() = width * height
}
