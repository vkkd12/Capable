package com.example.capable.detection

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * SegFormer B0 — semantic segmentation via ONNX Runtime
 * Trained on ADE20K (150 classes) for wall / floor / obstacle detection.
 *
 * Input:  [1, 3, 512, 512] RGB with ImageNet normalisation
 * Output: [1, 150, 128, 128] per-pixel logits  (model downsamples 4×)
 */
class SegFormerHelper(
    private val context: Context
) {
    private var session: OrtSession? = null
    private val env = OrtEnvironment.getEnvironment()
    private var inputName = "pixel_values"

    val isAvailable: Boolean get() = session != null

    init { setupSession() }

    private fun setupSession() {
        try {
            val bytes = context.assets.open(MODEL_FILE).readBytes()
            val opts = OrtSession.SessionOptions().apply {
                try {
                    addNnapi()
                    Log.i(TAG, "NNAPI enabled for segmentation")
                } catch (e: Exception) {
                    Log.w(TAG, "NNAPI unavailable: ${e.message}")
                }
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(4)
            }
            session = env.createSession(bytes, opts)
            inputName = session!!.inputNames.first()
            Log.i(TAG, "SegFormer B0 loaded — input=$inputName")
        } catch (e: Exception) {
            Log.w(TAG, "SegFormer model not available: ${e.message}")
        }
    }

    /**
     * Run semantic segmentation.
     * @return IntArray of size [OUTPUT_SIZE²] where each value is the ADE20K class ID (0‑149).
     */
    fun segment(bitmap: Bitmap): IntArray? {
        val sess = session ?: return null
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val buf = preprocess(resized)
        val tensor = OnnxTensor.createTensor(
            env, buf, longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )
        return try {
            val result = sess.run(mapOf(inputName to tensor))
            val logits = (result[0] as OnnxTensor).floatBuffer

            // argmax over 150 classes at each spatial position
            val segMap = IntArray(OUTPUT_SIZE * OUTPUT_SIZE)
            for (h in 0 until OUTPUT_SIZE) {
                for (w in 0 until OUTPUT_SIZE) {
                    var mx = Float.NEGATIVE_INFINITY; var mc = 0
                    for (c in 0 until NUM_CLASSES) {
                        val v = logits.get(c * OUTPUT_SIZE * OUTPUT_SIZE + h * OUTPUT_SIZE + w)
                        if (v > mx) { mx = v; mc = c }
                    }
                    segMap[h * OUTPUT_SIZE + w] = mc
                }
            }
            result.close()
            segMap
        } catch (e: Exception) {
            Log.e(TAG, "Segmentation failed: ${e.message}")
            null
        } finally {
            tensor.close()
        }
    }

    /** Analyse what surface types dominate a normalised rectangular region. */
    fun analyzeRegion(
        segMap: IntArray,
        left: Float, top: Float, right: Float, bottom: Float
    ): SurfaceAnalysis {
        val x0 = (left   * OUTPUT_SIZE).toInt().coerceIn(0, OUTPUT_SIZE - 1)
        val y0 = (top    * OUTPUT_SIZE).toInt().coerceIn(0, OUTPUT_SIZE - 1)
        val x1 = (right  * OUTPUT_SIZE).toInt().coerceIn(0, OUTPUT_SIZE - 1)
        val y1 = (bottom * OUTPUT_SIZE).toInt().coerceIn(0, OUTPUT_SIZE - 1)

        val counts = mutableMapOf<Int, Int>()
        var total = 0
        for (y in y0..y1) for (x in x0..x1) {
            val cls = segMap[y * OUTPUT_SIZE + x]
            counts[cls] = (counts[cls] ?: 0) + 1
            total++
        }
        if (total == 0) return SurfaceAnalysis(false, false, false, -1, 0f)

        val domCls = counts.maxByOrNull { it.value }!!.key
        val domPct = counts[domCls]!!.toFloat() / total

        val wallPct  = WALL_CLASSES.sumOf     { counts[it] ?: 0 }.toFloat() / total
        val floorPct = FLOOR_CLASSES.sumOf    { counts[it] ?: 0 }.toFloat() / total
        val obsPct   = OBSTACLE_CLASSES.sumOf { counts[it] ?: 0 }.toFloat() / total

        return SurfaceAnalysis(
            isWall      = wallPct  > 0.4f,
            isFloor     = floorPct > 0.4f,
            hasObstacle = obsPct   > 0.15f,
            dominantClassId  = domCls,
            dominantClassPct = domPct
        )
    }

    /* ---- preprocessing ---- */

    private fun preprocess(bitmap: Bitmap): FloatBuffer {
        val px = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(px, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        val buf = FloatBuffer.allocate(3 * INPUT_SIZE * INPUT_SIZE)
        val ch = INPUT_SIZE * INPUT_SIZE
        for (i in px.indices) {
            val p = px[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr  8) and 0xFF) / 255f
            val b = ( p         and 0xFF) / 255f
            buf.put(i,          (r - 0.485f) / 0.229f)
            buf.put(i + ch,     (g - 0.456f) / 0.224f)
            buf.put(i + 2 * ch, (b - 0.406f) / 0.225f)
        }
        buf.rewind()
        return buf
    }

    fun close() { session?.close(); session = null }

    /* ---- types ---- */

    data class SurfaceAnalysis(
        val isWall: Boolean,
        val isFloor: Boolean,
        val hasObstacle: Boolean,
        val dominantClassId: Int,
        val dominantClassPct: Float
    )

    companion object {
        private const val TAG = "SegFormerHelper"
        private const val MODEL_FILE = "segformer_b0.onnx"
        private const val INPUT_SIZE  = 512
        private const val OUTPUT_SIZE = 128
        private const val NUM_CLASSES = 150

        // ADE20K class‑ID groups
        val WALL_CLASSES     = setOf(0, 1, 8, 14)           // wall, building, windowpane, door
        val FLOOR_CLASSES    = setOf(3, 6, 11, 9, 13, 29, 52) // floor, road, sidewalk, grass, earth, rug, path
        val OBSTACLE_CLASSES = setOf(
            10, 15, 19, 24, 33, 36, 38, 42, 53, 65, 82, 93, 100, 136
            // cabinet, table, plant, column, pole, stairs, railing, box,
            // fence, pillar, barrier, step, signboard, post
        )
    }
}
