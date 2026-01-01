package com.shaun.airmouse

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

class DebugActivity : AppCompatActivity(), Shizuku.OnRequestPermissionResultListener {

    private lateinit var etX: EditText
    private lateinit var etY: EditText
    private lateinit var btnStartTest: Button
    private lateinit var btnCheckPermissions: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)

        // 注册 Shizuku 权限监听器
        Shizuku.addRequestPermissionResultListener(this)

        etX = findViewById(R.id.etX)
        etY = findViewById(R.id.etY)
        btnStartTest = findViewById(R.id.btnStartTest)
        btnCheckPermissions = findViewById(R.id.btnCheckPermissions)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)

        btnStartTest.setOnClickListener {
            val x = etX.text.toString().toIntOrNull()
            val y = etY.text.toString().toIntOrNull()

            if (x == null || y == null) {
                tvStatus.text = "请输入有效的坐标"
                return@setOnClickListener
            }

            startCountdown(x, y)
        }

        btnCheckPermissions.setOnClickListener {
            checkAllPermissions()
        }
    }

    private fun checkShizukuPermission(code: Int): Boolean {
        if (Shizuku.isPreV11()) {
            return false
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            return true
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            return false
        } else {
            Shizuku.requestPermission(code)
            return false
        }
    }

    private fun checkAllPermissions() {
        tvLog.text = ""
        appendLog("🔍 开始检查所有权限状态...")
        
        // 检查相机权限
        val cameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        if (cameraPermission == PackageManager.PERMISSION_GRANTED) {
            appendLog("✅ 相机权限: 已授予")
        } else {
            appendLog("❌ 相机权限: 未授予")
        }

        // 检查通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notificationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            if (notificationPermission == PackageManager.PERMISSION_GRANTED) {
                appendLog("✅ 通知权限: 已授予")
            } else {
                appendLog("❌ 通知权限: 未授予")
            }
        } else {
            appendLog("ℹ️ 通知权限: Android版本低于13，不需要单独授予")
        }

        // 检查悬浮窗权限
        if (Settings.canDrawOverlays(this)) {
            appendLog("✅ 悬浮窗权限: 已授予")
        } else {
            appendLog("❌ 悬浮窗权限: 未授予")
        }

        // 检查Shizuku状态
        if (Shizuku.pingBinder()) {
            appendLog("✅ Shizuku运行状态: 正常")
            
            if (Shizuku.isPreV11()) {
                appendLog("❌ Shizuku版本太低，不支持 v11 以下版本")
            } else {
                val shizukuPermission = Shizuku.checkSelfPermission()
                if (shizukuPermission == PackageManager.PERMISSION_GRANTED) {
                    appendLog("✅ Shizuku权限: 已授予")
                } else {
                    appendLog("❌ Shizuku权限: 未授予 (权限码: $shizukuPermission)")
                    if (Shizuku.shouldShowRequestPermissionRationale()) {
                        appendLog("💡 用户选择了“拒绝且不再询问”，请前往系统设置手动授权")
                    } else {
                        appendLog("🚀 正在请求 Shizuku 权限...")
                        Shizuku.requestPermission(0)
                    }
                }
            }

            try {
                val version = Shizuku.getVersion()
                appendLog("ℹ️ Shizuku版本: $version")
            } catch (e: Exception) {
                appendLog("⚠️ 无法获取Shizuku版本: ${e.message}")
            }
        } else {
            appendLog("❌ Shizuku运行状态: 未运行")
        }

        // 总结
        val allPermissionsGranted = 
            cameraPermission == PackageManager.PERMISSION_GRANTED &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || 
             ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            Settings.canDrawOverlays(this) &&
            Shizuku.pingBinder() &&
            !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

        if (allPermissionsGranted) {
            appendLog("🎉 所有权限已就绪，可以正常使用AirMouse功能！")
            tvStatus.text = "权限状态: 完整"
        } else {
            appendLog("⚠️ 部分权限缺失，可能影响功能使用")
            tvStatus.text = "权限状态: 不完整"
        }
    }

    private fun startCountdown(x: Int, y: Int) {
        btnStartTest.isEnabled = false
        object : CountDownTimer(10000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                tvStatus.text = "倒计时: ${millisUntilFinished / 1000}s"
            }

            override fun onFinish() {
                tvStatus.text = "正在执行点击..."
                executeTap(x, y)
                btnStartTest.isEnabled = true
            }
        }.start()
    }

    private fun executeTap(x: Int, y: Int) {
        try {
            // 清空日志
            tvLog.text = ""
            
            // 检查Shizuku运行状态
            if (!Shizuku.pingBinder()) {
                appendLog("❌ 错误: Shizuku 未运行")
                appendLog("💡 解决方案: 请先启动 Shizuku 应用")
                tvStatus.text = "Shizuku 未运行"
                return
            }
            appendLog("✅ Shizuku 运行状态: 正常")

            // 检查权限状态
            if (!checkShizukuPermission(0)) {
                appendLog("❌ 错误: Shizuku 权限未授予")
                tvStatus.text = "Shizuku 权限未授予"
                return
            }
            appendLog("✅ Shizuku 权限状态: 已授予")

            // 获取Shizuku版本信息
            try {
                val version = Shizuku.getVersion()
                appendLog("ℹ️ Shizuku 版本: $version")
            } catch (e: Exception) {
                appendLog("⚠️ 无法获取 Shizuku 版本: ${e.message}")
            }

            // 执行点击命令
            appendLog("🚀 执行点击命令: input tap $x $y")
            val process = Shizuku.newProcess(arrayOf("input", "tap", x.toString(), y.toString()), null, null)
            
            // 读取输出
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            
            val errorOutput = StringBuilder()
            while (errorReader.readLine().also { line = it } != null) {
                errorOutput.append(line).append("\n")
            }

            val exitCode = process.waitFor()
            appendLog("📊 命令执行完成")
            appendLog("📋 退出码: $exitCode")
            
            if (output.isNotEmpty()) {
                appendLog("📄 标准输出:\n$output")
            }
            
            if (errorOutput.isNotEmpty()) {
                appendLog("⚠️ 错误输出:\n$errorOutput")
            }

            if (exitCode == 0) {
                appendLog("✅ 成功执行点击 ($x, $y)")
                tvStatus.text = "点击执行成功"
            } else {
                appendLog("❌ 执行失败 (退出码: $exitCode)")
                appendLog("💡 可能原因: 系统限制、权限不足或坐标无效")
                tvStatus.text = "点击执行失败"
            }
        } catch (e: Exception) {
            appendLog("💥 异常: ${e.message}")
            appendLog("📋 异常堆栈:\n${e.stackTraceToString()}")
            tvStatus.text = "出现异常"
        }
    }

    private fun appendLog(message: String) {
        val currentLog = tvLog.text.toString()
        tvLog.text = "$currentLog\n> $message"
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        if (granted) {
            appendLog("✅ Shizuku 权限请求成功！")
            checkAllPermissions()
        } else {
            appendLog("❌ Shizuku 权限请求被拒绝。")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(this)
    }
}
