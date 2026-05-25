package com.alexvas.rtsp.demo.util

import android.util.Log
import com.alexvas.rtsp.demo.data.TelemetryData
import io.dronefleet.mavlink.MavlinkConnection
import io.dronefleet.mavlink.common.*
import io.dronefleet.mavlink.minimal.Heartbeat
import java.io.IOException
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

class MavlinkTcpClient(private val listener: TelemetryListener) {

    private val TAG = "MavlinkTcpClient"
    private val MAV_MODE_FLAG_SAFETY_ARMED = 128

    interface TelemetryListener {
        fun onTelemetryUpdate(data: TelemetryData)
    }

    private var socket: Socket? = null
    private val isRunning = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val currentData = TelemetryData()
    private var flightStartTime: Long = 0

    fun start(ip: String, port: Int) {
        if (isRunning.getAndSet(true)) return

        executor.execute {
            connectAndListen(ip, port)
        }
    }

    fun stop() {
        isRunning.set(false)
        try {
            socket?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        currentData.reset()
        listener.onTelemetryUpdate(currentData.copy())
    }

    private fun connectAndListen(ip: String, port: Int) {
        Log.d(TAG, "Connecting to drone at $ip:$port")

        try {
            socket = Socket(ip, port).apply {
                keepAlive = true
                soTimeout = 5000
            }

            val connection = MavlinkConnection.create(socket!!.getInputStream(), socket!!.getOutputStream())

            Log.d(TAG, "Connected to drone!")
            currentData.connected = true

            while (isRunning.get() && socket?.isClosed == false) {
                try {
                    val message = connection.next()
                    if (message != null) {
                        handleMessage(message.payload)
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Mavlink error: ${e.message}")
                        // Try to reconnect if it's a socket error
                        if (e is IOException) break
                    }
                }
            }

        } catch (e: IOException) {
            if (isRunning.get()) {
                Log.e(TAG, "Connection failed: ${e.message}")
            }
        } finally {
            currentData.connected = false
            listener.onTelemetryUpdate(currentData.copy())
        }
    }

    private fun handleMessage(payload: Any) {
        var needsUpdate = false

        when (payload) {
            is Heartbeat -> {
                val isArmedNow = (payload.baseMode().value() and MAV_MODE_FLAG_SAFETY_ARMED) != 0

                if (isArmedNow && !currentData.isArmed) {
                    flightStartTime = System.currentTimeMillis()
                    if (currentData.latitude != 0.0 && currentData.longitude != 0.0) {
                        currentData.homeLatitude = currentData.latitude
                        currentData.homeLongitude = currentData.longitude
                    }
                } else if (!isArmedNow) {
                    flightStartTime = 0
                    currentData.flightTimeInSeconds = 0
                }

                currentData.isArmed = isArmedNow

                if (currentData.isArmed && flightStartTime > 0L) {
                    val diff = System.currentTimeMillis() - flightStartTime
                    currentData.flightTimeInSeconds = diff / 1000
                }

                currentData.flightMode = "Mode: ${payload.customMode()}"
                needsUpdate = true
            }

            is GlobalPositionInt -> {
                currentData.latitude = payload.lat() / 1E7
                currentData.longitude = payload.lon() / 1E7
                currentData.altitude = payload.relativeAlt() / 1000f

                if (currentData.homeLatitude != 0.0 && currentData.homeLongitude != 0.0) {
                    currentData.distanceToHome = calculateDistance(
                        currentData.latitude, currentData.longitude,
                        currentData.homeLatitude, currentData.homeLongitude
                    )
                }
                needsUpdate = true
            }

            is GpsRawInt -> {
                currentData.gpsFixType = payload.fixType().value()
                currentData.satelliteCount = payload.satellitesVisible()
                needsUpdate = true
            }

            is SysStatus -> {
                val voltage = payload.voltageBattery() / 1000f
                currentData.batteryVoltage = voltage
                currentData.batteryPercent = payload.batteryRemaining()

                val estimatedCells = round(voltage / 4.0f).toInt().coerceAtLeast(1)
                currentData.cellVoltage = voltage / estimatedCells
                needsUpdate = true
            }

            is Attitude -> {
                currentData.pitch = Math.toDegrees(payload.pitch().toDouble()).toFloat()
                currentData.roll = Math.toDegrees(payload.roll().toDouble()).toFloat()

                var yawDeg = Math.toDegrees(payload.yaw().toDouble()).toFloat()
                if (yawDeg < 0) yawDeg += 360f
                currentData.yaw = yawDeg
                needsUpdate = true
            }

            is VfrHud -> {
                currentData.airspeed = payload.airspeed()
                currentData.verticalSpeed = payload.climb()
                currentData.throttlePercent = payload.throttle()
                needsUpdate = true
            }
        }

        if (needsUpdate) {
            listener.onTelemetryUpdate(currentData.copy())
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (earthRadius * c).toFloat()
    }
}
