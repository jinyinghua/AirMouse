package com.shaun.airmouse

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.content.ContextCompat

class PointerOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val pointerView: View = View(context).apply {
        // 设置背景为一个半透明的小圆点，初始透明度较低
        background = ContextCompat.getDrawable(context, R.drawable.pointer_shape)
        alpha = 0.3f // 初始透明度较低
    }

    private val params = WindowManager.LayoutParams(
        40, // 宽度 (像素)
        40, // 高度 (像素)
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
    }

    private var isShowing = false

    fun show() {
        if (!isShowing) {
            windowManager.addView(pointerView, params)
            isShowing = true
        }
    }

    fun hide() {
        if (isShowing) {
            windowManager.removeView(pointerView)
            isShowing = false
        }
    }

    fun updatePosition(x: Int, y: Int) {
        if (isShowing) {
            params.x = x - 20 // 居中
            params.y = y - 20 // 居中
            windowManager.updateViewLayout(pointerView, params)
        }
    }

    fun setAlpha(alpha: Float) {
        if (isShowing) {
            pointerView.alpha = alpha
        }
    }
}
