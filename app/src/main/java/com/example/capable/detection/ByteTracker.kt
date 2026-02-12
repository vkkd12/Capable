package com.example.capable.detection

import kotlin.math.sqrt

/**
 * ByteTrack multi-object tracker
 *
 * Assigns persistent IDs across frames and computes per-track velocity so the
 * rest of the pipeline can tell whether an object is *moving* without manual
 * frame-to-frame heuristics.
 *
 * Algorithm (simplified):
 *   1. Predict all existing tracks with a linear motion model.
 *   2. First association  — high-confidence detections ↔ active tracks (IoU).
 *   3. Second association — low-confidence detections  ↔ remaining tracks.
 *   4. Try to re-activate lost tracks with remaining high-confidence dets.
 *   5. Promote unmatched high-confidence dets to new tracks.
 *   6. Expire tracks that have been lost too long.
 *
 * Reference: https://arxiv.org/abs/2110.06864
 */
class ByteTracker(
    private val highThresh: Float = 0.5f,
    private val lowThresh: Float = 0.1f,
    private val matchThresh: Float = 0.3f,
    private val maxTimeLost: Int = 30,
    private val minHits: Int = 3
) {
    private var nextId = 1
    private val active  = mutableListOf<Track>()
    private val lost    = mutableListOf<Track>()

    /* ------------------------------------------------------------------ */
    /*  Public data                                                       */
    /* ------------------------------------------------------------------ */

    data class Track(
        val id: Int,
        var bbox: FloatArray,        // [x1 y1 x2 y2] normalised 0‑1
        var vel: FloatArray,         // [vx vy vw vh]  per-frame delta
        var score: Float,
        var classId: Int,
        var label: String,
        var age: Int = 0,
        var hits: Int = 0,
        var sinceUpdate: Int = 0,
        var activated: Boolean = false
    ) {
        val centerX get() = (bbox[0] + bbox[2]) / 2f
        val centerY get() = (bbox[1] + bbox[3]) / 2f
        val width   get() = bbox[2] - bbox[0]
        val height  get() = bbox[3] - bbox[1]
        val speed   get() = sqrt(vel[0] * vel[0] + vel[1] * vel[1])
        val isMoving get() = speed > MOTION_THRESHOLD
        override fun equals(other: Any?) = other is Track && id == other.id
        override fun hashCode() = id
    }

    /* ------------------------------------------------------------------ */
    /*  Main update                                                       */
    /* ------------------------------------------------------------------ */

    fun update(detections: List<Detection>): List<Track> {
        // 1  Predict
        active.forEach(::predict)
        lost.forEach(::predict)

        val hi = detections.filter { it.confidence >= highThresh }
        val lo = detections.filter { it.confidence in lowThresh..highThresh }

        // 2  First association — high-conf ↔ active
        val (m1, ut1, ud1) = associate(active, hi)
        for ((ti, di) in m1) updateTrack(active[ti], hi[di])

        // 3  Second association — low-conf ↔ remaining active
        val remTracks = ut1.map { active[it] }
        val (m2, ut2, _) = associate(remTracks, lo)
        for ((ti, di) in m2) updateTrack(remTracks[ti], lo[di])

        // 4  Move still-unmatched active tracks to lost
        val newlyLost = ut2.map { remTracks[it] }
        for (t in newlyLost) { t.sinceUpdate++; if (t.activated) lost.add(t) }
        active.removeAll(newlyLost.toSet())

        // 5  Try re-activate lost tracks with remaining high-conf dets
        val remDets = ud1.map { hi[it] }
        val (m3, _, ud3) = associate(lost, remDets)
        val recovered = mutableSetOf<Track>()
        for ((ti, di) in m3) {
            val t = lost[ti]; updateTrack(t, remDets[di]); t.activated = true
            active.add(t); recovered.add(t)
        }
        lost.removeAll(recovered)

        // 6  New tracks for unmatched high-conf dets
        for (di in ud3) {
            val d = remDets[di]
            active.add(Track(
                id = nextId++,
                bbox = floatArrayOf(d.x1, d.y1, d.x2, d.y2),
                vel  = floatArrayOf(0f, 0f, 0f, 0f),
                score = d.confidence, classId = d.classId, label = d.label
            ))
        }

        // 7  Expire old lost tracks
        lost.removeAll { it.sinceUpdate > maxTimeLost }

        // 8  Activate tracks with enough hits
        for (t in active) if (!t.activated && t.hits >= minHits) t.activated = true

        return active.filter { it.activated }
    }

    /* ------------------------------------------------------------------ */
    /*  Internals                                                         */
    /* ------------------------------------------------------------------ */

    private fun predict(t: Track) {
        t.bbox[0] += t.vel[0]; t.bbox[1] += t.vel[1]
        t.bbox[2] += t.vel[2]; t.bbox[3] += t.vel[3]
        t.age++
    }

    private fun updateTrack(t: Track, d: Detection) {
        val a = 0.4f
        val dx = d.centerX - t.centerX
        val dy = d.centerY - t.centerY
        val dw = d.width   - t.width
        val dh = d.height  - t.height
        t.vel[0] = a * dx + (1 - a) * t.vel[0]
        t.vel[1] = a * dy + (1 - a) * t.vel[1]
        t.vel[2] = a * dw + (1 - a) * t.vel[2]
        t.vel[3] = a * dh + (1 - a) * t.vel[3]
        t.bbox = floatArrayOf(d.x1, d.y1, d.x2, d.y2)
        t.score = d.confidence; t.classId = d.classId; t.label = d.label
        t.hits++; t.sinceUpdate = 0
    }

    /** @return (matched pairs, unmatched-track indices, unmatched-det indices) */
    private fun associate(
        tracks: List<Track>, dets: List<Detection>
    ): Triple<List<Pair<Int, Int>>, List<Int>, List<Int>> {
        if (tracks.isEmpty() || dets.isEmpty())
            return Triple(emptyList(), tracks.indices.toList(), dets.indices.toList())

        // IoU matrix
        val iou = Array(tracks.size) { t ->
            FloatArray(dets.size) { d -> computeIoU(tracks[t].bbox,
                floatArrayOf(dets[d].x1, dets[d].y1, dets[d].x2, dets[d].y2)) }
        }

        // Greedy matching
        val matched = mutableListOf<Pair<Int, Int>>()
        val usedT = mutableSetOf<Int>(); val usedD = mutableSetOf<Int>()
        while (true) {
            var best = matchThresh; var bt = -1; var bd = -1
            for (t in tracks.indices) { if (t in usedT) continue
                for (d in dets.indices) { if (d in usedD) continue
                    if (iou[t][d] > best) { best = iou[t][d]; bt = t; bd = d }
                }
            }
            if (bt < 0) break
            matched.add(bt to bd); usedT.add(bt); usedD.add(bd)
        }
        return Triple(matched,
            tracks.indices.filter { it !in usedT },
            dets.indices.filter { it !in usedD })
    }

    private fun computeIoU(a: FloatArray, b: FloatArray): Float {
        val ix1 = maxOf(a[0], b[0]); val iy1 = maxOf(a[1], b[1])
        val ix2 = minOf(a[2], b[2]); val iy2 = minOf(a[3], b[3])
        val inter = maxOf(0f, ix2 - ix1) * maxOf(0f, iy2 - iy1)
        val union = (a[2]-a[0])*(a[3]-a[1]) + (b[2]-b[0])*(b[3]-b[1]) - inter
        return if (union > 0f) inter / union else 0f
    }

    fun reset() { active.clear(); lost.clear(); nextId = 1 }

    companion object {
        const val MOTION_THRESHOLD = 0.015f   // normalised units / frame
    }
}
