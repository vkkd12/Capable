package com.example.capable.detection

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * Depth Anything V2 Small — monocular depth estimation via ONNX Runtime (Float16)
 * Input:  [1, 3, 518, 518] RGB with ImageNet normalization
 * Output: [1, 518, 518] relative inverse-depth map
 */
class DepthAnythingHelper(
    private val context: Context
) {
    private var session: OrtSession? = null
    private val env = OrtEnvironment.getEnvironment()
    private var inputName = "image"

    val isAvailable: Boolean get() = session != null

    init { setupSession() }

    private fun setupSession() {
        try {
            val bytes = context.assets.open(MODEL_FILE).readBytes()
            val opts = OrtSession.SessionOptions().apply {
                try {
                    addNnapi()
                    Log.i(TAG, "NNAPI enabled for depth")
                } catch (e: Exception) {
                    Log.w(TAG, "NNAPI unavailable: ${e.message}")
                }
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(4)
            }
            session = env.createSession(bytes, opts)
            inputName = session!!.inputNames.first()
            Log.i(TAG, "Depth Anything V2 loaded — input=$inputName")
        } catch (e: Exception) {
            Log.w(TAG, "Depth model not available: ${e.message}")
        }
    }

    /**
     * Run depth estimation.
     * @return Normalized depth map [0‑1] of size INPUT_SIZE², where 1 = nearest.
     */
    fun estimateDepth(bitmap: Bitmap): FloatArray? {
        val sess = session ?: return null
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val buf = preprocess(resized)
        val tensor = OnnxTensor.createTensor(
            env, buf, longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )
        return try {
            val result = sess.run(mapOf(inputName to tensor))
            val raw = (result[0] as OnnxTensor).floatBuffer
            raw.rewind()

            val map = FloatArray(INPUT_SIZE * INPUT_SIZE)
            var lo = Float.MAX_VALUE; var hi = Float.MIN_VALUE
            for (i in map.indices) { val d = raw.get(); map[i] = d; if (d < lo) lo = d; if (d > hi) hi = d }

            val range = hi - lo
            if (range > 0f) for (i in map.indices) map[i] = (map[i] - lo) / range

            result.close()
            map
        } catch (e: Exception) {
            Log.e(TAG, "Depth estimation failed: ${e.message}")
            null
        } finally {
            tensor.close()
        }
    }

    /** Average depth in a normalised rectangle (0‑1 coords). */
    fun getRegionDepth(depthMap: FloatArray, left: Float, top: Float, right: Float, bottom: Float): Float {
        val x0 = (left  * INPUT_SIZE).toInt().coerceIn(0, INPUT_SIZE - 1)
        val y0 = (top   * INPUT_SIZE).toInt().coerceIn(0, INPUT_SIZE - 1)
        val x1 = (right * INPUT_SIZE).toInt().coerceIn(0, INPUT_SIZE - 1)
        val y1 = (bottom* INPUT_SIZE).toInt().coerceIn(0, INPUT_SIZE - 1)
        var sum = 0f; var n = 0
        for (y in y0..y1) for (x in x0..x1) { sum += depthMap[y * INPUT_SIZE + x]; n++ }
        return if (n > 0) sum / n else 0f
    }

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

    companion object {
        private const val TAG = "DepthAnythingHelper"
        private const val MODEL_FILE = "depth_anything_v2_small_fp16.onnx"
        const val INPUT_SIZE = 518
    }
}
