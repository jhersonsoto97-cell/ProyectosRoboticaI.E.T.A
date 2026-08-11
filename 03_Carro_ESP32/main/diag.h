/* ============================================================
 *   Diagnostico remoto
 *   ============================================================
 *   Copia todo lo que sale por el monitor serie a un buffer en
 *   memoria y lo sirve por WiFi. Sin esto, el bring-up obliga a
 *   tener el carro sobre la mesa y atado al PC por el USB, que es
 *   justo la postura en la que no se puede probar nada: las ruedas
 *   no tocan el piso y la bateria no esta alimentando.
 *
 *   Tambien informa la razon del ultimo reinicio, que es el dato
 *   que separa un problema de alimentacion de un error de codigo.
 *   ============================================================ */

#pragma once

#include <stddef.h>

/* Con que reservar para volcar el log. Un byte mas que el anillo, por el cierre
 * de cadena. */
#define DIAG_LOG_MAXIMO 8193

typedef enum {
    PRUEBA_NINGUNA = 0,
    PRUEBA_SERVO,
    PRUEBA_SONAR,
    PRUEBA_MOTOR_IZQ,
    PRUEBA_MOTOR_DER,
    PRUEBA_TODO,
} diag_prueba_t;

/**
 * Engancha el log del sistema.
 *
 * Debe llamarse antes que cualquier otra cosa: lo que se imprima antes de este
 * punto sale por el UART pero no queda registrado, y la autoprueba de arranque
 * es precisamente lo que interesa conservar.
 */
void diag_iniciar(void);

/** Vuelca el log capturado en orden cronologico. Devuelve cuantos bytes escribio. */
size_t diag_log_copiar(char *destino, size_t maximo);

/** Estado del sistema en JSON. */
size_t diag_estado_json(char *destino, size_t maximo);

/** Encola una prueba pedida desde el navegador. */
void diag_pedir_prueba(diag_prueba_t prueba);

/** Devuelve la prueba pendiente y la limpia. */
diag_prueba_t diag_tomar_prueba(void);

/** Traduce el nombre que manda el navegador. */
diag_prueba_t diag_prueba_por_nombre(const char *nombre);

/**
 * Corre la prueba indicada. Bloquea varios segundos, asi que la ejecuta el lazo
 * principal y nunca el manejador HTTP: bloquear al servidor dejaria al navegador
 * sin respuesta justo mientras corre la prueba que se quiere mirar.
 */
void diag_ejecutar(diag_prueba_t prueba);
