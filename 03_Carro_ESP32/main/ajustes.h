/* ============================================================
 *   Ajustes en caliente
 *   ============================================================
 *   Los valores que hay que calibrar contra el hardware real, en
 *   memoria no volatil y modificables desde el celular.
 *
 *   Sin esto, cada correccion de un trim cuesta desconectar la
 *   bateria, buscar el cable, recompilar y volver a armar. Con el
 *   carro en el piso esa vuelta son varios minutos, y calibrar
 *   son decenas de intentos.
 *
 *   Solo entran aqui los valores que dependen de ESTE chasis: los
 *   que se deducen de la fisica o del chip siguen en config.h,
 *   donde no invitan a que alguien los mueva sin motivo.
 *   ============================================================ */

#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef struct {
    int16_t trim_izquierda;   /* 0..100, recorte del techo de PWM */
    int16_t trim_derecha;
    int16_t pwm_min;          /* piso de torque: debajo de esto el motor zumba */
    int16_t invertir_izq;     /* 0 o 1 */
    int16_t invertir_der;

    int16_t angulo_min;       /* recorrido util del servo, en grados */
    int16_t angulo_max;
    int16_t invertir_servo;   /* 0 o 1, segun de que lado quedo montado el brazo */

    int16_t pwm_giro;         /* giro sobre el eje entre sectores del escaneo */
    int16_t giro_ms;
} ajustes_t;

/** Carga de NVS. Si no hay nada guardado, deja los valores de config.h. */
void ajustes_iniciar(void);

/** Valores vigentes. Nunca devuelve NULL. */
const ajustes_t *ajustes(void);

/**
 * Cambia un valor por nombre y lo guarda.
 *
 * Devuelve false si la clave no existe. El valor se recorta a su rango util
 * antes de guardarse: un trim en 300 o un angulo en 400 no son un error del
 * usuario que valga la pena rechazar, sino un dedo que se paso de largo.
 */
bool ajustes_fijar(const char *clave, int valor);

/** Vuelve a los valores de compilacion y los guarda. */
void ajustes_restaurar(void);

/** Estado completo en JSON, para la pantalla de calibracion. */
size_t ajustes_json(char *destino, size_t maximo);
