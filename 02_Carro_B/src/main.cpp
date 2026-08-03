/*NUEVO
  Smart Car Bluetooth - PlatformIO
  Arduino Mega 2560 + L298N + HC-05

  Protocolo analogico (app Android "IETA Smart Car"):
    <L,R>   con L y R entre -255 y 255. Signo = sentido de giro de cada rueda.
    Ejemplo: <180,-120> avanza la rueda izquierda y retrocede la derecha.
    La app repite el paquete 20 veces por segundo; ese flujo alimenta el failsafe.

  Comandos discretos heredados (monitor serie o apps antiguas):
    A = avanzar, R = retroceder, I = izquierda, D = derecha, S = detener.
    Son de enclavamiento: quedan fijos hasta el siguiente comando, sin failsafe.
    T = prueba de sentido: mueve una rueda a la vez para calibrar INVERTIR_*.

  Seguridad y calidad de movimiento:
    FAILSAFE_MS  -> si el enlace analogico se corta, el carro frena solo.
    RAMPA_PASO   -> limita el di/dt para que el pico de arranque no reinicie el Mega.
    PWM_MIN      -> piso de torque: por debajo de este PWM el motor solo zumba.

  Conexion L298N:
    D10 -> ENA (PWM; retire su jumper), D9 -> IN1, D8 -> IN2
    D5  -> ENB (PWM; retire su jumper), D7 -> IN3, D6 -> IN4
    OUT1/OUT2 -> motor izquierdo, OUT3/OUT4 -> motor derecho.

  Conexion HC-05:
    D18/TX1 -> RXD a traves de divisor 1k/2k (el Mega saca 5 V, el HC-05 espera 3.3 V)
    D19/RX1 <- TXD, 5V -> VCC, GND -> GND.
*/

#include <Arduino.h>  // API de Arduino: Serial, pinMode, digitalWrite y analogWrite.
#include <ctype.h>    // Funcion toupper() para aceptar comandos en minuscula.
#include <stdlib.h>   // Funcion atoi() para convertir el texto del paquete a entero.
#include <string.h>   // Funcion strchr() para localizar el separador del paquete.

// ---------- Pines configurables del L298N ----------
const uint8_t PIN_ENA = 10;  // PWM del motor izquierdo; D10 admite PWM en Mega.
const uint8_t PIN_IN1 = 9;   // Primera entrada de direccion del motor izquierdo.
const uint8_t PIN_IN2 = 8;   // Segunda entrada de direccion del motor izquierdo.
const uint8_t PIN_ENB = 5;   // PWM del motor derecho; D5 admite PWM en Mega.
const uint8_t PIN_IN3 = 7;   // Primera entrada de direccion del motor derecho.
const uint8_t PIN_IN4 = 6;   // Segunda entrada de direccion del motor derecho.

// Serial1 usa D18 (TX1) y D19 (RX1), por lo que no ocupa el USB de programacion.
HardwareSerial &bluetooth = Serial1;
const unsigned long BAUD_BLUETOOTH = 9600;  // Baudrate por defecto de la mayoria de HC-05.

// ---------- Correccion de sentido de giro ----------
// Los dos motores van montados en espejo, uno a cada lado del chasis. Si se cablean
// simetricamente al L298N, uno gira al reves por pura geometria: pedir "adelante"
// termina haciendo girar el carro sobre su eje en vez de avanzar.
// Poner en true el lado que gire invertido. Enviar 'T' por el monitor serie ejecuta
// la prueba de sentido rueda por rueda para saber cual corregir.
const bool INVERTIR_IZQUIERDA = true;
const bool INVERTIR_DERECHA = false;

// ---------- Parametros de control ----------
const int16_t PWM_MIN = 60;              // Piso de torque medido: abajo de esto el motor no rompe inercia.
const int16_t PWM_MAX = 255;             // Techo del analogWrite de 8 bits.
const int16_t RAMPA_PASO = 12;           // Cambio maximo de PWM por tick: 0 a 255 en unos 210 ms.
const unsigned long TICK_MS = 10;        // Periodo del lazo de rampa; fija la pendiente real.
const unsigned long FAILSAFE_MS = 400;   // Sin paquete analogico por mas de esto, se frena.

// Al encender, el modulo Bluetooth emite basura por Serial1 mientras su UART estabiliza.
// Un solo byte espurio interpretado como comando discreto bastaria para que el carro
// arranque solo. Durante esta ventana se ignoran las letras sueltas; los paquetes <L,R>
// si se aceptan, porque su delimitacion los hace practicamente inmunes al ruido.
const unsigned long SILENCIO_ARRANQUE_MS = 1500;

// Potencia objetivo pedida por el enlace, ya escalada al rango util del motor.
int16_t objetivoIzquierda = 0;
int16_t objetivoDerecha = 0;

// Potencia realmente aplicada; persigue al objetivo respetando la rampa.
int16_t actualIzquierda = 0;
int16_t actualDerecha = 0;

unsigned long marcaUltimoPaquete = 0;  // Momento del ultimo paquete <L,R> valido.
unsigned long marcaUltimoTick = 0;     // Momento de la ultima actualizacion de rampa.
bool enlaceAnalogicoVivo = false;      // Solo con enlace analogico se vigila el failsafe.

// Buffer del parser: acumula lo que llega entre '<' y '>'.
char bufferPaquete[16];
uint8_t largoBuffer = 0;
bool capturandoPaquete = false;

// Declaraciones anticipadas de las funciones usadas por el programa.
void configurarPines();
int16_t escalarPotencia(int16_t crudo);
int16_t acercarConRampa(int16_t actual, int16_t objetivo);
void aplicarCanal(uint8_t pinEnable, uint8_t pinDirectoA, uint8_t pinDirectoB,
                  int16_t potencia, bool invertir);
void procesarCaracter(char entrante, char origen);
void procesarPaqueteAnalogico(char *texto);
void procesarComandoDiscreto(char comando, char origen);
void ejecutarPruebaDeSentido();
void probarCanal(uint8_t pinEnable, uint8_t pinDirectoA, uint8_t pinDirectoB,
                 int16_t potencia, bool invertir);
void frenar();

void setup() {
  Serial.begin(115200);                  // Habilita mensajes de diagnostico por USB.
  bluetooth.begin(BAUD_BLUETOOTH);       // Habilita el puerto hardware conectado al HC-05.
  configurarPines();                     // Prepara todas las salidas hacia el L298N.
  frenar();                              // Mantiene el carro detenido al encender.
  Serial.println(F("Smart Car listo. Protocolo <L,R>, comandos A/R/I/D/S, T = prueba."));
}

void loop() {
  // El HC-05 trae los paquetes de la app; el USB permite probar sin telefono.
  while (bluetooth.available() > 0) {
    procesarCaracter(static_cast<char>(bluetooth.read()), 'B');
  }
  while (Serial.available() > 0) {
    procesarCaracter(static_cast<char>(Serial.read()), 'U');
  }

  const unsigned long ahora = millis();

  // Si la app dejo de transmitir, el carro no puede quedarse acelerado a ciegas.
  if (enlaceAnalogicoVivo && (ahora - marcaUltimoPaquete) > FAILSAFE_MS) {
    objetivoIzquierda = 0;
    objetivoDerecha = 0;
    enlaceAnalogicoVivo = false;
    Serial.println(F("FAILSAFE: enlace perdido, frenando."));
  }

  // La rampa corre a periodo fijo para que la pendiente no dependa del trafico serial.
  if ((ahora - marcaUltimoTick) >= TICK_MS) {
    marcaUltimoTick = ahora;
    actualIzquierda = acercarConRampa(actualIzquierda, objetivoIzquierda);
    actualDerecha = acercarConRampa(actualDerecha, objetivoDerecha);
    aplicarCanal(PIN_ENA, PIN_IN1, PIN_IN2, actualIzquierda, INVERTIR_IZQUIERDA);
    aplicarCanal(PIN_ENB, PIN_IN3, PIN_IN4, actualDerecha, INVERTIR_DERECHA);
  }
}

void configurarPines() {
  pinMode(PIN_ENA, OUTPUT);               // ENA entrega PWM al canal izquierdo.
  pinMode(PIN_IN1, OUTPUT);               // IN1 controla sentido izquierdo.
  pinMode(PIN_IN2, OUTPUT);               // IN2 controla sentido izquierdo.
  pinMode(PIN_ENB, OUTPUT);               // ENB entrega PWM al canal derecho.
  pinMode(PIN_IN3, OUTPUT);               // IN3 controla sentido derecho.
  pinMode(PIN_IN4, OUTPUT);               // IN4 controla sentido derecho.
}

// Reparte el recorrido util del joystick sobre PWM_MIN..PWM_MAX en vez de 0..255,
// para no desperdiciar la mitad del rango en la zona donde el motor no gira.
int16_t escalarPotencia(int16_t crudo) {
  if (crudo == 0) {
    return 0;
  }
  int16_t magnitud = constrain(abs(crudo), 1, PWM_MAX);
  magnitud = PWM_MIN + static_cast<int16_t>(
      (static_cast<int32_t>(magnitud - 1) * (PWM_MAX - PWM_MIN)) / (PWM_MAX - 1));
  return (crudo < 0) ? static_cast<int16_t>(-magnitud) : magnitud;
}

// Acerca la salida al objetivo sin saltos. Al invertir el sentido pasa por cero,
// lo que evita conduccion cruzada en el puente H.
int16_t acercarConRampa(int16_t actual, int16_t objetivo) {
  const int16_t delta = objetivo - actual;
  if (delta > RAMPA_PASO) {
    return actual + RAMPA_PASO;
  }
  if (delta < -RAMPA_PASO) {
    return actual - RAMPA_PASO;
  }
  return objetivo;
}

void aplicarCanal(uint8_t pinEnable, uint8_t pinDirectoA, uint8_t pinDirectoB,
                  int16_t potencia, bool invertir) {
  // La correccion se aplica al final, sobre la salida fisica. Asi el resto del programa
  // razona siempre en sentido logico y la telemetria no miente sobre lo que se pidio.
  if (invertir) {
    potencia = -potencia;
  }

  if (potencia > 0) {
    digitalWrite(pinDirectoA, HIGH);      // Sentido adelante de este canal.
    digitalWrite(pinDirectoB, LOW);
  } else if (potencia < 0) {
    digitalWrite(pinDirectoA, LOW);       // Sentido atras de este canal.
    digitalWrite(pinDirectoB, HIGH);
  } else {
    digitalWrite(pinDirectoA, LOW);       // Ambas entradas bajas: parada libre segura.
    digitalWrite(pinDirectoB, LOW);
  }
  analogWrite(pinEnable, static_cast<uint8_t>(abs(potencia)));
}

void procesarCaracter(char entrante, char origen) {
  // '<' siempre reinicia la captura: un paquete truncado nunca contamina al siguiente.
  if (entrante == '<') {
    capturandoPaquete = true;
    largoBuffer = 0;
    return;
  }

  if (capturandoPaquete) {
    if (entrante == '>') {
      capturandoPaquete = false;
      bufferPaquete[largoBuffer] = '\0';
      procesarPaqueteAnalogico(bufferPaquete);
    } else if (largoBuffer < (sizeof(bufferPaquete) - 1)) {
      bufferPaquete[largoBuffer++] = entrante;
    } else {
      capturandoPaquete = false;          // Paquete mas largo de lo posible: se descarta.
    }
    return;
  }

  // Fuera de un paquete solo tienen sentido los comandos discretos de una letra.
  if (entrante == '\r' || entrante == '\n' || entrante == ' ') {
    return;
  }
  procesarComandoDiscreto(entrante, origen);
}

void procesarPaqueteAnalogico(char *texto) {
  char *separador = strchr(texto, ',');
  if (separador == NULL) {
    return;                               // Sin coma no hay dos ruedas: paquete invalido.
  }
  *separador = '\0';                      // Corta el texto en dos cadenas independientes.

  const int16_t crudoIzquierda = constrain(atoi(texto), -PWM_MAX, PWM_MAX);
  const int16_t crudoDerecha = constrain(atoi(separador + 1), -PWM_MAX, PWM_MAX);

  objetivoIzquierda = escalarPotencia(crudoIzquierda);
  objetivoDerecha = escalarPotencia(crudoDerecha);

  marcaUltimoPaquete = millis();          // Alimenta el failsafe.
  enlaceAnalogicoVivo = true;
}

void procesarComandoDiscreto(char comando, char origen) {
  comando = static_cast<char>(toupper(static_cast<unsigned char>(comando)));

  // Ver el origen y el valor exacto del byte ahorra media hora de dudas: distingue de
  // inmediato lo que uno escribio por USB del ruido que el modulo emite al arrancar.
  if (millis() < SILENCIO_ARRANQUE_MS) {
    Serial.print(F("Ignorado en el arranque ("));
    Serial.print(origen == 'B' ? F("BT") : F("USB"));
    Serial.print(F("): 0x"));
    Serial.println(static_cast<uint8_t>(comando), HEX);
    return;
  }

  const int16_t potencia = escalarPotencia(200);  // Velocidad comoda para modo discreto.

  switch (comando) {
    case 'A': objetivoIzquierda =  potencia; objetivoDerecha =  potencia; break;
    case 'R': objetivoIzquierda = -potencia; objetivoDerecha = -potencia; break;
    case 'I': objetivoIzquierda = -potencia; objetivoDerecha =  potencia; break;
    case 'D': objetivoIzquierda =  potencia; objetivoDerecha = -potencia; break;
    case 'S': objetivoIzquierda =  0;        objetivoDerecha =  0;        break;
    case 'T': ejecutarPruebaDeSentido();                                  return;
    default:
      Serial.print(F("Comando no valido ("));
      Serial.print(origen == 'B' ? F("BT") : F("USB"));
      Serial.print(F("): 0x"));
      Serial.print(static_cast<uint8_t>(comando), HEX);
      Serial.println(F(". Use A, R, I, D, S, T o el paquete <L,R>."));
      return;
  }

  enlaceAnalogicoVivo = false;            // Modo enclavado: el failsafe no lo vigila.
  Serial.print(F("Comando discreto "));
  Serial.print(origen == 'B' ? F("(BT): ") : F("(USB): "));
  Serial.println(comando);
}

/*
  Mueve una rueda a la vez, en un solo sentido a la vez.

  Se prueban las cuatro combinaciones por separado porque cada una usa una mitad
  distinta del puente H: si una sola falla, el problema no es el codigo sino esa
  mitad del driver, su cable de direccion o una escobilla del motor. Con las dos
  ruedas girando juntas ese detalle queda invisible.

  Levantar el carro antes de ejecutarla: se mueve solo durante unos segundos.
*/
void ejecutarPruebaDeSentido() {
  const int16_t potencia = escalarPotencia(150);

  Serial.println(F("PRUEBA DE SENTIDO. Levanta el carro para ver girar las ruedas."));

  Serial.print(F("  1/4 IZQUIERDA hacia ADELANTE"));
  probarCanal(PIN_ENA, PIN_IN1, PIN_IN2, potencia, INVERTIR_IZQUIERDA);

  Serial.print(F("  2/4 IZQUIERDA hacia ATRAS   "));
  probarCanal(PIN_ENA, PIN_IN1, PIN_IN2, -potencia, INVERTIR_IZQUIERDA);

  Serial.print(F("  3/4 DERECHA hacia ADELANTE  "));
  probarCanal(PIN_ENB, PIN_IN3, PIN_IN4, potencia, INVERTIR_DERECHA);

  Serial.print(F("  4/4 DERECHA hacia ATRAS     "));
  probarCanal(PIN_ENB, PIN_IN3, PIN_IN4, -potencia, INVERTIR_DERECHA);

  Serial.println(F("FIN."));
  Serial.println(F("  Si una rueda giro al reves en ambas fases: cambia su INVERTIR_*."));
  Serial.println(F("  Si una rueda solo giro en una fase: revisa el pin de direccion que"));
  Serial.println(F("  esa fase pone en HIGH, su cable al L298N, y esa mitad del puente."));

  // Durante la prueba el mando siguio transmitiendo. Descartar lo acumulado evita que
  // el carro arranque de golpe con una posicion de joystick ya vieja.
  while (bluetooth.available() > 0) bluetooth.read();
  while (Serial.available() > 0) Serial.read();
  capturandoPaquete = false;
  largoBuffer = 0;

  frenar();
}

/*
  Una fase de la prueba: gira, se detiene y deja una pausa para observar.

  Informa los niveles reales que quedan en los pines de direccion, calculados y no
  escritos a mano, para que el mensaje siga siendo cierto si se cambia un INVERTIR_*.
  Con esos niveles se puede medir directamente el pin con un multimetro y saber si el
  problema esta antes o despues del Arduino.
*/
void probarCanal(uint8_t pinEnable, uint8_t pinDirectoA, uint8_t pinDirectoB,
                 int16_t potencia, bool invertir) {
  const int16_t fisica = invertir ? static_cast<int16_t>(-potencia) : potencia;

  Serial.print(F("  ->  D"));
  Serial.print(pinDirectoA);
  Serial.print(fisica > 0 ? F("=HIGH  D") : F("=LOW   D"));
  Serial.print(pinDirectoB);
  Serial.println(fisica > 0 ? F("=LOW") : F("=HIGH"));

  aplicarCanal(pinEnable, pinDirectoA, pinDirectoB, potencia, invertir);
  delay(2000);
  aplicarCanal(pinEnable, pinDirectoA, pinDirectoB, 0, invertir);
  delay(900);
}

void frenar() {
  objetivoIzquierda = 0;
  objetivoDerecha = 0;
  actualIzquierda = 0;
  actualDerecha = 0;
  aplicarCanal(PIN_ENA, PIN_IN1, PIN_IN2, 0, INVERTIR_IZQUIERDA);
  aplicarCanal(PIN_ENB, PIN_IN3, PIN_IN4, 0, INVERTIR_DERECHA);
}
