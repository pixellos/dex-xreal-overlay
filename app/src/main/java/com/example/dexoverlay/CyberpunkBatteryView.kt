package com.example.dexoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class CyberpunkBatteryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var batteryLevel: Int = 100 // 0-100
    private var isCharging: Boolean = false

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val path = Path()

    fun updateBattery(level: Int, charging: Boolean) {
        this.batteryLevel = level
        this.isCharging = charging
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val spacing = 8f
        val segmentWidth = (w - (spacing * 4)) / 5f
        val notchSize = h * 0.25f

        val color = when {
            isCharging -> Color.parseColor("#00FF66") // Neon Green
            batteryLevel <= 20 -> Color.parseColor("#FF0055") // Neon Red
            else -> Color.parseColor("#00E5FF") // Neon Cyan
        }

        strokePaint.color = color
        fillPaint.color = color

        for (i in 0 until 5) {
            val left = i * (segmentWidth + spacing)
            val right = left + segmentWidth
            
            // Draw Segment Path (Cyberpunk Notched Style)
            path.reset()
            path.moveTo(left, 0f)
            path.lineTo(right - notchSize, 0f)
            path.lineTo(right, notchSize)
            path.lineTo(right, h)
            path.lineTo(left + notchSize, h)
            path.lineTo(left, h - notchSize)
            path.close()

            // 1. Draw Outline
            strokePaint.alpha = 255
            canvas.drawPath(path, strokePaint)

            // 2. Draw Fill based on level
            // i=0 (0-20%), i=1 (21-40%), etc.
            val segmentMin = i * 20
            val segmentMax = (i + 1) * 20
            
            if (batteryLevel > segmentMin) {
                val fillRatio = ((batteryLevel - segmentMin) / 20f).coerceIn(0f, 1f)
                
                // We clip the canvas to the notched path and draw a rectangle inside to simulate partial fill
                canvas.save()
                canvas.clipPath(path)
                
                // For a truly "Cyberpunk" look, we fill from left to right within the segment
                val fillRight = left + (segmentWidth * fillRatio)
                canvas.drawRect(left, 0f, fillRight, h, fillPaint)
                
                canvas.restore()
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Default size if not specified
        val desiredWidth = 120
        val desiredHeight = 40
        
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val width = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> desiredWidth.coerceAtMost(widthSize)
            else -> desiredWidth
        }

        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> desiredHeight.coerceAtMost(heightSize)
            else -> desiredHeight
        }

        setMeasuredDimension(width, height)
    }
}
