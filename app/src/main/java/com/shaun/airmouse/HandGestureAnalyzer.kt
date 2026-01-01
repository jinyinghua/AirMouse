package com.shaun.airmouse

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.pow
import kotlin.math.sqrt

class HandGestureAnalyzer(
    private val context: Context,
    private val listener: GestureListener
) {
    private var handLandmarker: HandLandmarker? = null

    interface GestureListener {
        fun onGestureUpdate(x: Float, y: Float, isPinching: Boolean, distance: Float)
        fun onError(error: String)
    }

    init {
        setupHandLandmarker()
    }

    private fun setupHandLandmarker() {
        val baseOptionsBuilder = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")

        val optionsBuilder = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptionsBuilder.build())
            .setMinHandDetectionConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setNumHands(1)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener(this::returnLivestreamResult)
            .setErrorListener(this::returnLivestreamError)

        try {
            handLandmarker = HandLandmarker.createFromOptions(context, optionsBuilder.build())
        } catch (e: Exception) {
            listener.onError("MediaPipe initialization failed: ${e.message}")
        }
    }

    fun analyze(bitmap: Bitmap, timestamp: Long) {
        val mpImage = BitmapImageBuilder(bitmap).build()
        handLandmarker?.detectAsync(mpImage, timestamp)
    }

    private fun returnLivestreamResult(result: HandLandmarkerResult, input: MPImage) {
        if (result.landmarks().isNotEmpty()) {
            val landmarks = result.landmarks()[0]
            
            // 4号点: 拇指尖, 8号点: 食指尖, 5号点: 食指指根 (虎口)
            val thumbTip = landmarks[4]
            val indexTip = landmarks[8]
            val tigerMouth = landmarks[5]

            // 使用虎口 (5号点) 作为鼠标的坐标锚点
            val anchorX = tigerMouth.x()
            val anchorY = tigerMouth.y()

            // 计算欧几里得距离 (用于捏合判定)
            val distance = sqrt(
                (thumbTip.x() - indexTip.x()).toDouble().pow(2.0) +
                (thumbTip.y() - indexTip.y()).toDouble().pow(2.0)
            ).toFloat()

            // 阈值判定 (经验值，可能需要根据实际情况微调)
            val pinchThreshold = 0.05f
            val isPinching = distance < pinchThreshold

            listener.onGestureUpdate(anchorX, anchorY, isPinching, distance)
        }
    }

    private fun returnLivestreamError(error: RuntimeException) {
        listener.onError(error.message ?: "Unknown error")
    }

    fun close() {
        handLandmarker?.close()
    }
}
