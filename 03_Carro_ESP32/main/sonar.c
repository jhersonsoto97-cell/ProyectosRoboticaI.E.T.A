#include "sonar.h"
#include "config.h"
#include "drive.h"

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_timer.h"
#include "esp_rom_sys.h"
#include "esp_log.h"

static const char *TAG_SONAR = "sonar";

/* Cuantos angulos entran en un sector, mas margen. El buffer es estatico:
 * reservarlo y liberarlo en cada escaneo fragmentaria la memoria a lo largo de
 * una demostracion de varias horas. */
#define PASOS_POR_SECTOR  (((ANGULO_MAX - ANGULO_MIN) / PASO_GRADOS) + 2)
#define MAX_PUNTOS        (PASOS_POR_SECTOR * SECTORES)

static sonar_lectura_t ultima = {0, -1.0f};
static volatile bool hay_nueva = false;
static portMUX_TYPE mux = portMUX_INITIALIZER_UNLOCKED;

static volatile bool barriendo = true;
static volatile bool en_escaneo = false;
static volatile int progreso = 0;

static sonar_lectura_t puntos[MAX_PUNTOS];
static int cantidad_puntos = 0;

/* ------------------------------------------------------------
 *   Servo por LEDC
 * ------------------------------------------------------------ */
static void servo_iniciar(void) {
    const ledc_timer_config_t timer = {
        .speed_mode = LEDC_MODO,
        .timer_num = TIMER_SERVO,
        .duty_resolution = SERVO_RESOLUCION,
        .freq_hz = SERVO_FRECUENCIA,
        .clk_cfg = LEDC_AUTO_CLK,
    };
    ledc_timer_config(&timer);

    const ledc_channel_config_t canal = {
        .speed_mode = LEDC_MODO,
        .channel = CANAL_SERVO,
        .timer_sel = TIMER_SERVO,
        .intr_type = LEDC_INTR_DISABLE,
        .gpio_num = PIN_SERVO,
        .duty = 0,
        .hpoint = 0,
    };
    ledc_channel_config(&canal);
}

/** Convierte grados a ancho de pulso y de ahi al duty que espera el LEDC. */
static void servo_escribir(int grados) {
    if (grados < 0) grados = 0;
    if (grados > 180) grados = 180;

    const uint32_t pulso_us = SERVO_PULSO_MIN_US +
        ((uint32_t)grados * (SERVO_PULSO_MAX_US - SERVO_PULSO_MIN_US)) / 180;

    /* El periodo completo son 20000 us y el contador llega a 2^14. */
    const uint32_t maximo = (1u << 14) - 1u;
    const uint32_t duty = (pulso_us * maximo) / 20000u;

    ledc_set_duty(LEDC_MODO, CANAL_SERVO, duty);
    ledc_update_duty(LEDC_MODO, CANAL_SERVO);
}

/* ------------------------------------------------------------
 *   Medicion
 * ------------------------------------------------------------ */
/**
 * Una medicion del HC-SR04.
 *
 * Devuelve distancia negativa cuando no hubo eco, en vez de cero: cero es una
 * distancia valida, y confundirlos haria aparecer obstaculos pegados al sensor
 * justo donde en realidad no hay nada que devuelva la senal.
 */
static float medir_cm(void) {
    gpio_set_level(PIN_SONAR_TRIG, 0);
    esp_rom_delay_us(3);
    gpio_set_level(PIN_SONAR_TRIG, 1);
    esp_rom_delay_us(10);
    gpio_set_level(PIN_SONAR_TRIG, 0);

    /* Espera del flanco de subida. Si el sensor esta desconectado, este lazo
     * terminaria igual por el tope de tiempo en vez de colgar la tarea. */
    const int64_t inicio_espera = esp_timer_get_time();
    while (gpio_get_level(PIN_SONAR_ECHO) == 0) {
        if (esp_timer_get_time() - inicio_espera > TIMEOUT_US) {
            return -1.0f;
        }
    }

    const int64_t inicio_eco = esp_timer_get_time();
    while (gpio_get_level(PIN_SONAR_ECHO) == 1) {
        if (esp_timer_get_time() - inicio_eco > TIMEOUT_US) {
            return -1.0f;
        }
    }
    const int64_t ancho = esp_timer_get_time() - inicio_eco;

    /* Ida y vuelta: la mitad del recorrido es la distancia al obstaculo. */
    const float cm = ((float)ancho * VELOCIDAD_SONIDO) / 2.0f;
    if (cm < ALCANCE_MIN_CM || cm > ALCANCE_MAX_CM) {
        return -1.0f;
    }
    return cm;
}

/**
 * Publica en grados relativos al frente del carro, no en grados de servo.
 *
 * El cero del servo es un tope mecanico que no significa nada para quien mira
 * la pantalla. Convirtiendo aqui, el resto del sistema trabaja siempre en el
 * marco del carro: 0 es hacia adelante y crece hacia la derecha.
 */
static void publicar(int angulo_servo, float cm) {
    portENTER_CRITICAL(&mux);
    ultima.angulo = (int16_t)(angulo_servo - 90);
    ultima.distancia_cm = cm;
    hay_nueva = true;
    portEXIT_CRITICAL(&mux);
}

/** Mueve el servo, espera a que llegue y mide. */
static float medir_en(int angulo) {
    servo_escribir(angulo);
    vTaskDelay(pdMS_TO_TICKS(ASENTAR_MS));
    return medir_cm();
}

static void tarea_barrido(void *arg) {
    (void)arg;
    int angulo = ANGULO_MIN;
    int paso = PASO_GRADOS;

    for (;;) {
        if (!barriendo) {
            vTaskDelay(pdMS_TO_TICKS(20));
            continue;
        }

        const float cm = medir_en(angulo);
        publicar(angulo, cm);

        angulo += paso;
        if (angulo >= ANGULO_MAX || angulo <= ANGULO_MIN) {
            if (angulo > ANGULO_MAX) angulo = ANGULO_MAX;
            if (angulo < ANGULO_MIN) angulo = ANGULO_MIN;
            paso = -paso;
        }
    }
}

/* ------------------------------------------------------------
 *   Interfaz publica
 * ------------------------------------------------------------ */
void sonar_iniciar(void) {
    const gpio_config_t trig = {
        .pin_bit_mask = (1ULL << PIN_SONAR_TRIG),
        .mode = GPIO_MODE_OUTPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    gpio_config(&trig);

    const gpio_config_t echo = {
        .pin_bit_mask = (1ULL << PIN_SONAR_ECHO),
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    gpio_config(&echo);
    gpio_set_level(PIN_SONAR_TRIG, 0);

    servo_iniciar();
    servo_escribir((ANGULO_MIN + ANGULO_MAX) / 2);

    /* Nucleo 0: el 1 lo ocupa la pila de red. Separarlos evita que una medicion
     * de 25 ms se coma la latencia del WebSocket. */
    xTaskCreatePinnedToCore(tarea_barrido, "sonar", 4096, NULL, 4, NULL, 0);
}

bool sonar_tomar_nueva(sonar_lectura_t *destino) {
    bool resultado = false;
    portENTER_CRITICAL(&mux);
    if (hay_nueva) {
        *destino = ultima;
        hay_nueva = false;
        resultado = true;
    }
    portEXIT_CRITICAL(&mux);
    return resultado;
}

void sonar_ultima(sonar_lectura_t *destino) {
    portENTER_CRITICAL(&mux);
    *destino = ultima;
    portEXIT_CRITICAL(&mux);
}

void sonar_probar_servo(void) {
    barriendo = false;
    vTaskDelay(pdMS_TO_TICKS(60));

    ESP_LOGI(TAG_SONAR, "Servo solo. El brazo debe ir a un tope, al otro y al centro.");

    const int centro = (ANGULO_MIN + ANGULO_MAX) / 2;
    const int posiciones[] = { ANGULO_MIN, ANGULO_MAX, centro };

    for (int i = 0; i < 3; ++i) {
        ESP_LOGI(TAG_SONAR, "  a %d grados", posiciones[i] - 90);
        servo_escribir(posiciones[i]);
        /* Medio segundo por posicion: el recorrido completo de un SG90 tarda
         * unos 400 ms y hay que poder verlo llegar, no solo salir. */
        vTaskDelay(pdMS_TO_TICKS(500));
    }

    ESP_LOGI(TAG_SONAR, "  No se movio     -> revisar senal en GPIO%d y los 5 V del servo",
             (int)PIN_SERVO);
    ESP_LOGI(TAG_SONAR, "  Tiembla o zumba -> le falta corriente: capacitor de 470 uF");
    ESP_LOGI(TAG_SONAR, "                     pegado al conector del servo");
    ESP_LOGI(TAG_SONAR, "  Fuerza el tope  -> ajustar ANGULO_MIN y ANGULO_MAX en config.h");

    barriendo = true;
}

int sonar_ejecutar_escaneo(void) {
    if (en_escaneo) {
        return 0;
    }

    en_escaneo = true;
    barriendo = false;
    progreso = 0;
    cantidad_puntos = 0;

    /* El carro debe estar quieto antes de empezar: si venia rodando, la inercia
     * lo sigue desplazando y los primeros puntos saldrian de otra posicion. */
    drive_detener();
    vTaskDelay(pdMS_TO_TICKS(ASENTAR_GIRO_MS));

    const int ancho_sector = ANGULO_MAX - ANGULO_MIN;

    for (int sector = 0; sector < SECTORES; ++sector) {
        /* El offset acumula lo que el chasis giro entre sectores, de modo que
         * los puntos quedan referidos a una sola vuelta continua medida desde
         * el frente que tenia el carro al empezar. */
        const int offset = sector * ancho_sector;

        for (int a = ANGULO_MIN; a <= ANGULO_MAX; a += PASO_GRADOS) {
            if (cantidad_puntos >= MAX_PUNTOS) {
                break;
            }
            const float cm = medir_en(a);

            /* Mismo marco que las lecturas vivas: 0 es el frente inicial. */
            puntos[cantidad_puntos].angulo = (int16_t)(offset + (a - 90));
            puntos[cantidad_puntos].distancia_cm = cm;
            ++cantidad_puntos;

            progreso = (cantidad_puntos * 100) / MAX_PUNTOS;
            publicar(a, cm);
        }

        /* Tras el ultimo sector no hace falta girar: el escaneo ya cerro. */
        if (sector < SECTORES - 1) {
            servo_escribir(ANGULO_MIN);
            vTaskDelay(pdMS_TO_TICKS(ASENTAR_MS));
            drive_girar_sobre_eje(PWM_GIRO, GIRO_MS);
            vTaskDelay(pdMS_TO_TICKS(ASENTAR_GIRO_MS));
        }
    }

    progreso = 100;
    en_escaneo = false;
    barriendo = true;
    return cantidad_puntos;
}

void sonar_autoprueba(void) {
    /* La tarea de barrido ya esta corriendo y moveria el servo por su cuenta,
     * lo que mezclaria sus lecturas con las de la prueba. */
    barriendo = false;
    vTaskDelay(pdMS_TO_TICKS(60));

    ESP_LOGI(TAG_SONAR, "Servo y sonar. El brazo debe barrer de lado a lado.");

    int conEco = 0;
    int total = 0;
    float minima = ALCANCE_MAX_CM;

    for (int a = ANGULO_MIN; a <= ANGULO_MAX; a += 20) {
        const float cm = medir_en(a);
        ++total;
        if (cm > 0) {
            ++conEco;
            if (cm < minima) {
                minima = cm;
            }
            ESP_LOGI(TAG_SONAR, "  %3d grados -> %6.1f cm", a - 90, cm);
        } else {
            ESP_LOGI(TAG_SONAR, "  %3d grados -> sin eco", a - 90);
        }
    }

    servo_escribir((ANGULO_MIN + ANGULO_MAX) / 2);

    ESP_LOGI(TAG_SONAR, "  %d de %d angulos con eco", conEco, total);

    if (conEco == 0) {
        ESP_LOGW(TAG_SONAR, "  Ningun eco. Revisar en este orden:");
        ESP_LOGW(TAG_SONAR, "    1. VCC del sensor a 5 V, no a 3V3");
        ESP_LOGW(TAG_SONAR, "    2. divisor entre ECHO y GPIO%d", (int)PIN_SONAR_ECHO);
        ESP_LOGW(TAG_SONAR, "    3. TRIG en GPIO%d y tierra comun", (int)PIN_SONAR_TRIG);
    } else if (conEco < total / 2) {
        ESP_LOGW(TAG_SONAR, "  Pocos ecos. Normal apuntando al aire libre;");
        ESP_LOGW(TAG_SONAR, "  repetir frente a una pared para confirmar.");
    } else {
        ESP_LOGI(TAG_SONAR, "  Sonar OK. Mas cercano a %.1f cm", minima);
    }

    barriendo = true;
}

const sonar_lectura_t *sonar_puntos_escaneo(void) { return puntos; }
int sonar_cantidad_puntos(void) { return cantidad_puntos; }
bool sonar_escaneando(void) { return en_escaneo; }
int sonar_progreso(void) { return progreso; }
