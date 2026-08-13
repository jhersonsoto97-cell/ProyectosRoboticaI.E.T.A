#include "sonar.h"
#include "ajustes.h"
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
 * una demostracion de varias horas.
 *
 * Se dimensiona con el recorrido maximo del servo, 0 a 180, y no con los topes
 * de config.h: esos ahora se ajustan en caliente, y si alguien los abre desde el
 * celular el barrido daria mas pasos de los que el buffer tenia previstos. */
#define PASOS_POR_SECTOR  ((180 / PASO_GRADOS) + 2)
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
/**
 * Como termino una medicion.
 *
 * Operando no interesa el detalle: hay eco o no lo hay. Diagnosticando es lo
 * unico que importa, porque cada caso apunta a una falla distinta y desde afuera
 * los cuatro se ven igual.
 */
typedef enum {
    ECO_OK,
    ECO_SIN_FLANCO,       /* el ECHO nunca subio */
    ECO_NO_BAJA,          /* subio y se quedo arriba */
    ECO_FUERA_DE_RANGO,   /* pulso medido, pero fuera de los limites utiles */
} eco_estado_t;

static eco_estado_t medir_detallado(int64_t *ancho_us, float *cm) {
    *ancho_us = 0;
    *cm = -1.0f;

    gpio_set_level(PIN_SONAR_TRIG, 0);
    esp_rom_delay_us(3);
    gpio_set_level(PIN_SONAR_TRIG, 1);
    esp_rom_delay_us(10);
    gpio_set_level(PIN_SONAR_TRIG, 0);

    /* Espera del flanco de subida. Si el sensor esta desconectado, este lazo
     * termina igual por el tope de tiempo en vez de colgar la tarea. */
    const int64_t inicio_espera = esp_timer_get_time();
    while (gpio_get_level(PIN_SONAR_ECHO) == 0) {
        if (esp_timer_get_time() - inicio_espera > TIMEOUT_US) {
            return ECO_SIN_FLANCO;
        }
    }

    const int64_t inicio_eco = esp_timer_get_time();
    while (gpio_get_level(PIN_SONAR_ECHO) == 1) {
        if (esp_timer_get_time() - inicio_eco > TIMEOUT_US) {
            *ancho_us = TIMEOUT_US;
            return ECO_NO_BAJA;
        }
    }
    *ancho_us = esp_timer_get_time() - inicio_eco;

    /* Ida y vuelta: la mitad del recorrido es la distancia al obstaculo. */
    *cm = ((float)*ancho_us * VELOCIDAD_SONIDO) / 2.0f;
    if (*cm < ALCANCE_MIN_CM || *cm > ALCANCE_MAX_CM) {
        return ECO_FUERA_DE_RANGO;
    }
    return ECO_OK;
}

static float medir_cm(void) {
    int64_t ancho;
    float cm;
    return (medir_detallado(&ancho, &cm) == ECO_OK) ? cm : -1.0f;
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
    int angulo = ajustes()->angulo_min;
    int paso = PASO_GRADOS;

    for (;;) {
        if (!barriendo) {
            vTaskDelay(pdMS_TO_TICKS(20));
            continue;
        }

        const float cm = medir_en(angulo);
        publicar(angulo, cm);

        angulo += paso;
        if (angulo >= ajustes()->angulo_max || angulo <= ajustes()->angulo_min) {
            if (angulo > ajustes()->angulo_max) angulo = ajustes()->angulo_max;
            if (angulo < ajustes()->angulo_min) angulo = ajustes()->angulo_min;
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

    /* El pull-down interno es para cuando el ECHO no esta conectado: sin el, el
     * pin queda flotando, capta ruido y el sonar informa distancias inventadas
     * que parecen medidas reales. Con el, informa "sin eco", que es la verdad.
     *
     * Con el divisor puesto no molesta: son unos 45 kohm en paralelo con los 2 k
     * de abajo, que bajan la tension de 3.33 a 3.28 V. */
    const gpio_config_t echo = {
        .pin_bit_mask = (1ULL << PIN_SONAR_ECHO),
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_DISABLE,
        .pull_down_en = GPIO_PULLDOWN_ENABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    gpio_config(&echo);
    gpio_set_level(PIN_SONAR_TRIG, 0);

    servo_iniciar();
    servo_escribir((ajustes()->angulo_min + ajustes()->angulo_max) / 2);

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

    const int centro = (ajustes()->angulo_min + ajustes()->angulo_max) / 2;
    const int posiciones[] = { ajustes()->angulo_min, ajustes()->angulo_max, centro };

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
    ESP_LOGI(TAG_SONAR, "  Fuerza el tope  -> cerrar el recorrido en la pantalla de calibracion");

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

    const int ancho_sector = ajustes()->angulo_max - ajustes()->angulo_min;

    for (int sector = 0; sector < SECTORES; ++sector) {
        /* El offset acumula lo que el chasis giro entre sectores, de modo que
         * los puntos quedan referidos a una sola vuelta continua medida desde
         * el frente que tenia el carro al empezar. */
        const int offset = sector * ancho_sector;

        for (int a = ajustes()->angulo_min; a <= ajustes()->angulo_max; a += PASO_GRADOS) {
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
            servo_escribir(ajustes()->angulo_min);
            vTaskDelay(pdMS_TO_TICKS(ASENTAR_MS));
            drive_girar_sobre_eje(ajustes()->pwm_giro, (uint32_t)ajustes()->giro_ms);
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

    /* En reposo el ECHO debe estar bajo. Si esta alto sin haber disparado nada, no
     * hay medicion posible y la causa esta en el cableado, no en el sensor. */
    ESP_LOGI(TAG_SONAR, "  ECHO en reposo: %s",
             gpio_get_level(PIN_SONAR_ECHO) ? "ALTO  <-- mal, deberia estar bajo" : "bajo");

    int conEco = 0;
    int total = 0;
    int sinFlanco = 0;
    int noBaja = 0;
    int fueraRango = 0;
    float minima = ALCANCE_MAX_CM;

    for (int a = ajustes()->angulo_min; a <= ajustes()->angulo_max; a += 20) {
        servo_escribir(a);
        vTaskDelay(pdMS_TO_TICKS(ASENTAR_MS));

        int64_t ancho = 0;
        float cm = -1.0f;
        const eco_estado_t estado = medir_detallado(&ancho, &cm);
        ++total;

        /* Se informa el ancho crudo del pulso incluso cuando la lectura se
         * descarta. Es el unico dato que distingue un sensor mudo de uno que
         * responde con basura, y sin el las dos fallas se leen igual. */
        switch (estado) {
            case ECO_OK:
                ++conEco;
                if (cm < minima) {
                    minima = cm;
                }
                ESP_LOGI(TAG_SONAR, "  %3d grados -> %6.1f cm   (pulso %lld us)",
                         a - 90, cm, ancho);
                break;

            case ECO_SIN_FLANCO:
                ++sinFlanco;
                ESP_LOGI(TAG_SONAR, "  %3d grados -> sin flanco: el ECHO nunca subio",
                         a - 90);
                break;

            case ECO_NO_BAJA:
                ++noBaja;
                ESP_LOGI(TAG_SONAR, "  %3d grados -> el ECHO subio y no bajo", a - 90);
                break;

            case ECO_FUERA_DE_RANGO:
                ++fueraRango;
                ESP_LOGI(TAG_SONAR, "  %3d grados -> pulso %lld us = %.1f cm, fuera de %.0f a %.0f",
                         a - 90, ancho, cm, (double)ALCANCE_MIN_CM, (double)ALCANCE_MAX_CM);
                break;
        }
    }

    servo_escribir((ajustes()->angulo_min + ajustes()->angulo_max) / 2);

    ESP_LOGI(TAG_SONAR, "  %d de %d con eco valido  (sin flanco %d, no baja %d, fuera de rango %d)",
             conEco, total, sinFlanco, noBaja, fueraRango);

    /* Cada patron apunta a una falla distinta, y ese es el motivo de contarlos por
     * separado en vez de resumir todo en "sin eco". */
    if (conEco > 0 && conEco >= total / 2) {
        ESP_LOGI(TAG_SONAR, "  Sonar OK. Mas cercano a %.1f cm", minima);
    } else if (sinFlanco == total) {
        ESP_LOGW(TAG_SONAR, "  El sensor no responde nunca. Revisar en este orden:");
        ESP_LOGW(TAG_SONAR, "    1. VCC del sensor, medido en sus propias patas, a 5 V");
        ESP_LOGW(TAG_SONAR, "    2. GND del sensor unido al GND del ESP32");
        ESP_LOGW(TAG_SONAR, "    3. TRIG en GPIO%d, sin divisor de por medio",
                 (int)PIN_SONAR_TRIG);
    } else if (fueraRango > 0 && conEco == 0) {
        ESP_LOGW(TAG_SONAR, "  Hay pulsos pero ninguno util. Si duran pocas decenas");
        ESP_LOGW(TAG_SONAR, "  de microsegundos no son eco: es el propio disparo del");
        ESP_LOGW(TAG_SONAR, "  TRIG acoplandose al cable del ECHO. Separar los dos");
        ESP_LOGW(TAG_SONAR, "  cables, y revisar que el sensor tenga sus 5 V.");
    } else if (noBaja > 0) {
        ESP_LOGW(TAG_SONAR, "  El ECHO se queda arriba. Revisar el divisor: con las");
        ESP_LOGW(TAG_SONAR, "  resistencias invertidas el pin no vuelve a bajar.");
    } else {
        ESP_LOGW(TAG_SONAR, "  Pocos ecos. Normal apuntando al aire libre;");
        ESP_LOGW(TAG_SONAR, "  repetir frente a una pared para confirmar.");
    }

    barriendo = true;
}

const sonar_lectura_t *sonar_puntos_escaneo(void) { return puntos; }
int sonar_cantidad_puntos(void) { return cantidad_puntos; }
bool sonar_escaneando(void) { return en_escaneo; }
int sonar_progreso(void) { return progreso; }
