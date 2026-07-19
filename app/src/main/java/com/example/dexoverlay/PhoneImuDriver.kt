package com.example.dexoverlay

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

class PhoneImuDriver(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private var isListening = false
    var onHeadMoveListener: ((deltaX: Float, deltaY: Float) -> Unit)? = null

    fun startListening() {
        if (isListening || gyroSensor == null) return
        isListening = true
        sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME)
        Log.d("PhoneImuDriver", "Phone Gyroscope fallback motion sensor started")
    }

    fun stopListening() {
        if (!isListening) return
        isListening = false
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isListening || event == null) return

        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            val axisX = event.values[0] // Pitch (rad/s)
            val axisY = event.values[1] // Yaw (rad/s)

            val deltaX = axisY * 4.0f
            val deltaY = -axisX * 4.0f

            if (Math.abs(deltaX) > 0.01f || Math.abs(deltaY) > 0.01f) {
                onHeadMoveListener?.invoke(deltaX, deltaY)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
