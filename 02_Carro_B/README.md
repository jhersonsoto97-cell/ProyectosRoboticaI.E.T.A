# Smart Car Bluetooth — Arduino Mega 2560

Proyecto PlatformIO para un carro de dos motores DC, controlado por Bluetooth mediante un HC-05 y un driver L298N.

## Abrir y cargar en PlatformIO

1. Instale la extension **PlatformIO IDE** en VS Code.
2. Abra esta carpeta: `02_Carro_B`.
3. Conecte el Arduino Mega por USB.
4. Ejecute **PlatformIO: Build** para compilar.
5. Ejecute **PlatformIO: Upload** para cargar el programa.
6. Abra el Monitor Serie a **115200 baudios** solo para ver diagnosticos. La comunicacion del HC-05 funciona a 9600 baudios internamente.

El archivo que PlatformIO compila es `src/main.cpp`. El archivo `SmartCarBluetoothMega.ino` se conserva como version compatible con Arduino IDE.

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

## Comandos de la aplicacion movil

| Comando | Accion |
|---|---|
| `A` | Avanzar |
| `R` | Retroceder |
| `I` | Girar a la izquierda |
| `D` | Girar a la derecha |
| `S` | Detener |

La velocidad esta configurada al maximo (`VELOCIDAD_NORMAL = 255`). Puede ajustarla entre 0 y 255 en `src/main.cpp`.
