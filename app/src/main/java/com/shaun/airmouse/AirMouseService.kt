package com.shaun.airmouse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import kotlin.math.sqrt
import kotlin.math.pow
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import rikka.shizuku.Shizuku
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class AirMouseService : LifecycleService(), HandGestureAnalyzer.GestureListener {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var pointerOverlay: PointerOverlay
    private lateinit var gestureAnalyzer: HandGestureAnalyzer
    private val CHANNEL_ID = "AirMouseServiceChannel"

    private var screenWidth = 0
    private var screenHeight = 0

    // 状态记录
    private var lastPinchTime = 0L
    private var isPinching = false
    private var lastX = 0f
    private var lastY = 0f
    private var pinchStartX = 0f
    private var pinchStartY = 0f
    private val deadzoneThreshold = 10f // 像素
    private val swipeThreshold = 60f // 提高阈值，减少误触
    private val clickOffset = 15 // 像素，用于修正点击偏上的问题

    // 频率控制
    private var lastAnalysisTime = 0L
    private val analysisInterval = 50L // 20fps (1000ms/20)

    override fun onCreate() {
        super.onCreate()
        pointerOverlay = PointerOverlay(this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        gestureAnalyzer = HandGestureAnalyzer(this, this)
        updateScreenMetrics()
    }

    private fun updateScreenMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            wm.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        createNotificationChannel()
        val notification = createNotification()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(1, notification)
        }

        pointerOverlay.show()
        startCamera()
        
        return START_STICKY
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val currentTime = SystemClock.uptimeMillis()
                if (currentTime - lastAnalysisTime >= analysisInterval) {
                    lastAnalysisTime = currentTime
                    
                    val bitmap = imageProxy.toBitmap()
                    // 移除镜像处理，直接使用原始位图
                    gestureAnalyzer.analyze(bitmap, currentTime)
                }
                imageProxy.close()
            }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis)
                Log.d("AirMouseService", "Camera started successfully")
            } catch (exc: Exception) {
                Log.e("AirMouseService", "Use case binding failed", exc)
                onError("相机启动失败: ${exc.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "AirMouse Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AirMouse 正在运行")
            .setContentText("手势控制已启用")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    override fun onGestureUpdate(x: Float, y: Float, isPinching: Boolean, distance: Float) {
        // 坐标映射 (修正传感器旋转并处理镜像)
        // 摄像头的 X 对应屏幕的 Y，摄像头的 Y 对应屏幕的 X (且需镜像处理)
        val targetX = (1 - y) * screenWidth
        val targetY = (1 - x) * screenHeight

        // 立即更新坐标，确保点击逻辑使用的是最新位置
        // 如果是第一次识别到坐标（lastX/lastY 为 0），则直接赋值
        if (lastX == 0f && lastY == 0f) {
            lastX = targetX
            lastY = targetY
        }

        // 防抖逻辑 (Deadzone)
        val hasMovedSignificantly = abs(targetX - lastX) > deadzoneThreshold || abs(targetY - lastY) > deadzoneThreshold
        if (hasMovedSignificantly) {
            lastX = targetX
            lastY = targetY
            
            // 切换到主线程更新 UI
            Handler(Looper.getMainLooper()).post {
                try {
                    pointerOverlay.updatePosition(targetX.toInt(), targetY.toInt())
                } catch (e: Exception) {
                    Log.e("AirMouseService", "Update position failed", e)
                }
            }
        }

        // 动作触发逻辑
        if (isPinching && !this.isPinching) {
            // 开始捏合
            lastPinchTime = SystemClock.uptimeMillis()
            pinchStartX = lastX
            pinchStartY = lastY
        } else if (!isPinching && this.isPinching) {
            // 结束捏合
            val duration = SystemClock.uptimeMillis() - lastPinchTime
            val currentX = lastX
            val currentY = lastY
            
            // 计算位移
            val moveDistance = sqrt(
                (currentX - pinchStartX).toDouble().pow(2.0) +
                (currentY - pinchStartY).toDouble().pow(2.0)
            ).toFloat()

            if (moveDistance > swipeThreshold) {
                // 位移足够大，判定为滑动
                // 滑动起点使用捏合时的坐标，终点使用释放时的坐标
                performSwipe(pinchStartX.toInt(), pinchStartY.toInt(), currentX.toInt(), currentY.toInt())
            } else {
                // 位移小，判定为点击或长按
                // 使用起始坐标进行点击，防止松开时的位移导致点击不准
                if (duration < 500) {
                    performClick(pinchStartX.toInt(), pinchStartY.toInt() + clickOffset)
                } else {
                    performLongPress(pinchStartX.toInt(), pinchStartY.toInt() + clickOffset)
                }
            }
        }
        this.isPinching = isPinching
    }

    private fun performClick(x: Int, y: Int) {
        if (!Shizuku.pingBinder()) {
            Log.e("AirMouseService", "Shizuku is not running")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this@AirMouseService, "Shizuku未运行，请检查", Toast.LENGTH_LONG).show()
            }
            return
        }
        
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.e("AirMouseService", "Shizuku permission not granted")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this@AirMouseService, "Shizuku权限被拒绝", Toast.LENGTH_LONG).show()
            }
            return
        }
        
        cameraExecutor.execute {
            try {
                Log.d("AirMouseService", "Executing: input tap $x $y")
                val process = Shizuku.newProcess(arrayOf("input", "tap", x.toString(), y.toString()), null, null)
                val result = process.waitFor()
                Log.d("AirMouseService", "Click command result: $result")
                if (result != 0) {
                    Log.e("AirMouseService", "Click command failed with exit code $result")
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this@AirMouseService, "点击执行失败", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("AirMouseService", "Perform click failed", e)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this@AirMouseService, "点击执行异常: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun performLongPress(x: Int, y: Int) {
        if (!Shizuku.pingBinder()) {
            Log.e("AirMouseService", "Shizuku is not running for long press")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this@AirMouseService, "Shizuku未运行，无法执行长按", Toast.LENGTH_LONG).show()
            }
            return
        }
        
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.e("AirMouseService", "Shizuku permission not granted for long press")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this@AirMouseService, "Shizuku权限被拒绝，无法执行长按", Toast.LENGTH_LONG).show()
            }
            return
        }
        
        cameraExecutor.execute {
            try {
                Log.d("AirMouseService", "Performing long press at $x, $y")
                // 模拟长按：在同一点滑动，持续时间较长
                val process = Shizuku.newProcess(
                    arrayOf("input", "swipe", x.toString(), y.toString(), x.toString(), y.toString(), "1000"),
                    null,
                    null
                )
                val result = process.waitFor()
                if (result != 0) {
                    Log.e("AirMouseService", "Long press command failed with exit code $result")
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this@AirMouseService, "长按执行失败", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("AirMouseService", "Perform long press failed", e)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this@AirMouseService, "长按执行异常: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun performSwipe(x1: Int, y1: Int, x2: Int, y2: Int) {
        if (!Shizuku.pingBinder()) {
            Log.e("AirMouseService", "Shizuku is not running for swipe")
            return
        }
        
        cameraExecutor.execute {
            try {
                Log.d("AirMouseService", "Executing swipe: $x1 $y1 to $x2 $y2")
                val process = Shizuku.newProcess(
                    arrayOf("input", "swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString(), "300"),
                    null,
                    null
                )
                val result = process.waitFor()
                if (result != 0) {
                    Log.e("AirMouseService", "Swipe command failed")
                }
            } catch (e: Exception) {
                Log.e("AirMouseService", "Perform swipe failed", e)
            }
        }
    }

    override fun onError(error: String) {
        Log.e("AirMouseService", "Error: $error")
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pointerOverlay.hide()
        gestureAnalyzer.close()
        cameraExecutor.shutdown()
    }
}
