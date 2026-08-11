/* ============================================================
 *   Traccion diferencial
 *   ============================================================
 *   Traduce ordenes de potencia por rueda a senales del puente H,
 *   con rampa de aceleracion y failsafe por perdida de enlace.
 *   ============================================================ */

#pragma once

#include <stdbool.h>
#include <stdint.h>

/** Configura pines y canales de PWM. Deja el carro detenido. */
void drive_iniciar(void);

/**
 * Pide potencia por rueda, de -255 a 255. El signo es el sentido.
 * Cada llamada alimenta el failsafe: dejar de llamar equivale a frenar.
 */
void drive_pedir(int16_t izquierda, int16_t derecha);

/** Detiene sin esperar la rampa. Para el paro de emergencia. */
void drive_detener(void);

/**
 * Avanza la rampa y vigila el failsafe. Debe llamarse continuamente;
 * es lo unico que escribe en las salidas.
 */
void drive_actualizar(void);

int16_t drive_aplicada_izquierda(void);
int16_t drive_aplicada_derecha(void);
bool drive_failsafe_activo(void);

/**
 * Giro sobre el eje, bloqueante, usado por el escaneo estacionado.
 * Bloquea a proposito: durante el giro no debe entrar ninguna otra orden,
 * o el sector medido no correspondera al angulo supuesto.
 */
void drive_girar_sobre_eje(int16_t pwm, uint32_t duracion_ms);

/**
 * Mueve cada motor por separado, en los dos sentidos, informando por consola.
 *
 * Se prueba de a uno porque con los dos girando es imposible distinguir un motor
 * invertido de un giro pedido a proposito, y se prueba cada sentido porque cada
 * uno usa una mitad distinta del puente H: fallando una sola, la otra igual anda.
 */
void drive_autoprueba(void);

/**
 * Pulsa un solo motor en los dos sentidos.
 *
 * Es la unidad que sirve para reparar: se mueve un cable, se repite esta prueba
 * desde el celular y se ve al instante si era ese. Probar los dos juntos obliga
 * a esperar el ciclo completo por cada intento.
 */
void drive_probar_motor(bool izquierdo);
