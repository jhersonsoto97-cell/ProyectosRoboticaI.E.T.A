/* ============================================================
 *   Configuracion del carro explorador — TODO lo ajustable
 *   ============================================================
 *   Unico archivo que hay que tocar para adaptar el firmware a
 *   otro cableado o a otro chasis. Si algo no responde, se revisa
 *   aca antes que en cualquier otro lado.
 *   ============================================================ */

#pragma once

#include <stdint.h>
#include "driver/gpio.h"
#include "driver/ledc.h"

/* ------------------------------------------------------------
 *   PELIGRO: el ECHO del HC-SR04 saca 5 V
 * ------------------------------------------------------------
 *   El ESP32 no tolera 5 V en sus entradas. Conectarlo directo
 *   degrada el pin y con el tiempo lo mata.
 *
 *       ECHO ---[ 1k ]---+--- GPIO
 *                        |
 *                      [ 2k ]
 *                        |
 *                       GND
 *
 *   El TRIG si va directo: es entrada del sensor y reconoce
 *   3.3 V como nivel alto.
 * ------------------------------------------------------------ */

/* ------------------------------------------------------------
 *   Pines — DevKit v1 de 30 pines, modulo WROOM-32
 * ------------------------------------------------------------
 *   Quedaron descartados:
 *     34,35,36,39  solo entrada, no pueden sacar PWM
 *     12           si esta alto al arrancar, el chip configura la
 *                  flash a 1.8 V y deja de bootear
 *     0, 5         pines de arranque, deciden el modo de boot
 *     1, 3         los usa el USB para programar y monitorear
 * ------------------------------------------------------------ */
#define PIN_IZQ_ADELANTE   GPIO_NUM_26
#define PIN_IZQ_ATRAS      GPIO_NUM_27
#define PIN_IZQ_PWM        GPIO_NUM_25

#define PIN_DER_ADELANTE   GPIO_NUM_32
#define PIN_DER_ATRAS      GPIO_NUM_33
#define PIN_DER_PWM        GPIO_NUM_14

#define PIN_SERVO          GPIO_NUM_18
#define PIN_SONAR_TRIG     GPIO_NUM_19
#define PIN_SONAR_ECHO     GPIO_NUM_21   /* via divisor 1k/2k */

/* ------------------------------------------------------------
 *   PWM
 * ------------------------------------------------------------
 *   Motores y servo necesitan frecuencias muy distintas, asi que
 *   cada grupo toma su propio temporizador. Compartirlo obligaria
 *   a una sola frecuencia para ambos.
 * ------------------------------------------------------------ */
#define LEDC_MODO          LEDC_LOW_SPEED_MODE

#define TIMER_MOTORES      LEDC_TIMER_0
#define CANAL_IZQ          LEDC_CHANNEL_0
#define CANAL_DER          LEDC_CHANNEL_1
/* 20 kHz queda por encima de lo audible. A las frecuencias tipicas de Arduino
 * el motor chilla, y en una demostracion ese ruido se nota mas que el
 * movimiento. */
#define MOTOR_FRECUENCIA   20000
#define MOTOR_RESOLUCION   LEDC_TIMER_8_BIT   /* 0..255 */

#define TIMER_SERVO        LEDC_TIMER_1
#define CANAL_SERVO        LEDC_CHANNEL_2
#define SERVO_FRECUENCIA   50                 /* 20 ms de periodo */
#define SERVO_RESOLUCION   LEDC_TIMER_14_BIT  /* ~1.2 us por paso */
#define SERVO_PULSO_MIN_US 500
#define SERVO_PULSO_MAX_US 2400

/* ------------------------------------------------------------
 *   Traccion
 * ------------------------------------------------------------ */
/* Los dos motores van montados en espejo. Si al pedir "adelante" el carro gira
 * sobre su eje, uno de estos dos pasa a true. */
#define INVERTIR_IZQUIERDA  false
#define INVERTIR_DERECHA    false

/* Piso de torque: por debajo de este PWM el motor zumba pero no gira. */
#define PWM_MIN            70
#define PWM_MAX            255

/* Recorte del motor mas rapido, en porcentaje, para que avance derecho. */
#define TRIM_IZQUIERDA     100
#define TRIM_DERECHA       100

/* Rampa: limita el di/dt del arranque. El ESP32 es mucho mas sensible que un
 * Mega a las caidas de tension que provoca ese pico. */
#define RAMPA_PASO         14
#define TICK_MS            10

/* Sin ordenes por mas de este tiempo, el carro frena solo. */
#define FAILSAFE_MS        500

/* ------------------------------------------------------------
 *   Sonar
 * ------------------------------------------------------------ */
/* Recorrido util del servo. Los topes mecanicos rara vez coinciden con 0 y 180
 * reales, y forzarlos hace que el servo trabaje contra el tope. */
#define ANGULO_MIN         10
#define ANGULO_MAX         170
#define PASO_GRADOS        3

/* Tiempo que se le da al servo para llegar antes de medir. Medir mientras
 * todavia se mueve reparte la lectura sobre varios angulos. */
#define ASENTAR_MS         18

#define ALCANCE_MAX_CM     250.0f
#define ALCANCE_MIN_CM     3.0f

/* 25 ms cubren ida y vuelta de 4 m con margen. Sin este tope, una superficie
 * que no devuelve eco dejaria la medicion esperando para siempre. */
#define TIMEOUT_US         25000

/* Velocidad del sonido a 20 grados, en cm por microsegundo. */
#define VELOCIDAD_SONIDO   0.0343f

/* ------------------------------------------------------------
 *   Escaneo estacionado
 * ------------------------------------------------------------
 *   El error de odometria nace del desplazamiento. Escaneando
 *   quieto ese error no existe, y por eso el plano que sale de
 *   aqui es exacto aunque el chasis no tenga encoders.
 * ------------------------------------------------------------ */
/* Con el servo cubriendo 160 grados, dos sectores dan 320 grados de cobertura.
 * Subir a 3 cierra la vuelta completa con solape, a cambio de mas tiempo. */
#define SECTORES           2

/* El giro entre sectores debe valer lo que abarca un sector. No importa que
 * cierre exacto en grados, sino que sea repetible: se calibra midiendo. */
#define PWM_GIRO           150
#define GIRO_MS            900
#define ASENTAR_GIRO_MS    400

/* ------------------------------------------------------------
 *   Red
 * ------------------------------------------------------------ */
#define WIFI_SSID          "SmartCar-03"
#define WIFI_CLAVE         "explorador"   /* minimo 8 caracteres */
#define WIFI_CANAL         1
#define WIFI_MAX_CLIENTES  4

/* Potencia de transmision, en unidades de 0.25 dBm. El rango va de 8 a 84, es
 * decir de 2 a 20 dBm.
 *
 * Por defecto el ESP32 transmite a 20 dBm, pensado para alcanzar decenas de
 * metros. Quien maneja el carro esta a dos o tres, y cada transmision a plena
 * potencia son unos 80 mA extra en picos que hunden la alimentacion. Bajarla a
 * 11 dBm conserva alcance de sobra para un salon y recorta el pico casi a la
 * mitad, que es la diferencia entre que el carro se reinicie o no cuando la
 * fuente esta justa.
 *
 * Si el enlace se corta al alejarse, subir de a 8 unidades. */
#define WIFI_POTENCIA_TX   44

/* Cadencia con que se empujan las lecturas al navegador. Mas rapido no aporta:
 * el servo no alcanza a moverse entre trama y trama. */
#define TELEMETRIA_MS      40

/* ------------------------------------------------------------
 *   Autoprueba de arranque
 * ------------------------------------------------------------
 *   Ejercita servo, sonar y cada motor por separado, imprimiendo
 *   el resultado por el monitor. Sirve para saber en segundos si
 *   el problema esta en el cableado, en la alimentacion o en el
 *   codigo, sin depender del celular ni de la red.
 *
 *   Poner en 0 para la demostracion: son unos ocho segundos que
 *   no hacen falta cada vez que se enciende el carro.
 * ------------------------------------------------------------ */
#define AUTOPRUEBA_AL_ARRANCAR   1

/* Potencia y duracion de cada pulso de motor durante la autoprueba. Suave y
 * corto: alcanza para ver girar la rueda sin que el carro se escape de la mesa. */
#define AUTOPRUEBA_PWM           140
#define AUTOPRUEBA_PULSO_MS      600
