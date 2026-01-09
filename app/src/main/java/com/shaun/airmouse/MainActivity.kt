package com.shaun.airmouse

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import rikka.shizuku.Shizuku
import rikka.sui.Sui

class MainActivity : AppCompatActivity(), Shizuku.OnRequestPermissionResultListener {

    companion object {
        const val PREFS_NAME = "AirMousePrefs"
        const val KEY_SENSITIVITY = "sensitivity"
        const val DEFAULT_SENSITIVITY = 1.0f
        const val SENSITIVITY_MIN = 0.5f
        const val SENSITIVITY_MAX = 3.0f
        const val SENSITIVITY_STEP = 0.1f
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                checkOverlayPermission()
            } else {
                Toast.makeText(this, "需要相机和通知权限才能运行", Toast.LENGTH_SHORT).show()
            }
        }
    private var shizukuPermissionGranted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 初始化 Sui (Shizuku 的 Magisk 模块实现)
        Sui.init(packageName)
        
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        
        setupSensitivityControl()
        setupModeSelection()

        findViewById<Button>(R.id.btnStartService).setOnClickListener {
            startAirMouseService()
        }

        findViewById<Button>(R.id.btnStopService).setOnClickListener {
            stopAirMouseService()
        }

        findViewById<Button>(R.id.btnOpenDebug).setOnClickListener {
            val intent = Intent(this, DebugActivity::class.java)
            startActivity(intent)
        }

        createNotificationChannel()
        checkAndRequestPermissions()
        
        // 注册Shizuku权限监听器
        Shizuku.addRequestPermissionResultListener(this)
    }
    
    private fun setupSensitivityControl() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val tvSensitivity = findViewById<TextView>(R.id.tvSensitivity)
        val sbSensitivity = findViewById<SeekBar>(R.id.sbSensitivity)

        // SeekBar max is 25 (0.5 to 3.0, step 0.1 -> 25 steps)
        val maxProgress = ((SENSITIVITY_MAX - SENSITIVITY_MIN) / SENSITIVITY_STEP).toInt()
        sbSensitivity.max = maxProgress

        // 加载保存的灵敏度值
        val savedSensitivity = prefs.getFloat(KEY_SENSITIVITY, DEFAULT_SENSITIVITY)
        val initialProgress = ((savedSensitivity - SENSITIVITY_MIN) / SENSITIVITY_STEP).toInt()
        sbSensitivity.progress = initialProgress
        tvSensitivity.text = getString(R.string.sensitivity_label, savedSensitivity)

        sbSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // 计算灵敏度值: 0.5 + progress * 0.1
                val sensitivity = SENSITIVITY_MIN + progress * SENSITIVITY_STEP
                tvSensitivity.text = getString(R.string.sensitivity_label, sensitivity)

                // 保存新值
                prefs.edit().putFloat(KEY_SENSITIVITY, sensitivity).apply()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
    
    private fun setupModeSelection() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rgMode = findViewById<RadioGroup>(R.id.rgMode)
        val currentMode = prefs.getString("input_mode", "shizuku")
        
        if (currentMode == "accessibility") {
            rgMode.check(R.id.rbAccessibility)
        } else {
            rgMode.check(R.id.rbShizuku)
        }
        
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.rbAccessibility) "accessibility" else "shizuku"
            prefs.edit().putString("input_mode", mode).apply()
            
            if (mode == "accessibility") {
                checkAccessibilityPermission()
            } else {
                checkShizukuPermission()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "AirMouseServiceChannel"
            val serviceChannel = NotificationChannel(
                channelId,
                "AirMouse Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needsRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needsRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(needsRequest.toTypedArray())
        } else {
            checkOverlayPermission()
        }
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            // Check based on current mode
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getString("input_mode", "shizuku") == "shizuku") {
                checkShizukuPermission()
            } else {
                checkAccessibilityPermission()
            }
        }
    }

    private fun checkShizukuPermission(code: Int = 0): Boolean {
        if (Shizuku.isPreV11()) {
            // 不支持 v11 以下版本
            Toast.makeText(this, "不支持当前 Shizuku 版本", Toast.LENGTH_SHORT).show()
            return false
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            // 已授权
            return true
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            // 用户选择了“拒绝且不再询问”
            Toast.makeText(this, "Shizuku 权限被拒绝且不再询问，请手动授权", Toast.LENGTH_LONG).show()
            return false
        } else {
            // 请求权限
            Shizuku.requestPermission(code)
            return false
        }
    }
    
    private fun checkAccessibilityPermission(): Boolean {
        val expectedServiceName = "$packageName/${AirMouseAccessibilityService::class.java.canonicalName}"
        val expectedServiceNameShort = "$packageName/.AirMouseAccessibilityService"
        
        val accessibilityEnabled = Settings.Secure.getInt(
            contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED, 0
        )
        
        if (accessibilityEnabled == 1) {
            val settingValue = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (settingValue != null) {
                val splitter = android.text.TextUtils.SimpleStringSplitter(':')
                splitter.setString(settingValue)
                while (splitter.hasNext()) {
                    val accessibilityService = splitter.next()
                    if (accessibilityService.equals(expectedServiceName, ignoreCase = true) ||
                        accessibilityService.equals(expectedServiceNameShort, ignoreCase = true)) {
                        return true
                    }
                }
            }
        }
        
        Toast.makeText(this, "请开启 AirMouse 无障碍服务", Toast.LENGTH_LONG).show()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        return false
    }

    private fun startAirMouseService() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "需要悬浮窗权限", Toast.LENGTH_SHORT).show()
            checkOverlayPermission()
            return
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "需要相机权限", Toast.LENGTH_SHORT).show()
            checkAndRequestPermissions()
            return
        }
        
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getString("input_mode", "shizuku")
        
        if (mode == "shizuku") {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "请先启动 Shizuku 应用", Toast.LENGTH_LONG).show()
                return
            }
            if (!checkShizukuPermission(0)) {
                return
            }
        } else {
            if (!checkAccessibilityPermission()) {
                return
            }
        }
        
        val intent = Intent(this, AirMouseService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopAirMouseService() {
        val intent = Intent(this, AirMouseService::class.java)
        stopService(intent)
        Toast.makeText(this, "服务已停止", Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        shizukuPermissionGranted = (grantResult == PackageManager.PERMISSION_GRANTED)
        if (shizukuPermissionGranted) {
            Toast.makeText(this, "Shizuku权限已授予", Toast.LENGTH_SHORT).show()
            // 权限授予后，如果用户之前尝试启动服务，现在自动启动
            checkAndStartServiceIfReady()
        } else {
            Toast.makeText(this, "Shizuku权限被拒绝，请手动授予", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkAndStartServiceIfReady() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getString("input_mode", "shizuku")
        
        val isModeReady = if (mode == "shizuku") {
            try {
                Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) { false }
        } else {
            // 这里简化判断，因为无障碍权限通常需要跳转设置页
            false 
        }

        if (Settings.canDrawOverlays(this) && 
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            isModeReady) {
            
            val intent = Intent(this, AirMouseService::class.java)
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, "权限已就绪，服务已自动启动", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 移除Shizuku权限监听器
        Shizuku.removeRequestPermissionResultListener(this)
    }
}
