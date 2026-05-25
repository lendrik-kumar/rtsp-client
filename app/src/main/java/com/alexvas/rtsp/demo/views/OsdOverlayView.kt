package com.alexvas.rtsp.demo.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.alexvas.rtsp.demo.data.TelemetryData
import java.util.*

/**
 * Professional Drone OSD Overlay.
 * Includes Horizon, Tapes (Speed, Alt, Compass), and Corner Data Blocks.
 */
class OsdOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var data: TelemetryData? = null

    // Visibility Flags
    var showOsd = true
        set(value) {
            field = value
            invalidate()
        }

    // Paints
    private val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
        alpha = 150
    }

    private val tapeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 35f
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
        isFakeBoldText = true
    }

    private val textSmallPaint = Paint(textPaint).apply {
        textSize = 28f
        color = Color.LTGRAY
    }

    private val warningPaint = Paint(textPaint).apply {
        color = Color.RED
    }

    private val safePaint = Paint(textPaint).apply {
        color = Color.GREEN
    }

    private val hudTapeBgPaint = Paint().apply {
        color = Color.BLACK
        alpha = 60
        style = Paint.Style.FILL
    }

    private val valueBoxPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        alpha = 180
    }

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }

    private val vsiPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        alpha = 200
    }

    // Dimensions
    private var widthF = 0f
    private var heightF = 0f
    private var centerX = 0f
    private var centerY = 0f

    // Configuration
    private val pitchPixelsPerDegree = 15f
    private val tapeHeight = 500f
    private val horizonLineWidth = 200f

    fun updateTelemetry(newData: TelemetryData) {
        this.data = newData
        if (showOsd) {
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        widthF = w.toFloat()
        heightF = h.toFloat()
        centerX = w / 2f
        centerY = h / 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentData = data ?: return
        if (!showOsd) return

        // --- Layer 1: Horizon ---
        drawSubtleHorizon(canvas, currentData)

        // --- Layer 2: Crosshair ---
        drawCrosshair(canvas)

        // --- Layer 3: Tapes ---
        drawSpeedTape(canvas, currentData)
        drawAltitudeTape(canvas, currentData)
        drawCompassTape(canvas, currentData)

        // --- Layer 4: Corner Info ---
        drawTopLeftPower(canvas, currentData)
        drawTopRightStatus(canvas, currentData)
        drawBottomLeftLocation(canvas, currentData)
        drawBottomRightPerformance(canvas, currentData)
    }

    private fun drawSubtleHorizon(canvas: Canvas, data: TelemetryData) {
        canvas.save()
        canvas.rotate(-data.roll, centerX, centerY)
        canvas.translate(0f, data.pitch * pitchPixelsPerDegree)

        // Horizon Line
        canvas.drawLine(centerX - horizonLineWidth, centerY, centerX + horizonLineWidth, centerY, horizonPaint)

        // Pitch Ladder
        for (i in -90..90 step 10) {
            if (i == 0) continue
            val y = centerY - (i * pitchPixelsPerDegree)
            if (y > centerY - tapeHeight && y < centerY + tapeHeight) {
                val len = if (i % 30 == 0) horizonLineWidth * 0.8f else horizonLineWidth * 0.5f
                canvas.drawLine(centerX - len, y, centerX + len, y, horizonPaint)

                // Numbers
                textSmallPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(i.toString(), centerX - len - 5f, y + 10f, textSmallPaint)
            }
        }
        canvas.restore()
    }

    private fun drawCrosshair(canvas: Canvas) {
        val len = 40f
        val gap = 15f
        canvas.drawLine(centerX - len, centerY, centerX - gap, centerY, crosshairPaint)
        canvas.drawLine(centerX + gap, centerY, centerX + len, centerY, crosshairPaint)
        canvas.drawPoint(centerX, centerY - gap, crosshairPaint)

        val bird = Path().apply {
            moveTo(centerX - 10f, centerY + 10f)
            lineTo(centerX, centerY)
            lineTo(centerX + 10f, centerY + 10f)
        }
        tapeLinePaint.style = Paint.Style.STROKE
        canvas.drawPath(bird, tapeLinePaint)
    }

    private fun drawSpeedTape(canvas: Canvas, data: TelemetryData) {
        val x = 80f
        val yStart = centerY - tapeHeight / 2f

        val bg = RectF(x - 60f, yStart, x + 10f, yStart + tapeHeight)
        canvas.drawRect(bg, hudTapeBgPaint)

        canvas.save()
        canvas.clipRect(bg)
        val pixelPerUnit = 40f
        val range = tapeHeight / pixelPerUnit / 2f

        for (i in (data.airspeed - range).toInt()..(data.airspeed + range).toInt()) {
            if (i < 0) continue
            val yPos = centerY + (data.airspeed - i) * pixelPerUnit
            if (i % 5 == 0) {
                canvas.drawLine(x, yPos, x - 20f, yPos, tapeLinePaint)
                textPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(i.toString(), x - 25f, yPos + 10f, textPaint)
            } else {
                canvas.drawLine(x, yPos, x - 10f, yPos, tapeLinePaint)
            }
        }
        canvas.restore()

        val valBox = RectF(x - 70f, centerY - 25f, x + 10f, centerY + 25f)
        canvas.drawRect(valBox, valueBoxPaint)
        canvas.drawRect(valBox, tapeLinePaint)
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(String.format(Locale.US, "%.0f", data.airspeed), valBox.centerX(), valBox.centerY() + 15f, textPaint)

        textSmallPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("SPD m/s", x - 60f, yStart - 10f, textSmallPaint)
    }

    private fun drawAltitudeTape(canvas: Canvas, data: TelemetryData) {
        val x = widthF - 80f
        val yStart = centerY - tapeHeight / 2f

        val bg = RectF(x - 10f, yStart, x + 60f, yStart + tapeHeight)
        canvas.drawRect(bg, hudTapeBgPaint)

        canvas.save()
        canvas.clipRect(bg)
        val pixelPerUnit = 20f
        val range = tapeHeight / pixelPerUnit / 2f

        val currentAlt = data.altitude.toInt()
        for (i in (currentAlt / 10 * 10 - (range * 10).toInt())..(currentAlt / 10 * 10 + (range * 10).toInt()) step 10) {
            val yPos = centerY + (data.altitude - i) * (pixelPerUnit / 10f)
            if (i % 50 == 0) {
                canvas.drawLine(x, yPos, x + 20f, yPos, tapeLinePaint)
                textPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(i.toString(), x + 25f, yPos + 10f, textPaint)
            } else {
                canvas.drawLine(x, yPos, x + 10f, yPos, tapeLinePaint)
            }
        }
        canvas.restore()

        val valBox = RectF(x - 10f, centerY - 25f, x + 70f, centerY + 25f)
        canvas.drawRect(valBox, valueBoxPaint)
        canvas.drawRect(valBox, tapeLinePaint)
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(String.format(Locale.US, "%.0f", data.altitude), valBox.centerX(), valBox.centerY() + 15f, textPaint)

        textSmallPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("ALT m", x + 60f, yStart - 10f, textSmallPaint)

        // --- VSI Bar ---
        val vsiScale = 20f
        var vsiHeight = data.verticalSpeed * vsiScale
        if (vsiHeight > 100) vsiHeight = 100f
        if (vsiHeight < -100) vsiHeight = -100f

        val vsiBar = if (data.verticalSpeed > 0) {
            RectF(x + 70f, centerY - vsiHeight, x + 80f, centerY)
        } else {
            RectF(x + 70f, centerY, x + 80f, centerY - vsiHeight)
        }
        canvas.drawRect(vsiBar, vsiPaint)
    }

    private fun drawCompassTape(canvas: Canvas, data: TelemetryData) {
        val tapeY = 30f
        val tapeH = 50f
        val tapeW = 500f

        val bg = RectF(centerX - tapeW / 2f, tapeY, centerX + tapeW / 2f, tapeY + tapeH)
        canvas.drawRect(bg, hudTapeBgPaint)

        canvas.save()
        canvas.clipRect(bg)
        val pixelsPerDeg = 8f

        for (i in 0 until 360 step 15) {
            var diff = i - data.yaw
            if (diff < -180) diff += 360f
            if (diff > 180) diff -= 360f
            val xPos = centerX + (diff * pixelsPerDeg)

            if (xPos > centerX - tapeW / 2f && xPos < centerX + tapeW / 2f) {
                canvas.drawLine(xPos, tapeY + tapeH, xPos, tapeY + tapeH - 10f, tapeLinePaint)
                val label = when (i) {
                    0 -> "N"
                    90 -> "E"
                    180 -> "S"
                    270 -> "W"
                    else -> i.toString()
                }
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(label, xPos, tapeY + 30f, textPaint)
            }
        }
        canvas.restore()

        val tri = Path().apply {
            moveTo(centerX, tapeY + tapeH + 5f)
            lineTo(centerX - 10f, tapeY + tapeH + 20f)
            lineTo(centerX + 10f, tapeY + tapeH + 20f)
            close()
        }
        val p = Paint().apply {
            color = Color.RED
            style = Paint.Style.FILL
        }
        canvas.drawPath(tri, p)
    }

    private fun drawTopLeftPower(canvas: Canvas, data: TelemetryData) {
        val x = 30f
        val y = 50f
        textPaint.textAlign = Paint.Align.LEFT

        val vol = String.format(Locale.US, "%.1fV", data.batteryVoltage)
        canvas.drawText(vol, x, y, textPaint)

        textSmallPaint.textAlign = Paint.Align.LEFT
        val cell = String.format(Locale.US, "%.2fV/cell", data.cellVoltage)
        canvas.drawText(cell, x, y + 35f, textSmallPaint)

        val pct = "${data.batteryPercent}%"
        val p = if (data.batteryPercent < 20) warningPaint else safePaint
        p.textAlign = Paint.Align.LEFT
        canvas.drawText(pct, x + 140f, y, p)
    }

    private fun drawTopRightStatus(canvas: Canvas, data: TelemetryData) {
        val x = widthF - 30f
        val y = 50f
        textPaint.textAlign = Paint.Align.RIGHT

        val statePaint = if (data.isArmed) warningPaint else safePaint
        statePaint.textAlign = Paint.Align.RIGHT
        val stateText = if (data.isArmed) "ARMED" else "DISARMED"
        canvas.drawText(stateText, x, y, statePaint)

        textSmallPaint.textAlign = Paint.Align.RIGHT
        val fixStr = when {
            data.gpsFixType == 2 -> "2D FIX"
            data.gpsFixType >= 3 -> "3D FIX"
            else -> "NO FIX"
        }
        val gps = String.format(Locale.US, "GPS: %s (%d)", fixStr, data.satelliteCount)
        canvas.drawText(gps, x, y + 35f, textSmallPaint)

        val min = data.flightTimeInSeconds / 60
        val sec = data.flightTimeInSeconds % 60
        val timeText = String.format(Locale.US, "TIME %02d:%02d", min, sec)
        canvas.drawText(timeText, x, y + 70f, textSmallPaint)
    }

    private fun drawBottomLeftLocation(canvas: Canvas, data: TelemetryData) {
        val x = 30f
        val y = heightF - 30f
        textSmallPaint.textAlign = Paint.Align.LEFT
        val lonText = String.format(Locale.US, "Lon: %.6f", data.longitude)
        canvas.drawText(lonText, x, y, textSmallPaint)
        val latText = String.format(Locale.US, "Lat: %.6f", data.latitude)
        canvas.drawText(latText, x, y - 35f, textSmallPaint)
    }

    private fun drawBottomRightPerformance(canvas: Canvas, data: TelemetryData) {
        val x = widthF - 30f
        val y = heightF - 30f
        textPaint.textAlign = Paint.Align.RIGHT

        val thr = String.format(Locale.US, "THR %d%%", data.throttlePercent)
        canvas.drawText(thr, x, y - 35f, textPaint)

        textSmallPaint.textAlign = Paint.Align.RIGHT
        val distText = String.format(Locale.US, "Dist To Home: %.0fm", data.distanceToHome)
        canvas.drawText(distText, x, y, textSmallPaint)
    }
}
