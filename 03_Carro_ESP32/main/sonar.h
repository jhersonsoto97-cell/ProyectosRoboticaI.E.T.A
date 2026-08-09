/* ============================================================
 *   Sonar orientable
 *   ============================================================
 *   El servo barre y el ultrasonico mide en cada paso. Corre en
 *   su propia tarea porque una medicion bloquea hasta 25 ms, y
 *   ese tiempo no puede robarselo al lazo de control ni a la red.
 *   ============================================================ */

#pragma once

#include <stdbool.h>
#include <stdint.h>

/** Una lectura. Distancia negativa significa que no hubo eco. */
typedef struct {
    int16_t angulo;        /* grados relativos al frente del carro */
    float distancia_cm;
} sonar_lectura_t;

/** Arranca el servo y lanza la tarea de barrido en el nucleo libre. */
void sonar_iniciar(void);

/**
 * Lectura mas reciente. Devuelve false si no hay ninguna nueva desde la
 * ultima consulta, para no reenviar al navegador datos que ya vio.
 */
bool sonar_tomar_nueva(sonar_lectura_t *destino);

/**
 * Escaneo estacionado. El carro gira sobre su eje entre sectores y el
 * resultado cubre el entorno desde un punto fijo, sin error de odometria
 * porque no hubo desplazamiento.
 *
 * Devuelve cuantos puntos quedaron guardados.
 */
int sonar_ejecutar_escaneo(void);

const sonar_lectura_t *sonar_puntos_escaneo(void);
int sonar_cantidad_puntos(void);

/** True mientras un escaneo esta en curso; el mando queda inhibido. */
bool sonar_escaneando(void);

/** Progreso del escaneo en curso, de 0 a 100. */
int sonar_progreso(void);

/**
 * Barre el servo e informa las lecturas por consola.
 *
 * Separa dos fallas que desde el navegador se ven iguales: un servo que no se
 * mueve y un sensor que no devuelve eco. Se ejecuta antes de levantar la red,
 * asi que no necesita celular ni WiFi para diagnosticar.
 */
void sonar_autoprueba(void);
