/* ============================================================
 *   Carro explorador con sonar orientable — ESP32
 *   ============================================================
 *   El carro levanta su propia red WiFi y sirve la interfaz. Se
 *   maneja desde cualquier navegador, sin instalar nada.
 *
 *   Dos modos:
 *     Conduccion — el servo barre y el navegador muestra los ecos
 *                  alrededor del carro en tiempo real.
 *     Escaneo    — el carro se detiene y levanta el plano del
 *                  entorno girando sobre su propio eje.
 *
 *   El escaneo se hace quieto a proposito: el error de odometria
 *   nace del desplazamiento, asi que midiendo sin moverse el plano
 *   sale exacto aunque el chasis no tenga encoders.
 *
 *   Protocolo por WebSocket, en JSON:
 *     navegador -> carro   {"t":"c","l":-255..255,"r":-255..255}
 *                          {"t":"scan"}   {"t":"stop"}
 *     carro -> navegador   {"t":"s","a":grados,"d":cm}
 *                          {"t":"p","v":0..100}
 *                          {"t":"e","pts":[{"a":..,"d":..}, ...]}
 *   ============================================================ */

#include <stdio.h>
#include <string.h>
#include <stdlib.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "nvs_flash.h"

#include "config.h"
#include "drive.h"
#include "sonar.h"
#include "web.h"

static const char *TAG = "carro";

/** Empuja la lectura mas reciente del sonar a todos los navegadores. */
static void enviar_telemetria(void) {
    static int64_t marca = 0;
    const int64_t ahora = esp_timer_get_time();
    if (ahora - marca < (int64_t)TELEMETRIA_MS * 1000) {
        return;
    }
    marca = ahora;

    sonar_lectura_t lectura;
    if (!sonar_tomar_nueva(&lectura)) {
        return;
    }

    char trama[64];
    snprintf(trama, sizeof(trama), "{\"t\":\"s\",\"a\":%d,\"d\":%.1f}",
             lectura.angulo, lectura.distancia_cm);
    web_difundir(trama);
}

/**
 * Corre el escaneo y devuelve el plano completo al navegador.
 *
 * Se ejecuta desde la tarea principal y no desde el manejador del WebSocket:
 * ese manejador corre en la tarea del servidor, y bloquearlo varios segundos
 * dejaria de responder a todo lo demas mientras dura el escaneo.
 */
static void atender_escaneo(void) {
    if (!web_tomar_solicitud_escaneo()) {
        return;
    }

    web_difundir("{\"t\":\"p\",\"v\":0}");
    const int total = sonar_ejecutar_escaneo();
    const sonar_lectura_t *pts = sonar_puntos_escaneo();

    /* Cada punto ocupa unos 24 caracteres. Se reserva con margen y de una sola
     * vez: ir concatenando reasignaria el buffer decenas de veces y podria
     * fragmentar la memoria en una demostracion larga. */
    const size_t capacidad = (size_t)total * 28u + 32u;
    char *carga = malloc(capacidad);
    if (carga == NULL) {
        ESP_LOGE(TAG, "sin memoria para el plano");
        return;
    }

    size_t n = snprintf(carga, capacidad, "{\"t\":\"e\",\"pts\":[");
    for (int i = 0; i < total && n < capacidad - 2; ++i) {
        n += snprintf(carga + n, capacidad - n, "%s{\"a\":%d,\"d\":%.1f}",
                      (i ? "," : ""), pts[i].angulo, pts[i].distancia_cm);
    }
    snprintf(carga + n, capacidad - n, "]}");

    web_difundir(carga);
    ESP_LOGI(TAG, "escaneo listo: %d puntos, %u bytes", total, (unsigned)strlen(carga));
    free(carga);
}

void app_main(void) {
    ESP_LOGI(TAG, "== Smart Car 03 ==");

    /* El WiFi guarda su calibracion en NVS y no arranca sin ella. */
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);

    drive_iniciar();
    sonar_iniciar();

    /* Antes de levantar la red: si el hardware falla, conviene saberlo sin tener
     * que conectar el celular para averiguarlo. */
#if AUTOPRUEBA_AL_ARRANCAR
    ESP_LOGI(TAG, "---------- AUTOPRUEBA ----------");
    sonar_autoprueba();
    drive_autoprueba();
    ESP_LOGI(TAG, "---------- FIN ----------");
    ESP_LOGI(TAG, "Para saltarla, poner AUTOPRUEBA_AL_ARRANCAR en 0 en config.h");
#endif

    web_iniciar();

    for (;;) {
        drive_actualizar();
        enviar_telemetria();
        atender_escaneo();

        /* 2 ms mantienen el lazo suelto sin acaparar la CPU: la rampa corre
         * cada 10 ms y la telemetria cada 40, asi que no hace falta mas fino. */
        vTaskDelay(pdMS_TO_TICKS(2));
    }
}
