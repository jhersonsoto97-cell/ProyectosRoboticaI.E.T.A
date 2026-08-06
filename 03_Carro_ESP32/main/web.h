/* ============================================================
 *   Punto de acceso, servidor HTTP y WebSocket
 *   ============================================================
 *   El carro levanta su propia red y sirve la interfaz. No se une
 *   a una red existente porque en un salon no siempre hay WiFi, y
 *   cuando la hay suele aislar clientes entre si, con lo que el
 *   celular no alcanzaria al carro.
 *   ============================================================ */

#pragma once

#include <stdbool.h>

/** Levanta el punto de acceso y arranca el servidor. */
void web_iniciar(void);

/** Manda una trama de texto a todos los navegadores conectados. */
void web_difundir(const char *texto);

/** True si el navegador pidio un escaneo desde la ultima consulta. */
bool web_tomar_solicitud_escaneo(void);
