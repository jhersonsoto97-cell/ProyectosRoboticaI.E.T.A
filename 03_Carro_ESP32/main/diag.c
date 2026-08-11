#include "diag.h"
#include "config.h"
#include "drive.h"
#include "sonar.h"

#include <stdio.h>
#include <string.h>
#include <stdarg.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "esp_heap_caps.h"

static const char *TAG = "diag";

/* Ocho kilobytes alcanzan para la autoprueba completa mas un buen rato de
 * operacion. Cuando se llena, lo viejo se pierde: en diagnostico interesan las
 * ultimas lineas, que son las que siguen al sintoma. */
#define LOG_BYTES (DIAG_LOG_MAXIMO - 1)

static char anillo[LOG_BYTES];
static size_t escritura = 0;
static bool dio_la_vuelta = false;
static portMUX_TYPE candado = portMUX_INITIALIZER_UNLOCKED;

static vprintf_like_t vprintf_anterior = NULL;

static volatile diag_prueba_t prueba_pendiente = PRUEBA_NINGUNA;

/* ------------------------------------------------------------
 *   Captura del log
 * ------------------------------------------------------------ */
static void anexar(const char *texto, size_t largo) {
    portENTER_CRITICAL(&candado);
    for (size_t i = 0; i < largo; ++i) {
        anillo[escritura] = texto[i];
        if (++escritura >= LOG_BYTES) {
            escritura = 0;
            dio_la_vuelta = true;
        }
    }
    portEXIT_CRITICAL(&candado);
}

/**
 * Quita los codigos de color ANSI que el log del IDF intercala.
 *
 * En una terminal pintan la linea; en el navegador aparecen como basura del
 * tipo [0;32m delante de cada mensaje. Se filtran al entrar y no al salir para
 * no gastar buffer guardando lo que igual habria que descartar.
 */
static size_t limpiar_ansi(const char *origen, size_t largo, char *destino) {
    size_t n = 0;
    bool en_secuencia = false;

    for (size_t i = 0; i < largo; ++i) {
        const char c = origen[i];
        if (en_secuencia) {
            /* Las secuencias del log terminan siempre en 'm'. */
            if (c == 'm') {
                en_secuencia = false;
            }
            continue;
        }
        if (c == '\033') {
            en_secuencia = true;
            continue;
        }
        destino[n++] = c;
    }
    return n;
}

/**
 * Reemplazo del vprintf que usa el log del sistema.
 *
 * La lista de argumentos solo puede recorrerse una vez, asi que se duplica con
 * va_copy: una copia se formatea para el buffer y la original sigue camino al
 * UART. Sin eso, el cable dejaria de mostrar nada.
 */
static int capturar(const char *formato, va_list args) {
    va_list copia;
    va_copy(copia, args);

    char linea[256];
    const int n = vsnprintf(linea, sizeof(linea), formato, copia);
    va_end(copia);

    if (n > 0) {
        const size_t largo = (n < (int)sizeof(linea)) ? (size_t)n : sizeof(linea) - 1;
        char limpio[256];
        anexar(limpio, limpiar_ansi(linea, largo, limpio));
    }

    return vprintf_anterior ? vprintf_anterior(formato, args) : vprintf(formato, args);
}

void diag_iniciar(void) {
    vprintf_anterior = esp_log_set_vprintf(capturar);
    ESP_LOGI(TAG, "captura de log activa, %d bytes", LOG_BYTES);
}

size_t diag_log_copiar(char *destino, size_t maximo) {
    if (maximo == 0) {
        return 0;
    }

    portENTER_CRITICAL(&candado);
    const size_t cursor = escritura;
    const bool completo = dio_la_vuelta;
    portEXIT_CRITICAL(&candado);

    size_t n = 0;

    /* Si el anillo dio la vuelta, lo mas viejo empieza justo despues del cursor
     * de escritura y hay que leer en dos tramos para salir en orden. */
    if (completo) {
        const size_t viejo = LOG_BYTES - cursor;
        const size_t copiar = (viejo < maximo - 1) ? viejo : maximo - 1;
        memcpy(destino, anillo + cursor, copiar);
        n = copiar;
    }

    if (n < maximo - 1) {
        const size_t resto = (cursor < maximo - 1 - n) ? cursor : maximo - 1 - n;
        memcpy(destino + n, anillo, resto);
        n += resto;
    }

    destino[n] = '\0';
    return n;
}

/* ------------------------------------------------------------
 *   Estado
 * ------------------------------------------------------------ */
/**
 * Traduce la causa del ultimo reinicio.
 *
 * Es el dato mas util de toda la pantalla: BROWNOUT senala que la tension cayo
 * por debajo del minimo, o sea un problema de fuente, mientras que PANIC senala
 * un error de codigo. Sin este dato ambos se ven igual desde afuera, que es un
 * carro que se reinicia solo.
 */
static const char *motivo_reinicio(void) {
    switch (esp_reset_reason()) {
        case ESP_RST_POWERON:  return "encendido normal";
        case ESP_RST_SW:       return "reinicio por software";
        case ESP_RST_PANIC:    return "PANIC: error de codigo";
        case ESP_RST_INT_WDT:  return "watchdog de interrupciones";
        case ESP_RST_TASK_WDT: return "watchdog de tarea";
        case ESP_RST_WDT:      return "watchdog";
        case ESP_RST_BROWNOUT: return "BROWNOUT: cayo la alimentacion";
        case ESP_RST_DEEPSLEEP:return "salida de sueno profundo";
        case ESP_RST_EXT:      return "reset externo";
        default:               return "desconocido";
    }
}

size_t diag_estado_json(char *destino, size_t maximo) {
    sonar_lectura_t lectura;
    sonar_ultima(&lectura);

    const int64_t segundos = esp_timer_get_time() / 1000000;

    const int n = snprintf(
        destino, maximo,
        "{"
        "\"reinicio\":\"%s\","
        "\"uptime\":%lld,"
        "\"heap\":%u,"
        "\"heap_min\":%u,"
        "\"izq\":%d,"
        "\"der\":%d,"
        "\"failsafe\":%s,"
        "\"angulo\":%d,"
        "\"distancia\":%.1f,"
        "\"escaneando\":%s"
        "}",
        motivo_reinicio(),
        segundos,
        (unsigned)esp_get_free_heap_size(),
        (unsigned)esp_get_minimum_free_heap_size(),
        drive_aplicada_izquierda(),
        drive_aplicada_derecha(),
        drive_failsafe_activo() ? "true" : "false",
        lectura.angulo,
        lectura.distancia_cm,
        sonar_escaneando() ? "true" : "false");

    return (n > 0) ? (size_t)n : 0;
}

/* ------------------------------------------------------------
 *   Pruebas
 * ------------------------------------------------------------ */
void diag_pedir_prueba(diag_prueba_t prueba) {
    prueba_pendiente = prueba;
}

diag_prueba_t diag_tomar_prueba(void) {
    const diag_prueba_t pedida = prueba_pendiente;
    prueba_pendiente = PRUEBA_NINGUNA;
    return pedida;
}

diag_prueba_t diag_prueba_por_nombre(const char *nombre) {
    if (strcmp(nombre, "servo") == 0) return PRUEBA_SERVO;
    if (strcmp(nombre, "sonar") == 0) return PRUEBA_SONAR;
    if (strcmp(nombre, "izq") == 0)   return PRUEBA_MOTOR_IZQ;
    if (strcmp(nombre, "der") == 0)   return PRUEBA_MOTOR_DER;
    if (strcmp(nombre, "todo") == 0)  return PRUEBA_TODO;
    return PRUEBA_NINGUNA;
}

void diag_ejecutar(diag_prueba_t prueba) {
    switch (prueba) {
        case PRUEBA_SERVO:
            ESP_LOGI(TAG, "---------- PRUEBA: SERVO ----------");
            sonar_probar_servo();
            break;

        case PRUEBA_SONAR:
            ESP_LOGI(TAG, "---------- PRUEBA: SONAR ----------");
            sonar_autoprueba();
            break;

        case PRUEBA_MOTOR_IZQ:
            ESP_LOGI(TAG, "---------- PRUEBA: MOTOR IZQUIERDO ----------");
            drive_probar_motor(true);
            break;

        case PRUEBA_MOTOR_DER:
            ESP_LOGI(TAG, "---------- PRUEBA: MOTOR DERECHO ----------");
            drive_probar_motor(false);
            break;

        case PRUEBA_TODO:
            ESP_LOGI(TAG, "---------- PRUEBA COMPLETA ----------");
            sonar_autoprueba();
            drive_autoprueba();
            break;

        case PRUEBA_NINGUNA:
        default:
            return;
    }

    ESP_LOGI(TAG, "---------- FIN ----------");
}
