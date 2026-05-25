package com.alexvas.rtsp.demo.data

/**
 * Drone Telemetry Snapshot.
 * Includes Flight Dynamics and Navigation Status.
 */
data class TelemetryData(
    // --- Connectivity ---
    var connected: Boolean = false,

    // --- Navigation (GPS) ---
    var latitude: Double = 0.0,
    var longitude: Double = 0.0,
    var homeLatitude: Double = 0.0,
    var homeLongitude: Double = 0.0,
    var satelliteCount: Int = 0,
    var gpsFixType: Int = 0, // 0=No Fix, 2=2D, 3=3D, 4=DGPS

    // --- Flight Dynamics ---
    var pitch: Float = 0f, // Degrees
    var roll: Float = 0f,  // Degrees
    var yaw: Float = 0f,   // Heading (0-360)
    var altitude: Float = 0f,    // Relative Altitude (m)
    var verticalSpeed: Float = 0f, // Climb/Sink rate (m/s)
    var airspeed: Float = 0f,    // m/s
    var groundspeed: Float = 0f, // m/s
    var throttlePercent: Int = 0, // 0-100%

    // --- Power ---
    var batteryVoltage: Float = 0f,
    var cellVoltage: Float = 0f,
    var batteryPercent: Int = 0,

    // --- Status ---
    var isArmed: Boolean = false,
    var flightMode: String = "--",
    var flightTimeInSeconds: Long = 0,
    var distanceToHome: Float = 0f
) {
    fun reset() {
        connected = false
        latitude = 0.0; longitude = 0.0
        homeLatitude = 0.0; homeLongitude = 0.0
        altitude = 0f; verticalSpeed = 0f
        airspeed = 0f; throttlePercent = 0
        pitch = 0f; roll = 0f; yaw = 0f
        satelliteCount = 0; gpsFixType = 0
        batteryVoltage = 0f; cellVoltage = 0f; batteryPercent = 0
        isArmed = false; flightMode = "DISCONNECTED"
        flightTimeInSeconds = 0
        distanceToHome = 0f
    }
}
