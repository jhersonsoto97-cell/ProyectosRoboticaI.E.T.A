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
 * Igual que la anterior pero sin consumir la marca de "nueva".
 *
 * La usa el diagnostico, que consulta aparte: si consumiera la marca le robaria
 * las lecturas a la telemetria del mando y el radar de la pantalla principal se
 * quedaria a medias.
 */
void sonar_ultima(sonar_lectura_t *destino);

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

/**
 * Detiene el barrido y sostiene el servo en el centro de su recorrido.
 *
 * Existe para montar el brazo: el sensor no va atornillado, asi que hay que pegarlo
 * mirando al frente, y con el servo barriendo eso es imposible de acertar. Se sostiene
 * en vez de moverse una vez porque un servo sin senal cede ante el peso del brazo.
 */
void sonar_centrar(bool activo);

/** True mientras el brazo se mantiene quieto en el centro. */
bool sonar_centrado(void);

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

/**
 * Mueve el servo a los topes y al centro, sin medir.
 *
 * Separada de la anterior porque un servo mudo y un sensor mudo se ven igual en
 * la prueba conjunta: si nada responde, esta dice cual de los dos falla.
 */
void sonar_probar_servo(void);
