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

// Puente H. En este carro el motor DERECHO está cableado al canal A
// (pines 5/6/7) y el IZQUIERDO al canal B (pines 8/9/10). moverMotores usa
// ENA/IN1/IN2 como rueda izquierda y ENB/IN3/IN4 como rueda derecha, por eso
// se apuntan a los pines del canal que físicamente mueve cada rueda.
const uint8_t ENA = 8;   // enable rueda IZQUIERDA (canal B del L298N)
const uint8_t IN1 = 9;
const uint8_t IN2 = 10;
const uint8_t ENB = 5;   // enable rueda DERECHA (canal A del L298N)
const uint8_t IN3 = 6;
const uint8_t IN4 = 7;

// En este carro los sensores entregan HIGH sobre la cinta negra y LOW sobre
// la baldosa. Cambie este valor si se reemplazan los sensores.
const bool LINEA_ES_LOW = false;

// Actívalo temporalmente para comprobar sensores en el Monitor Serial.
// En este modo los motores permanecen detenidos.
const bool MODO_PRUEBA_SENSORES = false;

// Rampa de PWM para hallar el piso real de los motores CON el carro en el
// piso (no en el aire: la fricción del chasis es la que importa). Anota el
// valor donde el carro arranca solo y avanza parejo: ese es PWM_MINIMO_UTIL.
const bool MODO_CALIBRAR_PWM = false;
const int PWM_CALIB_INICIAL = 30;
const int PWM_CALIB_PASO = 5;
const uint16_t MS_POR_PASO = 700;

// Los módulos IR sueltan pulsos espurios al pasar por el borde de la cinta.
// Se toman 3 muestras separadas y decide la mayoría.
const uint8_t MUESTRAS_SENSOR = 3;
const uint16_t US_ENTRE_MUESTRAS = 200;

// Piso de torque medido con MODO_CALIBRAR_PWM en la pista: PWM 65. Por debajo
// de ese valor la rueda no mueve el chasis, así que TODA velocidad usada aquí
// debe superar 65 en valor absoluto.
const uint8_t PWM_MINIMO_UTIL = 65;
// Los dos motores no giran igual con el mismo PWM: calibra rueda por rueda
// hasta que, con los sensores al aire (ambos NO), el carro avance recto.
// Sube el PWM de la rueda que se quede atrás.
const uint8_t VELOCIDAD_IZQ = 90;  // rueda izquierda en avance recto
const uint8_t VELOCIDAD_DER = 90;  // rueda derecha en avance recto
// Rueda interna al corregir. NEGATIVO = pivota con reversa: el carro gira
// sobre su eje sin desplazarse. Su magnitud también debe superar el piso; con
// menos de 65 la rueda no gira al revés, solo frena.
const int VELOCIDAD_GIRO = -80;

// Con PWM bajo el L298N no vence la fricción estática: se da un pulso corto a
// PWM alto solo al pasar de parado a movimiento.
const uint8_t PWM_ARRANQUE = 200;
const uint16_t MS_ARRANQUE = 60;

unsigned long ultimaLecturaSerial = 0;
bool motoresDetenidos = true;

bool leerSensor(uint8_t pin) {
  uint8_t votosAlto = 0;
  for (uint8_t i = 0; i < MUESTRAS_SENSOR; i++) {
    votosAlto += (digitalRead(pin) == HIGH) ? 1 : 0;
    delayMicroseconds(US_ENTRE_MUESTRAS);
  }
  return votosAlto > (MUESTRAS_SENSOR / 2);
}

bool esLinea(bool nivelAlto) {
  return LINEA_ES_LOW ? !nivelAlto : nivelAlto;
}

void moverMotores(int velocidadIzq, int velocidadDer) {
  // Velocidad positiva = adelante, negativa = reversa (para pivotar en curvas).
  // El chasis tiene los motores montados en sentido inverso, por eso la
  // dirección eléctrica de avance es INx1=LOW e INx2=HIGH.
  if (velocidadIzq >= 0) {
    digitalWrite(IN1, LOW);
    digitalWrite(IN2, HIGH);
  } else {
    digitalWrite(IN1, HIGH);
    digitalWrite(IN2, LOW);
    velocidadIzq = -velocidadIzq;
  }

  if (velocidadDer >= 0) {
    digitalWrite(IN3, LOW);
    digitalWrite(IN4, HIGH);
  } else {
    digitalWrite(IN3, HIGH);
    digitalWrite(IN4, LOW);
    velocidadDer = -velocidadDer;
  }

  velocidadIzq = constrain(velocidadIzq, 0, 255);
  velocidadDer = constrain(velocidadDer, 0, 255);

  // Un PWM distinto de cero pero bajo el piso de torque no mueve la rueda:
  // solo calienta el motor. Se eleva al mínimo útil.
  if (velocidadIzq > 0 && velocidadIzq < PWM_MINIMO_UTIL) {
    velocidadIzq = PWM_MINIMO_UTIL;
  }
  if (velocidadDer > 0 && velocidadDer < PWM_MINIMO_UTIL) {
    velocidadDer = PWM_MINIMO_UTIL;
  }

  if (motoresDetenidos && (velocidadIzq > 0 || velocidadDer > 0)) {
    analogWrite(ENA, velocidadIzq > 0 ? PWM_ARRANQUE : 0);
    analogWrite(ENB, velocidadDer > 0 ? PWM_ARRANQUE : 0);
    delay(MS_ARRANQUE);
    motoresDetenidos = false;
  }

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
  motoresDetenidos = true;
}

void mostrarLecturasSensores(bool crudoIzquierdo, bool crudoDerecho,
                             bool izquierdoEnLinea, bool derechoEnLinea) {
  if (millis() - ultimaLecturaSerial < 250) {
    return;
  }

  ultimaLecturaSerial = millis();
  Serial.print(F("Izquierdo: "));
  Serial.print(crudoIzquierdo ? 1 : 0);
  Serial.print(F(" (linea: "));
  Serial.print(izquierdoEnLinea ? F("SI") : F("NO"));
  Serial.print(F(") | Derecho: "));
  Serial.print(crudoDerecho ? 1 : 0);
  Serial.print(F(" (linea: "));
  Serial.print(derechoEnLinea ? F("SI") : F("NO"));
  Serial.println(F(")"));
}

void rampaCalibracion() {
  static int pwm = PWM_CALIB_INICIAL;
  static unsigned long ultimoPaso = 0;

  if (millis() - ultimoPaso < MS_POR_PASO) {
    return;
  }
  ultimoPaso = millis();

  digitalWrite(IN1, LOW);
  digitalWrite(IN2, HIGH);
  digitalWrite(IN3, LOW);
  digitalWrite(IN4, HIGH);
  analogWrite(ENA, pwm);
  analogWrite(ENB, pwm);

  Serial.print(F("PWM = "));
  Serial.println(pwm);

  if (pwm < 255) {
    pwm = min(pwm + PWM_CALIB_PASO, 255);
  }
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
  if (MODO_CALIBRAR_PWM) {
    rampaCalibracion();
    return;
  }

  const bool crudoIzquierdo = leerSensor(SENSOR_IZQUIERDO);
  const bool crudoDerecho = leerSensor(SENSOR_DERECHO);
  const bool izquierdoEnLinea = esLinea(crudoIzquierdo);
  const bool derechoEnLinea = esLinea(crudoDerecho);

  if (MODO_PRUEBA_SENSORES) {
    detenerMotores();
    mostrarLecturasSensores(crudoIzquierdo, crudoDerecho, izquierdoEnLinea, derechoEnLinea);
    delay(5);
    return;
  }

  if (!izquierdoEnLinea && !derechoEnLinea) {
    // Ninguno detecta cinta: la línea pasa centrada entre ambos. Avanzar recto.
    moverMotores(VELOCIDAD_IZQ, VELOCIDAD_DER);
  } else if (izquierdoEnLinea && !derechoEnLinea) {
    // La cinta tocó el sensor izquierdo: el carro se corrió a la derecha.
    // Corregir girando a la izquierda (rueda izquierda lenta).
    moverMotores(VELOCIDAD_GIRO, VELOCIDAD_DER);
  } else if (!izquierdoEnLinea && derechoEnLinea) {
    // La cinta tocó el sensor derecho: el carro se corrió a la izquierda.
    // Corregir girando a la derecha (rueda derecha lenta).
    moverMotores(VELOCIDAD_IZQ, VELOCIDAD_GIRO);
  } else {
    // Ambos detectan cinta (cruce o marca ancha): seguir recto.
    moverMotores(VELOCIDAD_IZQ, VELOCIDAD_DER);
  }

  // Más velocidad exige reaccionar antes: el ciclo debe ser corto.
  delay(2);
}
