#include "web.h"
#include "config.h"
#include "drive.h"
#include "sonar.h"
#include "web_page.h"

#include <string.h>
#include <stdlib.h>

#include "esp_log.h"
#include "esp_wifi.h"
#include "esp_event.h"
#include "esp_netif.h"
#include "esp_http_server.h"

static const char *TAG = "web";

static httpd_handle_t servidor = NULL;

/* Descriptores de los navegadores conectados. Se guardan para poder empujarles
 * telemetria: el servidor solo entrega el descriptor dentro de una peticion, y
 * el sonar publica fuera de toda peticion. */
static int clientes[WIFI_MAX_CLIENTES];
static int cantidad_clientes = 0;

static volatile bool solicitud_escaneo = false;

/* ------------------------------------------------------------
 *   Registro de clientes
 * ------------------------------------------------------------ */
static void agregar_cliente(int fd) {
    for (int i = 0; i < cantidad_clientes; ++i) {
        if (clientes[i] == fd) {
            return;
        }
    }
    if (cantidad_clientes < WIFI_MAX_CLIENTES) {
        clientes[cantidad_clientes++] = fd;
    }
}

static void quitar_cliente(int fd) {
    for (int i = 0; i < cantidad_clientes; ++i) {
        if (clientes[i] == fd) {
            clientes[i] = clientes[--cantidad_clientes];
            return;
        }
    }
}

/* ------------------------------------------------------------
 *   Mensajes entrantes
 * ------------------------------------------------------------ */
/**
 * Extrae un entero de un JSON plano sin traer una libreria de parseo.
 *
 * Las tramas son cortas y de forma conocida. Un parser completo costaria
 * memoria y tiempo de compilacion para un formato que no va a crecer.
 */
static bool leer_entero(const char *origen, const char *clave, int *destino) {
    char patron[16];
    snprintf(patron, sizeof(patron), "\"%s\":", clave);
    const char *p = strstr(origen, patron);
    if (p == NULL) {
        return false;
    }
    *destino = atoi(p + strlen(patron));
    return true;
}

static bool tiene_tipo(const char *origen, const char *valor) {
    char patron[24];
    snprintf(patron, sizeof(patron), "\"t\":\"%s\"", valor);
    return strstr(origen, patron) != NULL;
}

static void manejar_mensaje(const char *texto) {
    if (tiene_tipo(texto, "c")) {
        int izquierda = 0;
        int derecha = 0;
        if (leer_entero(texto, "l", &izquierda) && leer_entero(texto, "r", &derecha)) {
            /* Durante un escaneo el mando queda inhibido: una orden a mitad de
             * giro moveria el carro y arruinaria la geometria del plano. */
            if (!sonar_escaneando()) {
                drive_pedir((int16_t)izquierda, (int16_t)derecha);
            }
        }
        return;
    }

    if (tiene_tipo(texto, "scan")) {
        solicitud_escaneo = true;
        return;
    }

    if (tiene_tipo(texto, "stop")) {
        drive_detener();
    }
}

/* ------------------------------------------------------------
 *   Manejadores HTTP
 * ------------------------------------------------------------ */
static esp_err_t manejar_raiz(httpd_req_t *req) {
    httpd_resp_set_type(req, "text/html");
    return httpd_resp_send(req, PAGINA_HTML, HTTPD_RESP_USE_STRLEN);
}

static esp_err_t manejar_ws(httpd_req_t *req) {
    /* El apreton de manos llega como GET. Aqui solo se registra el cliente. */
    if (req->method == HTTP_GET) {
        agregar_cliente(httpd_req_to_sockfd(req));
        ESP_LOGI(TAG, "cliente %d conectado", httpd_req_to_sockfd(req));
        return ESP_OK;
    }

    httpd_ws_frame_t trama;
    memset(&trama, 0, sizeof(trama));
    trama.type = HTTPD_WS_TYPE_TEXT;

    /* Primera llamada con len 0: pregunta cuanto mide la carga. */
    esp_err_t err = httpd_ws_recv_frame(req, &trama, 0);
    if (err != ESP_OK) {
        return err;
    }
    if (trama.len == 0 || trama.len > 256) {
        return ESP_OK;
    }

    uint8_t *buffer = calloc(1, trama.len + 1);
    if (buffer == NULL) {
        return ESP_ERR_NO_MEM;
    }
    trama.payload = buffer;

    err = httpd_ws_recv_frame(req, &trama, trama.len);
    if (err == ESP_OK && trama.type == HTTPD_WS_TYPE_TEXT) {
        manejar_mensaje((const char *)buffer);
    }

    free(buffer);
    return err;
}

/* ------------------------------------------------------------
 *   Difusion
 * ------------------------------------------------------------ */
void web_difundir(const char *texto) {
    if (servidor == NULL || cantidad_clientes == 0) {
        return;
    }

    httpd_ws_frame_t trama;
    memset(&trama, 0, sizeof(trama));
    trama.type = HTTPD_WS_TYPE_TEXT;
    trama.payload = (uint8_t *)texto;
    trama.len = strlen(texto);

    /* Se recorre al reves porque un envio fallido saca al cliente del arreglo,
     * y recorrer hacia adelante saltearia al que ocupa su lugar. */
    for (int i = cantidad_clientes - 1; i >= 0; --i) {
        if (httpd_ws_send_frame_async(servidor, clientes[i], &trama) != ESP_OK) {
            ESP_LOGI(TAG, "cliente %d se fue", clientes[i]);
            quitar_cliente(clientes[i]);
        }
    }
}

bool web_tomar_solicitud_escaneo(void) {
    if (!solicitud_escaneo) {
        return false;
    }
    solicitud_escaneo = false;
    return true;
}

/* ------------------------------------------------------------
 *   Arranque
 * ------------------------------------------------------------ */
static void iniciar_ap(void) {
    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    esp_netif_create_default_wifi_ap();

    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&cfg));

    wifi_config_t ap = {
        .ap = {
            .ssid = WIFI_SSID,
            .ssid_len = strlen(WIFI_SSID),
            .channel = WIFI_CANAL,
            .password = WIFI_CLAVE,
            .max_connection = WIFI_MAX_CLIENTES,
            .authmode = WIFI_AUTH_WPA2_PSK,
        },
    };

    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_AP));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_AP, &ap));
    ESP_ERROR_CHECK(esp_wifi_start());

    /* Debe ir despues de start: antes, la radio todavia no existe. */
    esp_wifi_set_max_tx_power(WIFI_POTENCIA_TX);

    int8_t potencia = 0;
    esp_wifi_get_max_tx_power(&potencia);
    ESP_LOGI(TAG, "red '%s', TX %.1f dBm, abrir http://192.168.4.1",
             WIFI_SSID, potencia / 4.0f);
}

void web_iniciar(void) {
    iniciar_ap();

    httpd_config_t cfg = HTTPD_DEFAULT_CONFIG();
    /* El servidor debe seguir respondiendo mientras el sonar trabaja, asi que
     * se lo ancla al nucleo 1 y el barrido queda solo en el 0. */
    cfg.core_id = 1;
    cfg.lru_purge_enable = true;
    cfg.max_open_sockets = WIFI_MAX_CLIENTES + 2;

    if (httpd_start(&servidor, &cfg) != ESP_OK) {
        ESP_LOGE(TAG, "no se pudo arrancar el servidor");
        return;
    }

    const httpd_uri_t raiz = {
        .uri = "/",
        .method = HTTP_GET,
        .handler = manejar_raiz,
    };
    httpd_register_uri_handler(servidor, &raiz);

    const httpd_uri_t ws = {
        .uri = "/ws",
        .method = HTTP_GET,
        .handler = manejar_ws,
        .is_websocket = true,
    };
    httpd_register_uri_handler(servidor, &ws);

    ESP_LOGI(TAG, "servidor arriba");
}
