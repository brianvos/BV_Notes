package com.bv.notes.util

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

class ColorWheelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }
    private val indicatorInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.BLACK
    }

    private var radius = 0f
    private var centerX = 0f
    private var centerY = 0f

    private var selectedX = 0f
    private var selectedY = 0f
    private var currentColor = Color.RED

    private var sweep: SweepGradient? = null
    private var radial: RadialGradient? = null

    var onColorSelected: ((Int) -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        centerX = w / 2f
        centerY = h / 2f
        radius = min(centerX, centerY) - 20f
        
        sweep = SweepGradient(centerX, centerY, 
            intArrayOf(Color.RED, Color.MAGENTA, Color.BLUE, Color.CYAN, Color.GREEN, Color.YELLOW, Color.RED),
            null
        )
        radial = RadialGradient(centerX, centerY, radius, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)

        // Initial indicator position
        updateIndicatorFromColor(currentColor)
    }

    override fun onDraw(canvas: Canvas) {
        // Draw Color Wheel
        paint.shader = sweep
        canvas.drawCircle(centerX, centerY, radius, paint)

        // Draw radial gradient for saturation/value (white in middle)
        paint.shader = radial
        canvas.drawCircle(centerX, centerY, radius, paint)

        // Draw indicator
        canvas.drawCircle(selectedX, selectedY, 15f, indicatorPaint)
        canvas.drawCircle(selectedX, selectedY, 13f, indicatorInnerPaint)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x - centerX
        val y = event.y - centerY
        val d = sqrt(x * x + y * y)

        if (d <= radius || event.action == MotionEvent.ACTION_MOVE) {
            if (event.action == MotionEvent.ACTION_UP) {
                performClick()
            }
            val clampedD = min(d, radius)
            val angle = atan2(y, x)
            selectedX = centerX + clampedD * cos(angle)
            selectedY = centerY + clampedD * sin(angle)
            
            currentColor = getColorAt(selectedX, selectedY)
            onColorSelected?.invoke(currentColor)
            invalidate()
            return true
        }
        return super.onTouchEvent(event)
    }

    private fun getColorAt(x: Float, y: Float): Int {
        val dx = x - centerX
        val dy = y - centerY
        val d = sqrt(dx * dx + dy * dy)
        val hue = (atan2(dy, dx) * 180 / PI).toFloat().let { if (it < 0) it + 360f else it }
        val sat = (d / radius).coerceIn(0f, 1f)
        return Color.HSVToColor(floatArrayOf(hue, sat, 1f))
    }

    fun setColor(color: Int) {
        currentColor = color
        if (radius > 0) {
            updateIndicatorFromColor(color)
            invalidate()
        }
    }

    private fun updateIndicatorFromColor(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val angle = (hsv[0] * PI / 180).toFloat()
        val dist = hsv[1] * radius
        selectedX = centerX + dist * cos(angle)
        selectedY = centerY + dist * sin(angle)
    }
}
