package com.locksight.guard

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class NsfwAiScanner(context: Context) {

    companion object {
        private const val TAG = "LockSightAI"
        private const val INPUT_SIZE = 320

        private val LABELS = arrayOf(
            "FEMALE_GENITALIA_COVERED", // 0  (Level 2)
            "FACE_FEMALE",              // 1  (Female Face Mask)
            "BUTTOCKS_EXPOSED",         // 2  (Level 1)
            "FEMALE_BREAST_EXPOSED",    // 3  (Level 1)
            "FEMALE_GENITALIA_EXPOSED", // 4  (Level 1)
            "MALE_BREAST_EXPOSED",      // 5  (Male Chest Mask)
            "ANUS_EXPOSED",             // 6  (Level 1)
            "FEET_EXPOSED",             // 7  (Ignored)
            "BELLY_COVERED",            // 8  (Ignored)
            "FEET_COVERED",             // 9  (Ignored)
            "ARMPITS_COVERED",          // 10 (Ignored)
            "ARMPITS_EXPOSED",          // 11 (Ignored)
            "FACE_MALE",                // 12 (Male Face Mask)
            "BELLY_EXPOSED",            // 13 (Level 3 — Female Context Only)
            "MALE_GENITALIA_EXPOSED",   // 14 (Level 1 — Contextual Co-Detection)
            "ANUS_COVERED",             // 15 (Level 2)
            "FEMALE_BREAST_COVERED",    // 16 (Level 3 — Male-Chest Suppressed)
            "BUTTOCKS_COVERED"          // 17 (Level 2)
        )

        private val EXPOSED_LEVEL1_CLASSES = setOf(2, 3, 4, 6, 14)
        private val FEMALE_EXPLICIT_CONTEXT_CLASSES = setOf(2, 3, 4, 6)
        private val CHEST_OR_MIDRIFF_CLASSES = setOf(3, 13, 16)

        @Volatile private var sharedEnv: OrtEnvironment? = null
        @Volatile private var sharedSession: OrtSession? = null
        @Volatile private var sharedInputName: String = "images"
        private val sessionLock = Any()
    }

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var inputName: String = "images"

    private val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val nchwBuffer = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
    private val letterboxBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
    private val letterboxCanvas = Canvas(letterboxBitmap)
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private var lastFrameFingerprint: Long = 0L
    private var lastFrameResult: ScanResult = ScanResult(false, 0f, 0f, 0f, 1f, "Standby", false)
    private var consecutiveHits = 0
    private var lastHitClass = -1

    private data class Box(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val score: Float,
        val classId: Int,
        val sameAnchorMaleBreast: Float = 0f
    )

    data class ScanResult(
        val blocked: Boolean,
        val pornScore: Float,
        val sexyScore: Float,
        val hentaiScore: Float,
        val neutralScore: Float,
        val reason: String,
        val needsFastRecheck: Boolean = false
    )

    init {
        synchronized(sessionLock) {
            if (sharedSession == null) {
                try {
                    val env = OrtEnvironment.getEnvironment()
                    val opts = OrtSession.SessionOptions().apply {
                        setIntraOpNumThreads(2)
                        setInterOpNumThreads(1)
                        setCPUArenaAllocator(false)
                        setMemoryPatternOptimization(true)
                        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    }
                    val modelBytes = context.assets.open("320n.onnx").use { it.readBytes() }
                    sharedEnv = env
                    sharedSession = env.createSession(modelBytes, opts)
                    sharedSession?.inputNames?.firstOrNull()?.let { sharedInputName = it }
                } catch (_: Exception) {
                    sharedSession = null
                }
            }
            ortEnv = sharedEnv
            ortSession = sharedSession
            inputName = sharedInputName
        }
    }

    @Synchronized
    fun analyzeScreen(bitmap: Bitmap, aiLevel: Int): ScanResult {
        val env = ortEnv
        val session = ortSession
        if (env == null || session == null) {
            return ScanResult(false, 0f, 0f, 0f, 1f, "320n.onnx missing in assets")
        }

        val w = bitmap.width
        val h = bitmap.height
        if (w <= 32 || h <= 32) {
            return ScanResult(false, 0f, 0f, 0f, 1f, "Empty frame")
        }

        val fingerprint = computeQuickFrameFingerprint(bitmap, aiLevel)
        if (consecutiveHits == 0 && fingerprint == lastFrameFingerprint) {
            return lastFrameResult.copy(blocked = false, needsFastRecheck = false)
        }

        val cropRegions = mutableListOf<Rect>()
        if (h > (w * 1.30f).toInt()) {
            val safeTop = (h * 0.06f).toInt()
            val safeBottom = (h * 0.95f).toInt()
            val usableH = (safeBottom - safeTop).coerceAtLeast(w)
            val sliceH = min(usableH, (w * 1.15f).toInt())

            val upperEnd = (safeTop + sliceH).coerceAtMost(h)
            cropRegions.add(Rect(0, safeTop, w, upperEnd))

            val lowerStart = (safeBottom - sliceH).coerceAtLeast(safeTop)
            if (lowerStart > safeTop + 20) {
                cropRegions.add(Rect(0, lowerStart, w, safeBottom))
            }
        } else {
            cropRegions.add(Rect(0, 0, w, h))
        }

        var bestExplicit = 0f
        var bestRevealing = 0f
        var winningBox: Box? = null

        for (region in cropRegions) {
            val candidate = runSingleVerifiedPass(env, session, bitmap, region, aiLevel)
            if (candidate != null) {
                val isExp = EXPOSED_LEVEL1_CLASSES.contains(candidate.classId)
                if (isExp && candidate.score > bestExplicit) bestExplicit = candidate.score
                if (!isExp && candidate.score > bestRevealing) bestRevealing = candidate.score
                if (winningBox == null || candidate.score > winningBox.score) {
                    winningBox = candidate
                }
                if (isExp && candidate.classId != 14 && candidate.score >= 0.80f) break
            }
        }

        var blocked = false
        var needsFastRecheck = false
        var triggerLabel = "Clear"

        if (winningBox != null) {
            val cls = winningBox.classId
            val score = winningBox.score
            val className = LABELS[cls].replace("_", " ")

            val isInstant = when (cls) {
                4, 6 -> score >= 0.74f
                14 -> score >= 0.88f
                3, 2 -> score >= 0.85f
                else -> false
            }

            if (isInstant) {
                consecutiveHits = 0
                lastHitClass = -1
                blocked = true
                triggerLabel = "L$aiLevel: $className (${(score * 100).toInt()}%)"
            } else {
                consecutiveHits = if (lastHitClass == cls) consecutiveHits + 1 else 1
                lastHitClass = cls
                if (consecutiveHits >= 2) {
                    consecutiveHits = 0
                    lastHitClass = -1
                    blocked = true
                    triggerLabel = "L$aiLevel: $className (${(score * 100).toInt()}%)"
                } else {
                    needsFastRecheck = true
                    triggerLabel = "Verifying $className (${(score * 100).toInt()}%)"
                }
            }
            Log.d(TAG, "DECISION L$aiLevel | cls=$className score=${(score * 100).toInt()}% blocked=$blocked recheck=$needsFastRecheck")
        } else {
            consecutiveHits = 0
            lastHitClass = -1
        }

        val finalResult = ScanResult(
            blocked = blocked,
            pornScore = bestExplicit,
            sexyScore = bestRevealing,
            hentaiScore = 0f,
            neutralScore = (1f - max(bestExplicit, bestRevealing)).coerceIn(0f, 1f),
            reason = triggerLabel,
            needsFastRecheck = needsFastRecheck
        )

        lastFrameFingerprint = if (blocked || needsFastRecheck) 0L else fingerprint
        lastFrameResult = finalResult
        return finalResult
    }

    private fun runSingleVerifiedPass(
        env: OrtEnvironment,
        session: OrtSession,
        srcBitmap: Bitmap,
        region: Rect,
        aiLevel: Int
    ): Box? {
        val cropW = region.width().toFloat()
        val cropH = region.height().toFloat()
        if (cropW <= 0f || cropH <= 0f) return null

        letterboxCanvas.drawColor(Color.BLACK)
        val scale = min(INPUT_SIZE / cropW, INPUT_SIZE / cropH)
        val activeW = cropW * scale
        val activeH = cropH * scale

        srcRect.set(region)
        dstRect.set(0f, 0f, activeW, activeH)
        letterboxCanvas.drawBitmap(srcBitmap, srcRect, dstRect, scalePaint)

        letterboxBitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        val planeSize = INPUT_SIZE * INPUT_SIZE
        val inv255 = 1.0f / 255.0f
        for (i in 0 until planeSize) {
            val px = pixels[i]
            nchwBuffer[i] = ((px shr 16) and 0xFF) * inv255
            nchwBuffer[planeSize + i] = ((px shr 8) and 0xFF) * inv255
            nchwBuffer[2 * planeSize + i] = (px and 0xFF) * inv255
        }

        val femaleFaceBoxes = mutableListOf<Box>()
        val maleFaceBoxes = mutableListOf<Box>()
        val maleBreastBoxes = mutableListOf<Box>()
        val rawCandidates = mutableListOf<Box>()
        var hasFemaleExplicitContext = false

        try {
            val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
            OnnxTensor.createTensor(env, FloatBuffer.wrap(nchwBuffer), shape).use { inputTensor ->
                session.run(mapOf(inputName to inputTensor)).use { results ->
                    val outTensor = results[0] as OnnxTensor
                    val outShape = outTensor.info.shape
                    val outBuf = outTensor.floatBuffer

                    if (outShape.size == 3) {
                        val d1 = outShape[1].toInt()
                        val d2 = outShape[2].toInt()
                        val isStandard = (d1 == 4 + LABELS.size)
                        val numAnchors = if (isStandard) d2 else d1

                        for (a in 0 until numAnchors) {
                            var bestCls = -1
                            var bestScore = 0f

                            // Multi-label read: ALWAYS capture Male Chest & Faces independently per anchor!
                            val femaleFaceScore = if (isStandard) outBuf.get((4 + 1) * numAnchors + a) else outBuf.get(a * d2 + 4 + 1)
                            val maleBreastScore = if (isStandard) outBuf.get((4 + 5) * numAnchors + a) else outBuf.get(a * d2 + 4 + 5)
                            val maleFaceScore = if (isStandard) outBuf.get((4 + 12) * numAnchors + a) else outBuf.get(a * d2 + 4 + 12)

                            for (c in LABELS.indices) {
                                val v = if (isStandard) {
                                    outBuf.get((4 + c) * numAnchors + a)
                                } else {
                                    outBuf.get(a * d2 + 4 + c)
                                }
                                if (v > bestScore) {
                                    bestScore = v
                                    bestCls = c
                                }
                            }

                            val interestingMask = maleBreastScore >= 0.14f || femaleFaceScore >= 0.28f || maleFaceScore >= 0.28f
                            if (bestScore < 0.34f && !interestingMask) continue

                            var cx = if (isStandard) outBuf.get(a) else outBuf.get(a * d2)
                            var cy = if (isStandard) outBuf.get(numAnchors + a) else outBuf.get(a * d2 + 1)
                            var bw = if (isStandard) outBuf.get(2 * numAnchors + a) else outBuf.get(a * d2 + 2)
                            var bh = if (isStandard) outBuf.get(3 * numAnchors + a) else outBuf.get(a * d2 + 3)

                            if (bw <= 1.5f && bh <= 1.5f && cx <= 1.5f && cy <= 1.5f) {
                                cx *= INPUT_SIZE; cy *= INPUT_SIZE; bw *= INPUT_SIZE; bh *= INPUT_SIZE
                            }

                            if (cx < 0f || cy < 0f || cx > activeW || cy > activeH) continue

                            val x1 = (cx - bw * 0.5f).coerceIn(0f, activeW)
                            val y1 = (cy - bh * 0.5f).coerceIn(0f, activeH)
                            val x2 = (cx + bw * 0.5f).coerceIn(0f, activeW)
                            val y2 = (cy + bh * 0.5f).coerceIn(0f, activeH)

                            // Record independent masks BEFORE candidate filtering
                            if (femaleFaceScore >= 0.28f && bw >= 12f && bh >= 12f) {
                                femaleFaceBoxes.add(Box(x1, y1, x2, y2, femaleFaceScore, 1))
                            }
                            if (maleFaceScore >= 0.28f && bw >= 12f && bh >= 12f) {
                                maleFaceBoxes.add(Box(x1, y1, x2, y2, maleFaceScore, 12))
                            }
                            if (maleBreastScore >= 0.14f && bw >= 12f && bh >= 12f) {
                                maleBreastBoxes.add(Box(x1, y1, x2, y2, maleBreastScore, 5))
                            }

                            if (bestCls == -1 || bestCls == 1 || bestCls == 12 || bestCls == 5 || bestScore < 0.36f) continue

                            if (FEMALE_EXPLICIT_CONTEXT_CLASSES.contains(bestCls) && bestScore >= 0.44f && bw >= 14f && bh >= 14f) {
                                hasFemaleExplicitContext = true
                            }

                            val minBoxSize = when (bestCls) {
                                14 -> 20f
                                in EXPOSED_LEVEL1_CLASSES -> 14f
                                else -> 24f
                            }
                            if (bw < minBoxSize || bh < minBoxSize) continue
                            if (bw > activeW * 0.94f && bh > activeH * 0.94f) continue

                            rawCandidates.add(Box(x1, y1, x2, y2, bestScore, bestCls, maleBreastScore))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            return null
        }

        if (rawCandidates.isEmpty()) return null

        val allFaceBoxes = femaleFaceBoxes + maleFaceBoxes
        val verified = rawCandidates.filter { box ->
            meetsLevelThreshold(box.classId, box.score, aiLevel, hasFemaleExplicitContext)
        }.sortedByDescending { it.score }

        for (box in verified) {
            val clsName = LABELS[box.classId]

            // 1. Face-Overlap Rejection (Protects portraits & Hijab)
            val overlapsFace = allFaceBoxes.any { face -> computeIoU(box, face) >= 0.16f || isCenterInside(box, face) }
            if (overlapsFace) {
                Log.d(TAG, "SKIP_FACE L$aiLevel $clsName=${(box.score * 100).toInt()}%")
                continue
            }

            // 2. MULTI-LABEL MALE-CHEST & LOCAL MALE-FACE TORSO SUPPRESSION (Fixes video_1.mp4 in crowds!)
            if (CHEST_OR_MIDRIFF_CLASSES.contains(box.classId)) {
                val maxOverlapMb = maleBreastBoxes
                    .filter { mb -> computeIoU(box, mb) >= 0.12f || isCenterInside(box, mb) || isCenterInside(mb, box) }
                    .maxOfOrNull { it.score } ?: 0f
                val effectiveMb = max(box.sameAnchorMaleBreast, maxOverlapMb)
                val maleFaceOwner = isOwnedByMaleFace(box, maleFaceBoxes, femaleFaceBoxes)

                if ((effectiveMb >= 0.14f && box.score < 0.92f) || (maleFaceOwner && box.score < 0.92f)) {
                    Log.d(TAG, "SKIP_MALE_TORSO L$aiLevel $clsName=${(box.score * 100).toInt()}% mb=${(effectiveMb * 100).toInt()}% maleOwner=$maleFaceOwner")
                    continue
                }
            }

            // 3. INNER-CORE BLUR / CENSOR REJECTION ON LEVEL 1 (Fixes video_2.mp4 blurred GTA 6 thumbnail!)
            val innerSharp = computeInnerCoreSharpness(box)
            if (aiLevel == 1 && EXPOSED_LEVEL1_CLASSES.contains(box.classId)) {
                val isBlurredCensor = when (box.classId) {
                    4 -> box.score < 0.74f && innerSharp < 7.2f // Blurred genitalia thumbnails (e.g. 63%)
                    3 -> box.score < 0.84f && innerSharp < 7.2f // Blurred chest thumbnails
                    2, 6 -> box.score < 0.78f && innerSharp < 6.8f
                    else -> false
                }
                if (isBlurredCensor) {
                    Log.d(TAG, "SKIP_BLUR L$aiLevel $clsName=${(box.score * 100).toInt()}% innerSharp=${"%.1f".format(innerSharp)}")
                    continue
                }
            }

            // 4. Universal YCbCr Skin-Tone Verification
            var skinRatio = 1f
            if (EXPOSED_LEVEL1_CLASSES.contains(box.classId) || box.classId == 13) {
                skinRatio = computeYCbCrSkinRatioInsideBox(box)
                val minSkin = if (box.classId == 14 && !hasFemaleExplicitContext) 0.34f else 0.22f
                if (skinRatio < minSkin) {
                    Log.d(TAG, "SKIP_SKIN L$aiLevel $clsName=${(box.score * 100).toInt()}% skin=${(skinRatio * 100).toInt()}%")
                    continue
                }
            }

            Log.d(
                TAG,
                "PASS L$aiLevel $clsName=${(box.score * 100).toInt()}% mb=${(box.sameAnchorMaleBreast * 100).toInt()}% " +
                        "sharp=${"%.1f".format(innerSharp)} skin=${(skinRatio * 100).toInt()}% " +
                        "box=${(box.x2 - box.x1).toInt()}x${(box.y2 - box.y1).toInt()}"
            )
            return box
        }

        return null
    }

    /**
     * Checks local body geometry: if a MALE face sits directly above this chest/midriff box
     * (and no FEMALE face sits closer above it), the torso belongs to a shirtless man—even in a crowd!
     */
    private fun isOwnedByMaleFace(
        torsoBox: Box,
        maleFaces: List<Box>,
        femaleFaces: List<Box>
    ): Boolean {
        if (maleFaces.isEmpty()) return false
        val tCx = (torsoBox.x1 + torsoBox.x2) * 0.5f
        val tCy = (torsoBox.y1 + torsoBox.y2) * 0.5f
        val tW = (torsoBox.x2 - torsoBox.x1).coerceAtLeast(1f)
        val tH = (torsoBox.y2 - torsoBox.y1).coerceAtLeast(1f)

        fun bestDistanceAbove(faces: List<Box>): Float {
            var best = Float.MAX_VALUE
            for (f in faces) {
                val fCx = (f.x1 + f.x2) * 0.5f
                val fCy = (f.y1 + f.y2) * 0.5f
                val fW = (f.x2 - f.x1).coerceAtLeast(1f)
                val fH = (f.y2 - f.y1).coerceAtLeast(1f)

                val dy = tCy - fCy
                val dx = abs(tCx - fCx)
                if (dy > 0f && dy <= max(fH * 4.8f, tH * 4.5f) && dx <= max(fW * 2.4f, tW * 2.2f)) {
                    val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    if (dist < best) best = dist
                }
            }
            return best
        }

        val nearestMale = bestDistanceAbove(maleFaces)
        if (nearestMale == Float.MAX_VALUE) return false
        val nearestFemale = bestDistanceAbove(femaleFaces)
        return nearestMale <= nearestFemale
    }

    private fun meetsLevelThreshold(
        classId: Int,
        score: Float,
        aiLevel: Int,
        hasFemaleExplicitContext: Boolean
    ): Boolean {
        return when (aiLevel) {
            1 -> {
                when (classId) {
                    4 -> score >= 0.52f     // FEMALE GENITALIA EXPOSED (clear unblurred catches immediately)
                    14 -> if (hasFemaleExplicitContext) score >= 0.55f else score >= 0.80f
                    6 -> score >= 0.54f     // ANUS EXPOSED
                    3 -> score >= 0.75f     // FEMALE BREAST EXPOSED
                    2 -> score >= 0.75f     // BUTTOCKS EXPOSED
                    else -> false
                }
            }
            2 -> {
                meetsLevelThreshold(classId, score, 1, hasFemaleExplicitContext) || when (classId) {
                    3 -> score >= 0.64f
                    2 -> score >= 0.66f
                    0, 15, 17 -> score >= 0.64f
                    else -> false
                }
            }
            else -> {
                meetsLevelThreshold(classId, score, 2, hasFemaleExplicitContext) || when (classId) {
                    16 -> score >= 0.64f    // FEMALE_BREAST_COVERED
                    13 -> score >= 0.68f    // BELLY_EXPOSED
                    else -> false
                }
            }
        }
    }

    /**
     * Measures average pixel-to-pixel luminance gradient strictly inside the INNER 60% CORE
     * of the bounding box so sharp background borders don't fool the blur/censor detector.
     */
    private fun computeInnerCoreSharpness(box: Box): Float {
        val bw = box.x2 - box.x1
        val bh = box.y2 - box.y1
        val insetX = bw * 0.20f
        val insetY = bh * 0.20f

        val x1 = (box.x1 + insetX).toInt().coerceIn(1, INPUT_SIZE - 2)
        val y1 = (box.y1 + insetY).toInt().coerceIn(1, INPUT_SIZE - 2)
        val x2 = (box.x2 - insetX).toInt().coerceIn(x1 + 1, INPUT_SIZE - 1)
        val y2 = (box.y2 - insetY).toInt().coerceIn(y1 + 1, INPUT_SIZE - 1)

        val stepX = max(1, (x2 - x1) / 8)
        val stepY = max(1, (y2 - y1) / 8)
        var diffSum = 0f
        var count = 0

        var y = y1
        while (y < y2) {
            val rowOff = y * INPUT_SIZE
            var x = x1
            while (x < x2) {
                val c = pixels[rowOff + x]
                val r = pixels[rowOff + x + 1]
                val d = pixels[(y + 1) * INPUT_SIZE + x]
                val lC = ((c shr 16) and 0xFF) * 0.3f + ((c shr 8) and 0xFF) * 0.59f + (c and 0xFF) * 0.11f
                val lR = ((r shr 16) and 0xFF) * 0.3f + ((r shr 8) and 0xFF) * 0.59f + (r and 0xFF) * 0.11f
                val lD = ((d shr 16) and 0xFF) * 0.3f + ((d shr 8) and 0xFF) * 0.59f + (d and 0xFF) * 0.11f
                diffSum += abs(lC - lR) + abs(lC - lD)
                count++
                x += stepX
            }
            y += stepY
        }
        return if (count == 0) 0f else diffSum / count.toFloat()
    }

    private fun computeYCbCrSkinRatioInsideBox(box: Box): Float {
        val x1 = box.x1.toInt().coerceIn(0, INPUT_SIZE - 1)
        val y1 = box.y1.toInt().coerceIn(0, INPUT_SIZE - 1)
        val x2 = box.x2.toInt().coerceIn(x1 + 1, INPUT_SIZE)
        val y2 = box.y2.toInt().coerceIn(y1 + 1, INPUT_SIZE)

        val stepX = max(1, (x2 - x1) / 8)
        val stepY = max(1, (y2 - y1) / 8)
        var total = 0
        var skin = 0

        var y = y1
        while (y < y2) {
            val rowOff = y * INPUT_SIZE
            var x = x1
            while (x < x2) {
                val px = pixels[rowOff + x]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF

                val yVal = 0.299f * r + 0.587f * g + 0.114f * b
                val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
                val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b

                if (yVal > 38f && cb in 75f..132f && cr in 131f..178f) {
                    skin++
                }
                total++
                x += stepX
            }
            y += stepY
        }
        return if (total == 0) 0f else skin.toFloat() / total.toFloat()
    }

    private fun computeIoU(a: Box, b: Box): Float {
        val ix1 = max(a.x1, b.x1)
        val iy1 = max(a.y1, b.y1)
        val ix2 = min(a.x2, b.x2)
        val iy2 = min(a.y2, b.y2)
        val iw = (ix2 - ix1).coerceAtLeast(0f)
        val ih = (iy2 - iy1).coerceAtLeast(0f)
        val inter = iw * ih
        if (inter <= 0f) return 0f
        val areaA = (a.x2 - a.x1) * (a.y2 - a.y1)
        val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)
        return inter / (areaA + areaB - inter).coerceAtLeast(1f)
    }

    private fun isCenterInside(inner: Box, outer: Box): Boolean {
        val cx = (inner.x1 + inner.x2) * 0.5f
        val cy = (inner.y1 + inner.y2) * 0.5f
        return cx in outer.x1..outer.x2 && cy in outer.y1..outer.y2
    }

    private fun computeQuickFrameFingerprint(bmp: Bitmap, aiLevel: Int): Long {
        val w = bmp.width
        val h = bmp.height
        var hash = aiLevel.toLong() * 31L
        for (gy in 1..4) {
            val y = (h * gy) / 5
            for (gx in 1..4) {
                val x = (w * gx) / 5
                val px = bmp.getPixel(x, y)
                val q = (((px shr 19) and 0x1F) shl 10) or (((px shr 11) and 0x1F) shl 5) or ((px shr 3) and 0x1F)
                hash = hash * 31L + q
            }
        }
        return hash
    }
}