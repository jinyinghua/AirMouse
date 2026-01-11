package com.shaun.airmouse

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

// 1 Euro Filter implementation for smoothing real-time data
class OneEuroFilter(
    private val minCutoff: Float = 1.0f, // Minimum cutoff frequency, in Hz
    private val beta: Float = 0.0f,      // Speed parameter, higher beta means less lag but more jitter
    private val dCutoff: Float = 1.0f    // Cutoff frequency for derivative, usually minCutoff or higher
) {

    private var x: LowPassFilter = LowPassFilter()
    private var dx: LowPassFilter = LowPassFilter()
    private var lastTime: Long = -1

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1.0f / (2 * Math.PI.toFloat() * cutoff)
        return 1.0f / (1.0f + tau / dt)
    }

    fun filter(value: Float, timestamp: Long): Float {
        if (lastTime == -1L) {
            lastTime = timestamp
            return x.filter(value, alpha(minCutoff, 1.0f)) // Initial alpha can be 1.0f for first value
        }

        val dt = (timestamp - lastTime) / 1000.0f // Convert milliseconds to seconds
        lastTime = timestamp

        // Estimate the derivative of the signal
        val dValue = (value - x.lastValue()) / dt
        val edValue = dx.filter(dValue, alpha(dCutoff, dt))

        // Use the derivative to adjust the cutoff frequency
        val cutoff = minCutoff + beta * Math.abs(edValue)
        
        return x.filter(value, alpha(cutoff, dt))
    }

    private inner class LowPassFilter {
        private var lastFilteredValue: Float = 0.0f
        private var firstTime: Boolean = true

        fun filter(value: Float, alpha: Float): Float {
            if (firstTime) {
                lastFilteredValue = value
                firstTime = false
            } else {
                lastFilteredValue = alpha * value + (1.0f - alpha) * lastFilteredValue
            }
            return lastFilteredValue
        }

        fun lastValue(): Float {
            return lastFilteredValue
        }
    }
}
