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

Funcionan tambien escribiendolos en el Monitor Serie del USB, sin necesidad del HC-05.
Son de enclavamiento: quedan fijos hasta el siguiente comando.

## Parametros de control

Todos en `src/main.cpp`, arriba del archivo:

| Constante | Valor | Que hace |
|---|---|---|
| `PWM_MIN` | 60 | Piso de torque. Debajo de este PWM el motor zumba pero no gira. El rango util del joystick se reparte sobre `PWM_MIN..255`, no sobre `0..255` |
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
