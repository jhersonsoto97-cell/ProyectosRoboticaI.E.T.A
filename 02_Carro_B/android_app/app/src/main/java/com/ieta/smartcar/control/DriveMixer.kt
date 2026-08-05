package com.ieta.smartcar.control

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Como se traducen los joysticks a potencia de rueda. */
enum class DriveMode { ARCADE, TANK }

/** Potencia por rueda ya lista para el firmware, en el rango -255..255. */
data class WheelPower(val left: Int, val right: Int)

/**
 * Como responde el mando al pulgar. Se ajusta desde la app porque el valor correcto
 * depende del chasis, del peso y del piso, y no hay uno que sirva para todos.
 *
 * @param throttleExpo cuanto se achata el centro del acelerador, de 0 a 1.
 * @param steerExpo    lo mismo para la direccion.
 * @param steerAuthority cuanta diferencia entre ruedas puede pedir la direccion. Con 1
 *   el stick al tope hace girar el carro sobre su eje; bajarlo abre el radio de giro y
 *   es lo que permite trazar curvas en vez de trompos.
 */
data class DriveTuning(
    val throttleExpo: Float = 0.55f,
    val steerExpo: Float = 0.60f,
    val steerAuthority: Float = 0.65f
)

object DriveMixer {

    /** Zona muerta: el dedo nunca suelta el stick exactamente en el centro. */
    const val DEADZONE = 0.08f

    fun mix(
        mode: DriveMode,
        leftStickY: Float,
        leftStickX: Float,
        rightStickY: Float,
        rightStickX: Float,
        speedCap: Float,
        tuning: DriveTuning
    ): WheelPower = when (mode) {
        // Stick izquierdo = acelerador, stick derecho = direccion. Manejo tipo consola.
        DriveMode.ARCADE -> arcade(
            throttle = shape(leftStickY, tuning.throttleExpo),
            steer = shape(rightStickX, tuning.steerExpo) * tuning.steerAuthority,
            speedCap = speedCap
        )
        // Cada stick manda su propia oruga. Control total, curva de aprendizaje mas dura.
        DriveMode.TANK -> WheelPower(
            left = toPwm(shape(leftStickY, tuning.throttleExpo) * speedCap),
            right = toPwm(shape(rightStickY, tuning.throttleExpo) * speedCap)
        )
    }

    /**
     * Zona muerta y curva expo, en ese orden: la curva actua sobre el valor ya limpio.
     *
     * La expo viene de las emisoras de radiocontrol. Con respuesta lineal el tramo util
     * del pulgar se gasta en la mitad alta del recorrido: cualquier toque pequeno ya
     * manda mucha potencia y maniobrar despacio se vuelve imposible. La curva achata el
     * centro y conserva el extremo, de modo que se gana precision sin perder velocidad
     * maxima. Con expo en 0 la respuesta vuelve a ser lineal.
     */
    private fun shape(value: Float, expo: Float): Float {
        val limpio = applyDeadzone(value)
        return expo * limpio * limpio * limpio + (1f - expo) * limpio
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
