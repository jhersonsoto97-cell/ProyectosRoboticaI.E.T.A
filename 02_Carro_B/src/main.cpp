/*
  Smart Car Bluetooth - PlatformIO
  Arduino Mega 2560 + L298N + HC-05

  Comandos recibidos por Bluetooth:
    A = avanzar, R = retroceder, I = izquierda, D = derecha, S = detener.

  Conexion L298N:
    D10 -> ENA (PWM; retire su jumper), D9 -> IN1, D8 -> IN2
    D5  -> ENB (PWM; retire su jumper), D7 -> IN3, D6 -> IN4
    OUT1/OUT2 -> motor izquierdo, OUT3/OUT4 -> motor derecho.

  Conexion HC-05:
    D18/TX1 -> RXD (con divisor de tension si es necesario)
    D19/RX1 <- TXD, 5V -> VCC, GND -> GND.
*/

#include <Arduino.h>  // API de Arduino: Serial, pinMode, digitalWrite y analogWrite.
#include <ctype.h>    // Funcion toupper() para aceptar comandos en minuscula.

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

// PWM entre 0 y 255: 255 entrega la velocidad maxima disponible a los motores.
const uint8_t VELOCIDAD_NORMAL = 255;

// Registra el ultimo comando para no reescribir pines ante comandos repetidos.
char ultimoComando = 'S';

// Declaraciones anticipadas de las funciones usadas por el programa.
void configurarPines();
void establecerVelocidad(uint8_t velocidadIzquierda, uint8_t velocidadDerecha);
void avanzar();
void retroceder();
void girarIzquierda();
void girarDerecha();
void frenar();
void procesarComando(char comando);

void setup() {
  Serial.begin(115200);                  // Habilita mensajes de diagnostico por USB.
  bluetooth.begin(BAUD_BLUETOOTH);       // Habilita el puerto hardware conectado al HC-05.
  configurarPines();                     // Prepara todas las salidas hacia el L298N.
  frenar();                              // Mantiene el carro detenido al encender.
  Serial.println(F("Smart Car listo. Esperando Bluetooth..."));
}

void loop() {
  // Procesa todos los caracteres pendientes en el buffer del HC-05.
  while (bluetooth.available() > 0) {
    const char comando = static_cast<char>(bluetooth.read()); // Lee un caracter recibido.

    // Algunas aplicaciones agregan retorno de carro o salto de linea; se ignoran.
    if (comando != '\r' && comando != '\n') {
      // Muestra el dato crudo recibido para confirmar que el HC-05 esta comunicando.
      Serial.print(F("Bluetooth recibido: '"));
      Serial.print(comando);
      Serial.println(F("'"));

      procesarComando(comando);          // Ejecuta solo los comandos de control validos.
    }
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

void establecerVelocidad(uint8_t velocidadIzquierda, uint8_t velocidadDerecha) {
  analogWrite(PIN_ENA, velocidadIzquierda); // Ajusta potencia del motor izquierdo.
  analogWrite(PIN_ENB, velocidadDerecha);   // Ajusta potencia del motor derecho.
}

void avanzar() {
  digitalWrite(PIN_IN1, HIGH);             // Sentido adelante del motor izquierdo.
  digitalWrite(PIN_IN2, LOW);
  digitalWrite(PIN_IN3, HIGH);             // Sentido adelante del motor derecho.
  digitalWrite(PIN_IN4, LOW);
  establecerVelocidad(VELOCIDAD_NORMAL, VELOCIDAD_NORMAL);
}

void retroceder() {
  digitalWrite(PIN_IN1, LOW);              // Sentido atras del motor izquierdo.
  digitalWrite(PIN_IN2, HIGH);
  digitalWrite(PIN_IN3, LOW);              // Sentido atras del motor derecho.
  digitalWrite(PIN_IN4, HIGH);
  establecerVelocidad(VELOCIDAD_NORMAL, VELOCIDAD_NORMAL);
}

void girarIzquierda() {
  digitalWrite(PIN_IN1, LOW);              // Izquierdo atras.
  digitalWrite(PIN_IN2, HIGH);
  digitalWrite(PIN_IN3, HIGH);             // Derecho adelante: giro sobre el eje.
  digitalWrite(PIN_IN4, LOW);
  establecerVelocidad(VELOCIDAD_NORMAL, VELOCIDAD_NORMAL);
}

void girarDerecha() {
  digitalWrite(PIN_IN1, HIGH);             // Izquierdo adelante.
  digitalWrite(PIN_IN2, LOW);
  digitalWrite(PIN_IN3, LOW);              // Derecho atras: giro sobre el eje.
  digitalWrite(PIN_IN4, HIGH);
  establecerVelocidad(VELOCIDAD_NORMAL, VELOCIDAD_NORMAL);
}

void frenar() {
  establecerVelocidad(0, 0);               // Corta la potencia PWM de ambos motores.
  digitalWrite(PIN_IN1, LOW);              // Deja las entradas del L298N en estado seguro.
  digitalWrite(PIN_IN2, LOW);
  digitalWrite(PIN_IN3, LOW);
  digitalWrite(PIN_IN4, LOW);
}

void procesarComando(char comando) {
  comando = static_cast<char>(toupper(static_cast<unsigned char>(comando)));

  // No realiza cambios electricos si la aplicacion repite el mismo comando.
  if (comando == ultimoComando) {
    return;
  }

  switch (comando) {
    case 'A': avanzar();        break; // Avanzar.
    case 'R': retroceder();     break; // Retroceder.
    case 'I': girarIzquierda(); break; // Izquierda.
    case 'D': girarDerecha();   break; // Derecha.
    case 'S': frenar();         break; // Stop / detener.
    default:
      // Informa el error, pero conserva el movimiento que el carro ya tenia.
      Serial.println(F("Comando no valido. Use A, R, I, D o S."));
      return;
  }

  ultimoComando = comando;              // Guarda el ultimo comando ejecutado.
  Serial.print(F("Comando Bluetooth: "));
  Serial.println(comando);              // Informa por USB para facilitar las pruebas.
}
