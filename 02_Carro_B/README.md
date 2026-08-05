# Smart Car Bluetooth — Arduino Mega 2560

Proyecto PlatformIO para un carro de dos motores DC, controlado por Bluetooth mediante un HC-05 y un driver L298N.

## Contenido

| Carpeta | Que es |
|---|---|
| `src/` | Firmware del Arduino Mega 2560 (PlatformIO) |
| `android_app/` | Mando Android nativo en Kotlin + Jetpack Compose ([README](android_app/README.md)) |
| `simulador/` | Carro simulado en el PC para probar sin hardware ([README](simulador/README.md)) |

Las tres partes hablan el mismo protocolo `<L,R>`, de modo que la app no distingue si
del otro lado hay un Arduino o el simulador:

```
                        <L,R> por Bluetooth SPP
   App Android  ─────────────────────────────────►  HC-05 ─► Arduino Mega ─► L298N
        │
        │               <L,R> por TCP
        └─────────────────────────────────────────►  car_simulator.py (PC)
```

## Abrir y cargar en PlatformIO

1. Instale la extension **PlatformIO IDE** en VS Code.
2. Abra esta carpeta: `02_Carro_B`.
3. Conecte el Arduino Mega por USB.
4. Ejecute **PlatformIO: Build** para compilar.
5. Ejecute **PlatformIO: Upload** para cargar el programa.
6. Abra el Monitor Serie a **115200 baudios** solo para ver diagnosticos. La comunicacion del HC-05 funciona a 9600 baudios internamente.

El archivo que PlatformIO compila es `src/main.cpp`.

## Conexiones

| Arduino Mega | L298N | Funcion |
|---|---|---|
| D10 | ENA | PWM motor izquierdo; retire el jumper ENA |
| D9 | IN1 | Direccion motor izquierdo |
| D8 | IN2 | Direccion motor izquierdo |
| D5 | ENB | PWM motor derecho; retire el jumper ENB |
| D7 | IN3 | Direccion motor derecho |
| D6 | IN4 | Direccion motor derecho |
| GND | GND | Tierra comun |

| Arduino Mega | HC-05 | Funcion |
|---|---|---|
| D18 / TX1 | RXD | Use un divisor de tension si el modulo no tiene adaptacion a 5 V |
| D19 / RX1 | TXD | Datos Bluetooth hacia el Mega |
| 5V | VCC | Verifique la serigrafia de su placa HC-05 |
| GND | GND | Tierra comun |

Conecte el motor izquierdo a `OUT1/OUT2` y el derecho a `OUT3/OUT4`. Alimente los motores con una bateria adecuada conectada al L298N y una todas las tierras. No los alimente desde el pin 5V del Mega.

## Protocolo analogico (app Android)

El firmware acepta paquetes de potencia por rueda:

```
<L,R>     L y R enteros entre -255 y 255. El signo define el sentido de giro.
```

`<180,-120>` avanza la rueda izquierda y retrocede la derecha. La app de `android_app/`
transmite 20 paquetes por segundo, cambien o no los joysticks.

## Comandos discretos (monitor serie o apps genericas de Bluetooth)

| Comando | Accion |
|---|---|
| `A` | Avanzar |
| `R` | Retroceder |
| `I` | Girar a la izquierda |
| `D` | Girar a la derecha |
| `S` | Detener |
| `T` | Prueba de sentido: mueve una rueda a la vez para calibrar `INVERTIR_*` |

Funcionan tambien escribiendolos en el Monitor Serie del USB, sin necesidad del modulo.
Son de enclavamiento: quedan fijos hasta el siguiente comando.

## Calibracion del sentido de giro

Los dos motores van montados en espejo, uno a cada lado del chasis. Si se cablean
simetricamente al L298N **uno gira al reves por geometria**, no por error de armado:
pedir "adelante" hace que el carro gire sobre su eje en vez de avanzar.

Se corrige con dos constantes en `src/main.cpp`:

```cpp
const bool INVERTIR_IZQUIERDA = true;
const bool INVERTIR_DERECHA = false;
```

Para saber cual poner en `true`, enviar **`T`** por el Monitor Serie (115200):

1. Levantar el carro para que las ruedas giren libres
2. Escribir `T` y enviar
3. Gira **solo la rueda izquierda**, que debe ir hacia adelante
4. Pausa, y gira **solo la derecha**, que tambien debe ir hacia adelante
5. La que gire al reves es la que necesita su `INVERTIR_*` en `true`

Se prueba una rueda a la vez a proposito: con las dos girando es imposible distinguir
un motor invertido de un giro pedido a proposito.

La alternativa por hardware es intercambiar los dos cables de ese motor en el borne del
L298N. Da el mismo resultado; la constante evita tener que desarmar.

## Emparejar la velocidad de los motores

Dos motorreductores del mismo lote nunca giran igual. La tolerancia de fabricacion de
estos TT ronda el **20 %**, y a eso se suma la friccion propia de cada caja reductora.
Con el mismo PWM uno empuja mas y el carro se abre hacia el lado del mas debil. No es
un defecto del armado ni del codigo: es lo normal en motores sin realimentacion.

Se corrige con cuatro constantes en `src/main.cpp`:

```cpp
const int16_t TRIM_IZQUIERDA = 95;       // recorta el techo del motor rapido
const int16_t TRIM_DERECHA = 100;
const int16_t PWM_MIN_IZQUIERDA = 60;    // piso de torque de cada motor
const int16_t PWM_MIN_DERECHA = 60;
```

### Ajuste del trim, a velocidad de crucero

1. Bateria **cargada**. Una bateria baja exagera la disparidad y la calibracion sale mal.
2. Marcar una recta de 3 m en piso liso.
3. En la app, poner **LIMITE en 70 %**. A fondo no sirve: el motor rapido ya esta en 255
   y solo se puede compensar recortandolo, con lo que se pierde velocidad maxima.
4. Soltar el carro sobre la linea con el acelerador al fondo y medir cuanto se desvia
   lateralmente al cabo de los 3 m.
5. Se abre hacia la derecha significa que la **izquierda** corre mas: bajar
   `TRIM_IZQUIERDA`. Se abre hacia la izquierda: bajar `TRIM_DERECHA`.
6. Punto de partida practico: **1 % de trim por cada 5 cm** de desviacion en 3 m.
7. Repetir hasta que la desviacion baje de 10 cm. Mas fino que eso no se sostiene:
   el piso, el desgaste de las llantas y la carga de la bateria mueven el resultado.

Bajar siempre, nunca subir por encima de 100. El trim solo recorta.

### Ajuste del piso, a baja velocidad

Es donde mas se nota la diferencia, porque cada motor rompe la inercia a un PWM distinto.

1. Levantar el carro y enviar **`T`**.
2. Observar cual rueda arranca con dificultad o zumba antes de girar.
3. Subir el `PWM_MIN_*` de esa rueda de a 5 hasta que ambas arranquen parejo.

Subir el piso de la rueda floja, no bajar el de la otra: bajarlo la deja sin torque.

### El arreglo de fondo

Todo esto es **lazo abierto**: se compensa una disparidad medida una vez, y deja de
valer cuando cambia la carga, el piso o la bateria. Las dos salidas reales son:

| Opcion | Costo aprox. | Que resuelve |
|---|---|---|
| Encoders en las ruedas | 20.000 COP el par | Control de velocidad real por rueda con PID |
| IMU MPU6050 | 12.000 COP | Mantiene el rumbo aunque las ruedas no coincidan |

Para avanzar derecho el MPU6050 es la opcion mas barata y directa: cierra el lazo sobre
lo que de verdad importa, que es el rumbo, en vez de sobre las revoluciones de cada rueda.

## Parametros de control

Todos en `src/main.cpp`, arriba del archivo:

| Constante | Valor | Que hace |
|---|---|---|
| `PWM_MIN_IZQUIERDA` / `_DERECHA` | 60 | Piso de torque de cada motor. Debajo de ese PWM zumba pero no gira. El rango util del joystick se reparte sobre `PWM_MIN..techo`, no sobre `0..255` |
| `TRIM_IZQUIERDA` / `_DERECHA` | 95 / 100 | Recorta el techo del motor mas rapido para que el carro avance derecho |
| `RAMPA_PASO` | 12 | Cambio maximo de PWM por tick. Limita el `di/dt` del arranque para que el pico de corriente no reinicie el Mega. 0 a 255 en ~210 ms |
| `TICK_MS` | 10 | Periodo del lazo de rampa. Fija la pendiente real de aceleracion |
| `FAILSAFE_MS` | 400 | Si el enlace analogico se corta por mas de este tiempo, el carro frena solo |

**Por que el failsafe importa:** sin el, una desconexion del HC-05 durante un avance deja
el carro acelerado indefinidamente. Con el, cualquier fallo de enlace, cierre de app o
telefono descargado termina en parada. Los comandos discretos no lo activan porque son
de enclavamiento por diseno.

## Mejora pendiente de hardware

El L298N es un puente H de transistores bipolares: pierde cerca de **2 V** entre la
alimentacion y el motor. Con una bateria de 7.4 V los motores reciben unos 5.4 V.
Un **TB6612FNG** (~3 USD) es reemplazo directo con caida de ~0.1 V y entrega bastante
mas torque real con la misma bateria.
