#include "drive.h"
#include "config.h"

#include <stdlib.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_timer.h"

static int16_t objetivo_izq = 0;
static int16_t objetivo_der = 0;
static int16_t actual_izq = 0;
static int16_t actual_der = 0;

static int64_t marca_ultima_orden = 0;
static int64_t marca_ultimo_tick = 0;
static bool enlace_vivo = false;
static bool failsafe_disparado = false;

static int16_t limitar(int16_t v, int16_t lo, int16_t hi) {
    if (v < lo) return lo;
    if (v > hi) return hi;
    return v;
}

/**
 * Reparte el recorrido util sobre PWM_MIN..techo en vez de 0..255.
 *
 * El trim recorta el techo y no la salida ya calculada: recortarla al final
 * empujaria los valores bajos por debajo del piso de torque, y el motor
 * compensado se quedaria quieto justo donde mas falta hace la precision.
 */
static int16_t escalar(int16_t crudo, int16_t trim) {
    if (crudo == 0) {
        return 0;
    }

    const int16_t techo = limitar((int16_t)(((int32_t)PWM_MAX * trim) / 100),
                                  PWM_MIN, PWM_MAX);

    int16_t magnitud = limitar((int16_t)abs(crudo), 1, PWM_MAX);
    magnitud = PWM_MIN + (int16_t)(((int32_t)(magnitud - 1) * (techo - PWM_MIN)) /
                                   (PWM_MAX - 1));

    return (crudo < 0) ? (int16_t)(-magnitud) : magnitud;
}

/* Acerca sin saltos. Al invertir el sentido pasa por cero, lo que evita
 * conduccion cruzada en el puente H. */
static int16_t acercar(int16_t actual, int16_t objetivo) {
    const int16_t delta = objetivo - actual;
    if (delta > RAMPA_PASO) {
        return actual + RAMPA_PASO;
    }
    if (delta < -RAMPA_PASO) {
        return actual - RAMPA_PASO;
    }
    return objetivo;
}

static void aplicar_canal(gpio_num_t pin_adelante, gpio_num_t pin_atras,
                          ledc_channel_t canal, int16_t potencia, bool invertir) {
    /* La correccion va al final, sobre la salida fisica, para que el resto del
     * programa razone siempre en sentido logico. */
    if (invertir) {
        potencia = -potencia;
    }

    if (potencia > 0) {
        gpio_set_level(pin_adelante, 1);
        gpio_set_level(pin_atras, 0);
    } else if (potencia < 0) {
        gpio_set_level(pin_adelante, 0);
        gpio_set_level(pin_atras, 1);
    } else {
        gpio_set_level(pin_adelante, 0);
        gpio_set_level(pin_atras, 0);
    }

    ledc_set_duty(LEDC_MODO, canal, (uint32_t)abs(potencia));
    ledc_update_duty(LEDC_MODO, canal);
}

static void escribir_salidas(void) {
    aplicar_canal(PIN_IZQ_ADELANTE, PIN_IZQ_ATRAS, CANAL_IZQ,
                  actual_izq, INVERTIR_IZQUIERDA);
    aplicar_canal(PIN_DER_ADELANTE, PIN_DER_ATRAS, CANAL_DER,
                  actual_der, INVERTIR_DERECHA);
}

void drive_iniciar(void) {
    const gpio_config_t salidas = {
        .pin_bit_mask = (1ULL << PIN_IZQ_ADELANTE) | (1ULL << PIN_IZQ_ATRAS) |
                        (1ULL << PIN_DER_ADELANTE) | (1ULL << PIN_DER_ATRAS),
        .mode = GPIO_MODE_OUTPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    gpio_config(&salidas);

    const ledc_timer_config_t timer = {
        .speed_mode = LEDC_MODO,
        .timer_num = TIMER_MOTORES,
        .duty_resolution = MOTOR_RESOLUCION,
        .freq_hz = MOTOR_FRECUENCIA,
        .clk_cfg = LEDC_AUTO_CLK,
    };
    ledc_timer_config(&timer);

    ledc_channel_config_t canal = {
        .speed_mode = LEDC_MODO,
        .timer_sel = TIMER_MOTORES,
        .intr_type = LEDC_INTR_DISABLE,
        .duty = 0,
        .hpoint = 0,
    };

    canal.channel = CANAL_IZQ;
    canal.gpio_num = PIN_IZQ_PWM;
    ledc_channel_config(&canal);

    canal.channel = CANAL_DER;
    canal.gpio_num = PIN_DER_PWM;
    ledc_channel_config(&canal);

    drive_detener();
}

void drive_pedir(int16_t izquierda, int16_t derecha) {
    objetivo_izq = escalar(limitar(izquierda, -PWM_MAX, PWM_MAX), TRIM_IZQUIERDA);
    objetivo_der = escalar(limitar(derecha, -PWM_MAX, PWM_MAX), TRIM_DERECHA);
    marca_ultima_orden = esp_timer_get_time();
    enlace_vivo = true;
    failsafe_disparado = false;
}

void drive_detener(void) {
    objetivo_izq = 0;
    objetivo_der = 0;
    actual_izq = 0;
    actual_der = 0;
    enlace_vivo = false;
    escribir_salidas();
}

void drive_actualizar(void) {
    const int64_t ahora = esp_timer_get_time();

    /* Sin ordenes el carro no puede quedarse acelerado a ciegas: una caida de
     * WiFi o un navegador que se cierra tienen que terminar en parada. */
    if (enlace_vivo && (ahora - marca_ultima_orden) > (int64_t)FAILSAFE_MS * 1000) {
        objetivo_izq = 0;
        objetivo_der = 0;
        enlace_vivo = false;
        failsafe_disparado = true;
    }

    /* La rampa corre a periodo fijo para que la pendiente no dependa de cuanto
     * trafico de red haya en ese momento. */
    if ((ahora - marca_ultimo_tick) < (int64_t)TICK_MS * 1000) {
        return;
    }
    marca_ultimo_tick = ahora;

    actual_izq = acercar(actual_izq, objetivo_izq);
    actual_der = acercar(actual_der, objetivo_der);
    escribir_salidas();
}

int16_t drive_aplicada_izquierda(void) { return actual_izq; }
int16_t drive_aplicada_derecha(void) { return actual_der; }
bool drive_failsafe_activo(void) { return failsafe_disparado; }

void drive_girar_sobre_eje(int16_t pwm, uint32_t duracion_ms) {
    /* Se saltan la rampa y el failsafe a proposito: es una maniobra cerrada, de
     * duracion conocida, y una rampa cambiaria el angulo recorrido. */
    actual_izq = escalar(pwm, TRIM_IZQUIERDA);
    actual_der = escalar((int16_t)(-pwm), TRIM_DERECHA);
    escribir_salidas();

    vTaskDelay(pdMS_TO_TICKS(duracion_ms));

    actual_izq = 0;
    actual_der = 0;
    objetivo_izq = 0;
    objetivo_der = 0;
    escribir_salidas();
}
