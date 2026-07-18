#include <Arduino.h>

/*
  Carro seguidor de línea con Arduino Mega + L298N (o puente H equivalente).

  Conexiones sugeridas:
    Sensor IR izquierdo (DO) -> D2
    Sensor IR derecho   (DO) -> D3
    L298N: ENA -> D5, IN1 -> D6, IN2 -> D7
           ENB -> D8, IN3 -> D9, IN4 -> D10

  Alimente los motores con una fuente externa y conecte su GND al GND del Mega.
*/

// Sensores de línea
const uint8_t SENSOR_IZQUIERDO = 2;
const uint8_t SENSOR_DERECHO = 3;

// Puente H: motor izquierdo (A) y motor derecho (B)
const uint8_t ENA = 5;
const uint8_t IN1 = 6;
const uint8_t IN2 = 7;
const uint8_t ENB = 8;
const uint8_t IN3 = 9;
const uint8_t IN4 = 10;

// La mayoría de módulos IR digitales entregan LOW sobre línea negra.
// Cámbielo a HIGH si sus sensores leen HIGH sobre la línea negra.
const bool LINEA_ES_LOW = true;

const uint8_t VELOCIDAD_BASE = 170;
const uint8_t VELOCIDAD_GIRO = 130;

bool ultimoGiroFueDerecha = true;

bool estaSobreLinea(uint8_t pin) {
  return digitalRead(pin) == (LINEA_ES_LOW ? LOW : HIGH);
}

void moverMotores(uint8_t velocidadIzq, uint8_t velocidadDer) {
  // Ambos motores hacia adelante.
  // El chasis tiene los motores montados en sentido inverso, por eso la
  // dirección eléctrica de avance es INx1=LOW e INx2=HIGH.
  digitalWrite(IN1, LOW);
  digitalWrite(IN2, HIGH);
  digitalWrite(IN3, LOW);
  digitalWrite(IN4, HIGH);
  analogWrite(ENA, velocidadIzq);
  analogWrite(ENB, velocidadDer);
}

void detenerMotores() {
  analogWrite(ENA, 0);
  analogWrite(ENB, 0);
  digitalWrite(IN1, LOW);
  digitalWrite(IN2, LOW);
  digitalWrite(IN3, LOW);
  digitalWrite(IN4, LOW);
}

void setup() {
  pinMode(SENSOR_IZQUIERDO, INPUT);
  pinMode(SENSOR_DERECHO, INPUT);

  pinMode(ENA, OUTPUT);
  pinMode(IN1, OUTPUT);
  pinMode(IN2, OUTPUT);
  pinMode(ENB, OUTPUT);
  pinMode(IN3, OUTPUT);
  pinMode(IN4, OUTPUT);

  Serial.begin(115200);
  detenerMotores();
  Serial.println(F("Seguidor de linea listo"));
}

void loop() {
  const bool izquierdoEnLinea = estaSobreLinea(SENSOR_IZQUIERDO);
  const bool derechoEnLinea = estaSobreLinea(SENSOR_DERECHO);

  if (izquierdoEnLinea && derechoEnLinea) {
    // Ambos sensores ven la línea: avanzar centrado.
    moverMotores(VELOCIDAD_BASE, VELOCIDAD_BASE);
  } else if (izquierdoEnLinea && !derechoEnLinea) {
    // La línea queda a la izquierda: reducir motor izquierdo para corregir.
    moverMotores(VELOCIDAD_GIRO, VELOCIDAD_BASE);
    ultimoGiroFueDerecha = false;
  } else if (!izquierdoEnLinea && derechoEnLinea) {
    // La línea queda a la derecha: reducir motor derecho para corregir.
    moverMotores(VELOCIDAD_BASE, VELOCIDAD_GIRO);
    ultimoGiroFueDerecha = true;
  } else {
    // Se perdió la línea: buscarla girando hacia el último lado detectado.
    if (ultimoGiroFueDerecha) {
      moverMotores(VELOCIDAD_BASE, 0);
    } else {
      moverMotores(0, VELOCIDAD_BASE);
    }
  }

  delay(10);
}
