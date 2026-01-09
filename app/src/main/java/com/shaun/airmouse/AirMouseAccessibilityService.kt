package com.shaun.airmouse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch

class AirMouseAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: AirMouseAccessibilityService? = null

        fun getInstance(): AirMouseAccessibilityService? {
            return instance
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("AirMouseAccessibility", "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to handle events, just perform actions
    }

    override fun onInterrupt() {
        Log.d("AirMouseAccessibility", "Service interrupted")
        instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d("AirMouseAccessibility", "Service destroyed")
    }

    fun performClick(x: Float, y: Float): Boolean {
        val path = Path()
        path.moveTo(x, y)
        val builder = GestureDescription.Builder()
        val gestureDescription = builder
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        
        return dispatchGesture(gestureDescription, null, null)
    }

    fun performLongPress(x: Float, y: Float): Boolean {
        val path = Path()
        path.moveTo(x, y)
        val builder = GestureDescription.Builder()
        val gestureDescription = builder
            .addStroke(GestureDescription.StrokeDescription(path, 0, 1000))
            .build()
        
        return dispatchGesture(gestureDescription, null, null)
    }

    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): Boolean {
        val path = Path()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        val builder = GestureDescription.Builder()
        val gestureDescription = builder
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        
        return dispatchGesture(gestureDescription, null, null)
    }
}
