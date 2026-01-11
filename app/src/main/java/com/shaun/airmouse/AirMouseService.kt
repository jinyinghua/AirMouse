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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import rikka.shizuku.Shizuku
import java.util.concurrent.ExecutorService
import com.shaun.airmouse.MainActivity.Companion.DEFAULT_SENSITIVITY
import com.shaun.airmouse.MainActivity.Companion.KEY_SENSITIVITY
import com.shaun.airmouse.MainActivity.Companion.PREFS_NAME
import java.util.concurrent.Executors
import kotlin.math.abs

class AirMouseService : LifecycleService(), HandGestureAnalyzer.GestureListener {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var pointerOverlay: PointerOverlay
    private lateinit var gestureAnalyzer: HandGestureAnalyzer
    private lateinit var inputController: InputController
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
    private val deadzoneThreshold = 30f // 像素，提高阈值减少误触
    private val swipeThreshold = 60f // 提高阈值，减少误触
    private val clickOffset = 15 // 像素，用于修正点击偏上的问题

    // 1 Euro Filter 实例
    private lateinit var xFilter: OneEuroFilter
    private lateinit var yFilter: OneEuroFilter

    // 频率控制
    private var lastAnalysisTime = 0L
    private val analysisInterval = 50L // 20fps (1000ms/20)

    // 指针激活状态
    private var isPointerActivated = false
    
    // 新增：防抖逻辑相关变量
    private var isFirstGesture = true // 是否是第一次识别到手势
    private var initialGestureX = 0f // 初始手势X坐标
    private var initialGestureY = 0f // 初始手势Y坐标
    private var currentPointerX = 0f // 当前指针X坐标
    private var currentPointerY = 0f // 当前指针Y坐标
    
    // 修复跳动所需的新变量
    private var basePointerX = 0f // 手势开始时指针的起始基准坐标
    private var basePointerY = 0f // 手势开始时指针的起始基准坐标
    private var lastHandDetectedTime = 0L // 上次检测到手的时间
    private val handLossTimeout = 500L // 手势丢失超时时间 (ms)
    
    // 灵敏度乘数
    private var sensitivityMultiplier = DEFAULT_SENSITIVITY

    override fun onCreate() {
        super.onCreate()
        pointerOverlay = PointerOverlay(this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        gestureAnalyzer = HandGestureAnalyzer(this, this)
        updateScreenMetrics()

        // 初始化 1 Euro Filter
        xFilter = OneEuroFilter(minCutoff = 1.0f, beta = 0.5f, dCutoff = 1.0f)
        yFilter = OneEuroFilter(minCutoff = 1.0f, beta = 0.5f, dCutoff = 1.0f)

        // 初始化指针位置为屏幕中央
        currentPointerX = screenWidth / 2f
        currentPointerY = screenHeight / 2f
        
        loadSensitivity()
        setupInputController()
    }

    private fun loadSensitivity() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sensitivityMultiplier = prefs.getFloat(KEY_SENSITIVITY, DEFAULT_SENSITIVITY)
        Log.d("AirMouseService", "Loaded sensitivity: $sensitivityMultiplier")
    }
    
    private fun setupInputController() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getString("input_mode", "shizuku")
        
        inputController = if (mode == "accessibility") {
            AccessibilityInputController()
        } else {
            ShizukuInputController()
        }
        
        Log.d("AirMouseService", "Input controller initialized: $mode")
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
        
        // 更新指针位置为屏幕中央
        currentPointerX = screenWidth / 2f
        currentPointerY = screenHeight / 2f
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

        pointerOverlay.show() // 先显示悬浮窗，但初始透明度较低
        
        // 初始化指针位置为屏幕中央
        Handler(Looper.getMainLooper()).post {
            pointerOverlay.updatePosition(currentPointerX.toInt(), currentPointerY.toInt())
        }
        
        startCamera()
        
        // 发送服务运行状态广播
        sendServiceStatus(true)

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

                // 摄像头成功绑定后，显示就绪提示
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this@AirMouseService, "AirMouse 服务已就绪", Toast.LENGTH_SHORT).show()
                }
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
        val currentTime = SystemClock.uptimeMillis()
        
        // 更新上次检测到手的时间
        lastHandDetectedTime = currentTime

        // 激活指针（检测到手势时调高透明度）
        if (!isPointerActivated || isFirstGesture) {
            isPointerActivated = true
            Handler(Looper.getMainLooper()).post {
                pointerOverlay.setAlpha(0.8f) // 调高透明度表示已激活
            }
        }

        // 坐标映射 (修正传感器旋转并处理镜像)
        // 摄像头的 X 对应屏幕的 Y，摄像头的 Y 对应屏幕的 X (且需镜像处理)
        val targetX = (1 - y) * screenWidth
        val targetY = (1 - x) * screenHeight

        // 1. 应用 1 Euro Filter 平滑原始手势坐标
        val smoothedHandX = xFilter.filter(targetX, currentTime)
        val smoothedHandY = yFilter.filter(targetY, currentTime)

        // 2. 防抖逻辑（使用平滑后的坐标）
        if (isFirstGesture) {
            // 第一次识别到手势（或手势丢失后重新识别），记录初始基准
            initialGestureX = smoothedHandX // 使用平滑后的坐标作为初始锚点
            initialGestureY = smoothedHandY
            basePointerX = currentPointerX // 记录当前指针位置作为移动基准
            basePointerY = currentPointerY
            
            lastX = smoothedHandX // 使用平滑后的坐标用于点击逻辑
            lastY = smoothedHandY
            isFirstGesture = false
            Log.d("AirMouseService", "Gesture anchor set. Smoothed Hand: ($initialGestureX, $initialGestureY), Pointer: ($basePointerX, $basePointerY)")
        } else {
            // 计算手势相对于初始位置的位移 (应用灵敏度乘数)
            val gestureDeltaX = (smoothedHandX - initialGestureX) * sensitivityMultiplier
            val gestureDeltaY = (smoothedHandY - initialGestureY) * sensitivityMultiplier
            
            // 计算手势移动距离
            val gestureMoveDistance = sqrt(gestureDeltaX.pow(2) + gestureDeltaY.pow(2))
            
            if (gestureMoveDistance > deadzoneThreshold) {
                // 手势移动超过阈值，开始跟随移动
                // 计算指针应该移动到的位置（起始基准位置 + 手势位移）
                val newPointerX = basePointerX + gestureDeltaX
                val newPointerY = basePointerY + gestureDeltaY
                
                // 确保指针在屏幕范围内
                val clampedX = newPointerX.coerceIn(0f, screenWidth.toFloat())
                val clampedY = newPointerY.coerceIn(0f, screenHeight.toFloat())
                
                // 更新指针位置
                currentPointerX = clampedX
                currentPointerY = clampedY
                
                // 切换到主线程更新 UI
                Handler(Looper.getMainLooper()).post {
                    try {
                        pointerOverlay.updatePosition(currentPointerX.toInt(), currentPointerY.toInt())
                    } catch (e: Exception) {
                        Log.e("AirMouseService", "Update position failed", e)
                    }
                }
                
                // 更新lastX/lastY用于点击逻辑
                lastX = smoothedHandX
                lastY = smoothedHandY
            } else {
                // 手势移动未超过阈值，指针保持当前位置
                // 但更新lastX/lastY用于点击逻辑
                lastX = smoothedHandX
                lastY = smoothedHandY
            }
        }

        // 动作触发逻辑
        if (isPinching && !this.isPinching) {
            // 开始捏合
            lastPinchTime = SystemClock.uptimeMillis()
            pinchStartX = currentPointerX // 使用当前指针位置
            pinchStartY = currentPointerY
        } else if (!isPinching && this.isPinching) {
            // 结束捏合
            val duration = SystemClock.uptimeMillis() - lastPinchTime
            val currentX = currentPointerX // 使用当前指针位置
            val currentY = currentPointerY
            
            // 计算位移
            val moveDistance = sqrt(
                (currentX - pinchStartX).toDouble().pow(2.0) +
                (currentY - pinchStartY).toDouble().pow(2.0)
            ).toFloat()

            if (moveDistance > swipeThreshold) {
                // 位移足够大，判定为滑动
                performSwipe(pinchStartX.toInt(), pinchStartY.toInt(), currentX.toInt(), currentY.toInt())
            } else {
                // 位移小，判定为点击或长按
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
        if (!inputController.isReady()) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this@AirMouseService, "输入服务未就绪，请检查权限或服务开启状态", Toast.LENGTH_SHORT).show()
            }
            return
        }
        
        cameraExecutor.execute {
            try {
                inputController.click(x.toFloat(), y.toFloat())
            } catch (e: Exception) {
                Log.e("AirMouseService", "Perform click failed", e)
            }
        }
    }

    private fun performLongPress(x: Int, y: Int) {
        if (!inputController.isReady()) {
            return
        }
        
        cameraExecutor.execute {
            try {
                inputController.longPress(x.toFloat(), y.toFloat())
            } catch (e: Exception) {
                Log.e("AirMouseService", "Perform long press failed", e)
            }
        }
    }

    private fun performSwipe(x1: Int, y1: Int, x2: Int, y2: Int) {
        if (!inputController.isReady()) {
            return
        }
        
        cameraExecutor.execute {
            try {
                inputController.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), 300)
            } catch (e: Exception) {
                Log.e("AirMouseService", "Perform swipe failed", e)
            }
        }
    }

    override fun onHandLost() {
        // 无论手势丢失多久，都将 isFirstGesture 设为 true，确保下次识别到手时重置锚点，防止跳动
        isFirstGesture = true
        
        val currentTime = SystemClock.uptimeMillis()
        // 如果手势丢失超过超时时间，标记为非激活状态，并降低指针透明度
        if (currentTime - lastHandDetectedTime > handLossTimeout) {
            if (isPointerActivated) {
                isPointerActivated = false
                Handler(Looper.getMainLooper()).post {
                    pointerOverlay.setAlpha(0.3f) // 降低透明度表示失去追踪
                }
                Log.d("AirMouseService", "Hand lost timeout triggered, deactivating pointer")
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
        
        // 重置状态
        isFirstGesture = true
        isPointerActivated = false
        
        // 发送服务停止状态广播
        sendServiceStatus(false)
    }

    private fun sendServiceStatus(isRunning: Boolean) {
        val intent = Intent("com.shaun.airmouse.SERVICE_STATUS")
        intent.putExtra("isRunning", isRunning)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
}
