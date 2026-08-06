# 03 — Carro explorador con sonar orientable

Carro con ESP32 que levanta su propia red WiFi y sirve el mando desde el navegador. El
servo barre con el ultrasonico y el entorno se dibuja en tiempo real; parando el carro
se puede levantar el plano del lugar.

**Sin instalar nada**: cualquiera se conecta al WiFi del carro y abre el navegador.

## Como se compila

Proyecto **ESP-IDF v5.3.2**, sin PlatformIO. Desde la extension de Espressif en VS Code,
o por linea de comandos:

```powershell
C:\Espressif\frameworks\esp-idf-v5.3.2\export.ps1
cd 03_Carro_ESP32
idf.py set-target esp32
idf.py build
idf.py -p COMx flash monitor
```

## Mapa de pines

Placa **DevKit v1 de 30 pines**, modulo WROOM-32.

| Funcion | GPIO |
|---|---|
| Motor izquierdo — IN1 | 26 |
| Motor izquierdo — IN2 | 27 |
| Motor izquierdo — ENA (PWM) | 25 |
| Motor derecho — IN3 | 32 |
| Motor derecho — IN4 | 33 |
| Motor derecho — ENB (PWM) | 14 |
| Servo — senal | 18 |
| HC-SR04 — TRIG | 19 |
| HC-SR04 — ECHO | 21 (**con divisor**) |

### Pines descartados y por que

| GPIO | Motivo |
|---|---|
| 34, 35, 36, 39 | Solo entrada, no pueden sacar PWM |
| 12 | Si esta alto al arrancar, el chip configura la flash a 1.8 V y deja de bootear |
| 0, 5 | Pines de arranque: deciden el modo de boot |
| 1, 3 | Los usa el USB para programar y ver el monitor |

### El ECHO necesita divisor

El HC-SR04 saca **5 V** por ECHO y el ESP32 **no tolera** esa tension en sus entradas.
Conectarlo directo degrada el pin y con el tiempo lo mata.

```
ECHO ---[ 1k ]---+--- GPIO 21
                 |
               [ 2k ]
                 |
                GND
```

El TRIG si va directo: es entrada del sensor y reconoce 3.3 V como nivel alto.

### Alimentacion del servo

A la **bateria**, nunca al 3V3 del ESP32. El servo tira picos de unos 500 mA al arrancar
y esa caida reinicia la placa. Capacitor de 470 uF entre su alimentacion y GND, y todas
las tierras unidas. **Es el fallo mas comun en este tipo de armado.**

## Como se usa

1. Encender el carro
2. Conectarse al WiFi `SmartCar-03`, clave `explorador`
3. Abrir `http://192.168.4.1`

| Control | Que hace |
|---|---|
| Stick izquierdo | Acelerador |
| Stick derecho | Direccion |
| ESCANEAR | Detiene el carro y levanta el plano girando sobre su eje |
| LIMPIAR | Borra los ecos y el plano de la pantalla |
| PARO | Detiene de inmediato |

## Por que el escaneo se hace parado

El error de odometria nace del **desplazamiento**: sin encoders, la unica forma de saber
cuanto avanzo el carro es contar cuanto PWM se le mando, y eso se degrada tanto que en
pocos metros el mapa queda torcido.

Escaneando **quieto** ese error no existe. El barrido desde un punto fijo es
geometricamente exacto, y por eso el plano que sale de aqui es confiable aunque el
chasis no tenga realimentacion.

## Arquitectura

```
        ESP32 (punto de acceso "SmartCar-03")
   +-------------------------------------------+
   |  HTTP  -> pagina embebida en el firmware  |
   |  WS    -> control 20 Hz + telemetria      |
   |                                           |
   |  nucleo 0 : barrido del sonar             |
   |  nucleo 1 : servidor y lazo de control    |
   +-------------------------------------------+
                    | LEDC
              driver -> 2 motores
```

Los nucleos se reparten a proposito: una medicion del ultrasonico bloquea hasta 25 ms
esperando el eco, y ese tiempo no puede robarselo al servidor ni al lazo de control.

| Archivo | Responsabilidad |
|---|---|
| `main/config.h` | Todo lo ajustable: pines, limites, calibracion |
| `main/drive.c` | Traccion, rampa, failsafe, giro sobre el eje |
| `main/sonar.c` | Servo, medicion y escaneo estacionado |
| `main/web.c` | Punto de acceso, servidor HTTP y WebSocket |
| `main/web_page.h` | Interfaz, embebida en el firmware |

## Protocolo

```
navegador -> carro   {"t":"c","l":-255..255,"r":-255..255}
                     {"t":"scan"}
                     {"t":"stop"}

carro -> navegador   {"t":"s","a":grados,"d":cm}
                     {"t":"p","v":0..100}
                     {"t":"e","pts":[{"a":..,"d":..}, ...]}
```

Los angulos van referidos al **frente del carro**: 0 es hacia adelante y crece hacia la
derecha. El cero del servo es un tope mecanico que no significa nada para quien mira la
pantalla, asi que la conversion se hace en el firmware y el resto del sistema trabaja
siempre en el marco del carro.

El navegador transmite cada 50 ms aunque los sticks no cambien: ese flujo constante es
lo que alimenta el failsafe, de modo que una caida de WiFi o un navegador que se cierra
terminan en parada y no en un carro descontrolado.

## Hardware

| Elemento | Detalle |
|---|---|
| Microcontrolador | ESP32 DevKit v1, 30 pines, modulo WROOM-32 |
| Sensado | Servomotor con HC-SR04 sobre el brazo |
| Traccion | Dos motores DC |
| Encoders | **No tiene** |
| IMU | **No tiene** |

## Por que el radar clasico no alcanza

La combinacion servo + ultrasonico + ESP32 tiene decenas de implementaciones publicas,
casi todas iguales: el sensor barre 180 grados y dibuja un radar en pantalla. El carro
no se mueve, y la imagen se borra en cada barrido.

Lo que casi nadie hace con este hardware es **acumular** esos barridos mientras el carro
se desplaza, para construir un mapa que persiste. Ahi esta el margen para hacer algo que
no sea una repeticion.

## Investigacion previa

### Lo que ya existe y se puede reutilizar

| Proyecto | Que aporta | Que habria que cambiar |
|---|---|---|
| [abedshaaban/arduino-radar](https://github.com/abedshaaban/arduino-radar) | ESP32 + HC-SR04 + servo con interfaz web por WebSocket, control de inicio y parada, modo punto de acceso | El radar es estatico y sin memoria: no acumula ni relaciona barridos |
| [mhhridoy7907/esp32-radar-system](https://github.com/mhhridoy7907/esp32-radar-system) | SoftAP y render polar en el navegador, sin router de por medio | Mismo limite: sin desplazamiento ni mapa persistente |
| [ClemensAtElektor/Lidar-controlled-autonomous-vehicle](https://github.com/ClemensAtElektor/Lidar-controlled-autonomous-vehicle) | Vehiculo autonomo con ESP32 que navega una habitacion sin chocar | Usa LIDAR 2D real, no sonar. La logica de navegacion si es transferible |
| [RawFish69/ESP32-RC-Car](https://github.com/RawFish69/ESP32-RC-Car) | Chasis ESP32 con TB6612FNG y mapeo del entorno | Depende de un LD06, sensor que este proyecto no tiene |
| [Zbotic — Ultrasonic Grid Mapping](https://zbotic.in/ultrasonic-grid-mapping-2d-arduino-robot/) | Grilla de ocupacion 2D con robot Arduino y ultrasonido | Referencia directa del enfoque, en version simplificada |

### Limites fisicos que conviene tener presentes

| Dato | Valor | Consecuencia |
|---|---|---|
| Precision de distancia del HC-SR04 | +/- 3 mm | Suficiente; no es la limitante |
| Apertura del haz | ~15 grados | Contra los 0.1 grados de un LIDAR. Un obstaculo angosto se dibuja como un arco ancho |
| Alcance util en interiores | ~4 m | Superficies blandas y angulos agudos devuelven eco debil o nulo |
| Deriva de odometria sin IMU | mapa confiable hasta ~4x4 m | El error de rumbo se acumula y tuerce el mapa |
| Deriva agregando IMU | ~8x8 m | Un giroscopio corrige el rumbo, que es donde mas duele el error |

Fuentes: [Zbotic](https://zbotic.in/ultrasonic-grid-mapping-2d-arduino-robot/),
[Sonar-based SLAM using Occupancy Grid Mapping and Dead Reckoning](https://www.researchgate.net/publication/331856014_Sonar-based_SLAM_Using_Occupancy_Grid_Mapping_and_Dead_Reckoning).

## Decisiones abiertas

1. Si el chasis tiene encoders, y con cuantos pulsos por vuelta
2. Que driver de motores lleva
3. Si hay presupuesto para sumar IMU o encoders
4. Cual de los conceptos candidatos se persigue
5. Donde se demuestra el proyecto y con cuanto tiempo
