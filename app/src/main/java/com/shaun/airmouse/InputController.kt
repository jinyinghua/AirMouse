package com.shaun.airmouse

import android.util.Log
import rikka.shizuku.Shizuku

interface InputController {
    fun click(x: Float, y: Float)
    fun longPress(x: Float, y: Float)
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long)
    fun isReady(): Boolean
}

class ShizukuInputController : InputController {
    override fun click(x: Float, y: Float) {
        executeShizukuCommand("input tap $x $y")
    }

    override fun longPress(x: Float, y: Float) {
        executeShizukuCommand("input swipe $x $y $x $y 1000")
    }

    override fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        executeShizukuCommand("input swipe $x1 $y1 $x2 $y2 $duration")
    }

    override fun isReady(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    private fun executeShizukuCommand(command: String) {
        try {
            val process = Shizuku.newProcess(command.split(" ").toTypedArray(), null, null)
            val result = process.waitFor()
            if (result != 0) {
                Log.e("ShizukuInput", "Command failed: $command, code: $result")
            }
        } catch (e: Exception) {
            Log.e("ShizukuInput", "Execution error: ${e.message}")
        }
    }
}

class AccessibilityInputController : InputController {
    override fun click(x: Float, y: Float) {
        AirMouseAccessibilityService.getInstance()?.performClick(x, y)
    }

    override fun longPress(x: Float, y: Float) {
        AirMouseAccessibilityService.getInstance()?.performLongPress(x, y)
    }

    override fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        AirMouseAccessibilityService.getInstance()?.performSwipe(x1, y1, x2, y2, duration)
    }

    override fun isReady(): Boolean {
        return AirMouseAccessibilityService.getInstance() != null
    }
}
