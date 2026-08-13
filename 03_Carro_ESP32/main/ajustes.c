#include "ajustes.h"
#include "config.h"

#include <stdio.h>
#include <string.h>

#include "esp_log.h"
#include "nvs_flash.h"
#include "nvs.h"

static const char *TAG = "ajustes";

#define ESPACIO  "carro"
#define CLAVE    "ajustes"

/* Sube cuando cambia la forma del struct. Un blob guardado con la forma vieja se
 * descarta en vez de leerse torcido, que es peor que perder la calibracion. */
#define VERSION  2

static ajustes_t vigentes;

static const ajustes_t DE_FABRICA = {
    .trim_izquierda = TRIM_IZQUIERDA,
    .trim_derecha   = TRIM_DERECHA,
    .pwm_min        = PWM_MIN,
    .invertir_izq   = INVERTIR_IZQUIERDA ? 1 : 0,
    .invertir_der   = INVERTIR_DERECHA ? 1 : 0,
    .angulo_min     = ANGULO_MIN,
    .angulo_max     = ANGULO_MAX,
    .invertir_servo = INVERTIR_SERVO ? 1 : 0,
    .pwm_giro       = PWM_GIRO,
    .giro_ms        = GIRO_MS,
};

/* Un campo por fila: nombre que viaja por la red, donde vive, y su rango util.
 * Tabla y no una cadena de if: agregar un ajuste pasa a ser una linea, y el
 * recorte y el JSON salen solos sin tocar tres funciones. */
typedef struct {
    const char *clave;
    size_t desplazamiento;
    int16_t minimo;
    int16_t maximo;
} campo_t;

#define CAMPO(nombre, lo, hi) \
    { #nombre, offsetof(ajustes_t, nombre), (lo), (hi) }

static const campo_t CAMPOS[] = {
    CAMPO(trim_izquierda, 10, 100),
    CAMPO(trim_derecha,   10, 100),
    CAMPO(pwm_min,         0, 200),
    CAMPO(invertir_izq,    0,   1),
    CAMPO(invertir_der,    0,   1),
    /* Los topes del servo se acotan al recorrido fisico de un SG90. Pedirle mas
     * lo hace trabajar contra el tope mecanico, que zumba y lo desgasta. */
    CAMPO(angulo_min,      0,  90),
    CAMPO(angulo_max,     90, 180),
    CAMPO(invertir_servo,  0,   1),
    CAMPO(pwm_giro,       60, 255),
    CAMPO(giro_ms,       100, 3000),
};

#define CANTIDAD_CAMPOS (sizeof(CAMPOS) / sizeof(CAMPOS[0]))

static int16_t *campo_en(const campo_t *campo) {
    return (int16_t *)((char *)&vigentes + campo->desplazamiento);
}

static int16_t recortar(int16_t valor, int16_t lo, int16_t hi) {
    if (valor < lo) return lo;
    if (valor > hi) return hi;
    return valor;
}

static void guardar(void) {
    nvs_handle_t manejador;
    if (nvs_open(ESPACIO, NVS_READWRITE, &manejador) != ESP_OK) {
        ESP_LOGW(TAG, "no se pudo abrir NVS, los ajustes duran hasta el proximo corte");
        return;
    }

    uint8_t bloque[sizeof(ajustes_t) + 1];
    bloque[0] = VERSION;
    memcpy(bloque + 1, &vigentes, sizeof(ajustes_t));

    if (nvs_set_blob(manejador, CLAVE, bloque, sizeof(bloque)) == ESP_OK) {
        nvs_commit(manejador);
    }
    nvs_close(manejador);
}

void ajustes_iniciar(void) {
    vigentes = DE_FABRICA;

    nvs_handle_t manejador;
    if (nvs_open(ESPACIO, NVS_READONLY, &manejador) != ESP_OK) {
        ESP_LOGI(TAG, "sin calibracion guardada, valores de fabrica");
        return;
    }

    uint8_t bloque[sizeof(ajustes_t) + 1];
    size_t largo = sizeof(bloque);

    if (nvs_get_blob(manejador, CLAVE, bloque, &largo) == ESP_OK &&
        largo == sizeof(bloque) && bloque[0] == VERSION) {
        memcpy(&vigentes, bloque + 1, sizeof(ajustes_t));
        ESP_LOGI(TAG, "calibracion recuperada de NVS");
    } else {
        ESP_LOGI(TAG, "sin calibracion valida, valores de fabrica");
    }

    nvs_close(manejador);

    /* Aunque venga de NVS se recorta igual: un blob de una version anterior con
     * el mismo tamano pasaria el control y podria traer un valor imposible. */
    for (size_t i = 0; i < CANTIDAD_CAMPOS; ++i) {
        int16_t *destino = campo_en(&CAMPOS[i]);
        *destino = recortar(*destino, CAMPOS[i].minimo, CAMPOS[i].maximo);
    }

    ESP_LOGI(TAG, "trim %d/%d  pwm_min %d  invertir %d/%d  servo %d..%d  giro %d por %d ms",
             vigentes.trim_izquierda, vigentes.trim_derecha, vigentes.pwm_min,
             vigentes.invertir_izq, vigentes.invertir_der,
             vigentes.angulo_min, vigentes.angulo_max,
             vigentes.pwm_giro, vigentes.giro_ms);
}

const ajustes_t *ajustes(void) {
    return &vigentes;
}

bool ajustes_fijar(const char *clave, int valor) {
    for (size_t i = 0; i < CANTIDAD_CAMPOS; ++i) {
        if (strcmp(clave, CAMPOS[i].clave) != 0) {
            continue;
        }

        const int16_t recortado = recortar((int16_t)valor,
                                           CAMPOS[i].minimo, CAMPOS[i].maximo);
        *campo_en(&CAMPOS[i]) = recortado;

        /* El servo no admite que los topes se crucen: cambiarlos por separado
         * puede dejar el minimo por encima del maximo y el barrido no arrancaria. */
        if (vigentes.angulo_min >= vigentes.angulo_max) {
            if (strcmp(clave, "angulo_min") == 0) {
                vigentes.angulo_min = (int16_t)(vigentes.angulo_max - 10);
            } else {
                vigentes.angulo_max = (int16_t)(vigentes.angulo_min + 10);
            }
        }

        guardar();
        ESP_LOGI(TAG, "%s = %d", clave, recortado);
        return true;
    }
    return false;
}

void ajustes_restaurar(void) {
    vigentes = DE_FABRICA;
    guardar();
    ESP_LOGI(TAG, "calibracion vuelta a los valores de fabrica");
}

size_t ajustes_json(char *destino, size_t maximo) {
    size_t n = snprintf(destino, maximo, "{");

    for (size_t i = 0; i < CANTIDAD_CAMPOS && n < maximo; ++i) {
        n += snprintf(destino + n, maximo - n, "%s\"%s\":%d",
                      (i ? "," : ""), CAMPOS[i].clave, *campo_en(&CAMPOS[i]));
    }

    if (n < maximo) {
        n += snprintf(destino + n, maximo - n, "}");
    }
    return n;
}
