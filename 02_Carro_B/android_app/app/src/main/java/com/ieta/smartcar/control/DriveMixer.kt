package com.ieta.smartcar.control

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Como se traducen los joysticks a potencia de rueda. */
enum class DriveMode { ARCADE, TANK }

/** Potencia por rueda ya lista para el firmware, en el rango -255..255. */
data class WheelPower(val left: Int, val right: Int)

object DriveMixer {

    /** Zona muerta: el dedo nunca suelta el stick exactamente en el centro. */
    const val DEADZONE = 0.08f

    fun mix(
        mode: DriveMode,
        leftStickY: Float,
        leftStickX: Float,
        rightStickY: Float,
        rightStickX: Float,
        speedCap: Float
    ): WheelPower = when (mode) {
        // Stick izquierdo = acelerador, stick derecho = direccion. Manejo tipo consola.
        DriveMode.ARCADE -> arcade(
            throttle = applyDeadzone(leftStickY),
            steer = applyDeadzone(rightStickX),
            speedCap = speedCap
        )
        // Cada stick manda su propia oruga. Control total, curva de aprendizaje mas dura.
        DriveMode.TANK -> WheelPower(
            left = toPwm(applyDeadzone(leftStickY) * speedCap),
            right = toPwm(applyDeadzone(rightStickY) * speedCap)
        )
    }

    private fun arcade(throttle: Float, steer: Float, speedCap: Float): WheelPower {
        var left = throttle + steer
        var right = throttle - steer

        // Si la suma se sale del rango, se normaliza en vez de recortar: recortar
        // deformaria el radio de giro justo cuando mas se necesita precision.
        val peak = max(abs(left), abs(right))
        if (peak > 1f) {
            left /= peak
            right /= peak
        }

        return WheelPower(toPwm(left * speedCap), toPwm(right * speedCap))
    }

    private fun applyDeadzone(value: Float): Float {
        if (abs(value) < DEADZONE) return 0f
        // Reescala lo que queda para no perder el tramo consumido por la zona muerta.
        val sign = if (value < 0f) -1f else 1f
        return sign * ((abs(value) - DEADZONE) / (1f - DEADZONE))
    }

    private fun toPwm(value: Float): Int = (value.coerceIn(-1f, 1f) * 255f).roundToInt()
}
