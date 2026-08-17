# 📡 Carro Explorador · Sonar y WiFi propio

El más completo de los tres. Este carro:

- Crea **su propia red WiFi**, no necesita ninguna red del colegio.
- Lleva un **sonar** que barre de lado a lado y dibuja lo que hay alrededor.
- Se frena solo si tiene un obstáculo pegado al frente (el **escudo**).
- Se maneja desde la app, **o desde el navegador sin instalar nada**.

Esta guía te lleva desde la caja de materiales hasta el carro andando y dibujando su
entorno. Sigue los pasos en orden.

¿Palabras raras? Al final hay un [glosario](#glosario).

---

## Paso 0 — Reúne los materiales

### Electrónica

- [ ] 1 × **ESP32 DevKit v1** de 30 pines, con su cable USB
- [ ] 1 × Driver L298N
- [ ] 1 × Sensor ultrasónico **HC-SR04**
- [ ] 1 × Servomotor **SG90** (el pequeño azul)
- [ ] 2 × Resistencias: una de **1 kΩ** y una de **2 kΩ**
- [ ] 2 × Condensadores electrolíticos de **470 µF**
- [ ] Cables Dupont

### Chasis

- [ ] 1 × Chasis de robot de dos ruedas
- [ ] 2 × Motores amarillos (TT) con sus soportes
- [ ] 2 × Ruedas + 1 × rueda loca
- [ ] 1 × Soporte para el servo con el HC-SR04 (el "brazo" del sonar)
- [ ] Tornillos M3 y separadores

### Batería

- [ ] 4 × Celdas **18650**
- [ ] 2 × Portapilas de 2 celdas cada uno
- [ ] 1 × Interruptor

### Y además

- [ ] Un celular o tablet Android
- [ ] Un computador con VS Code
- [ ] Destornillador, alicate, y multímetro si hay

---

## Paso 1 — Arma el chasis

1. **Pon los motores** con sus soportes en U, uno a cada lado, ejes hacia afuera.
2. **Mete las ruedas** a presión, hasta el fondo.
3. **Instala la rueda loca** con sus separadores. El chasis debe quedar paralelo al piso.
4. **Los portapilas abajo.** Pesan, y abajo bajan el centro de gravedad.
5. **Las placas arriba**, con separadores o cinta doble faz:
   - El **ESP32** con su USB accesible desde el borde.
   - El **L298N** cerca de los motores.

### El brazo del sonar

Es lo que distingue a este carro. El servo va **al frente y bien alto**, con el HC-SR04
montado encima:

```
              visto de lado

              [HC-SR04]     <- el sensor, mirando al frente
                 |
              [servo]       <- gira el sensor de lado a lado
                 |
        =====================
         (o)            ||
                      (rueda)
```

Tres cosas:

- **Al frente y despejado.** Si una placa o un cable le queda por delante, el sonar la
  detecta como si fuera una pared y el radar muestra un obstáculo que no existe.
- **Bien firme.** Si el soporte se mueve, cada medición apunta a un lugar distinto y el
  dibujo sale desordenado.
- **No atornilles el brazo todavía.** Primero hay que centrar el servo, y eso se hace con
  el carro encendido. Es el [Paso 7](#paso-7--centra-el-brazo-del-sonar).

> **Los cables del TRIG y del ECHO, separados el uno del otro.** Si van pegados o
> trenzados, el pulso del TRIG se "cuela" al cable del ECHO y el carro cree que hay algo a
> un centímetro. La autoprueba lo detecta y te avisa.

---

## Paso 2 — Conecta los cables

### El driver L298N

| ESP32 | L298N | Mueve |
|---|---|---|
| `GPIO 14` | `ENA` | rueda izquierda |
| `GPIO 27` | `IN1` | rueda izquierda |
| `GPIO 26` | `IN2` | rueda izquierda |
| `GPIO 25` | `IN3` | rueda derecha |
| `GPIO 33` | `IN4` | rueda derecha |
| `GPIO 32` | `ENB` | rueda derecha |

Estos seis pines quedan **seguidos y en el mismo orden** que el conector del L298N, así
que el manojo de cables va derecho, sin cruces:

```
  ESP32   14    27    26    25    33    32
          |     |     |     |     |     |
  L298N  ENA   IN1   IN2   IN3   IN4   ENB
```

**Quita los jumpers de `ENA` y `ENB`.** Igual que en los otros carros: mientras estén
puestos, los motores van siempre a fondo.

### El servo y el sonar

| ESP32 | Va a |
|---|---|
| `GPIO 18` | Cable de **señal** del servo (el naranja o amarillo) |
| `GPIO 19` | `TRIG` del HC-SR04 |
| `GPIO 21` | `ECHO` del HC-SR04, **con divisor** |

### ⚠️ El ECHO necesita divisor, sin excepción

El HC-SR04 saca **5 voltios** por el pin `ECHO`. El ESP32 **no aguanta** 5 V en sus
entradas: se conecta directo y el pin se va dañando hasta que deja de funcionar.

Dos resistencias lo arreglan:

```
   ECHO ---[ 1 kΩ ]---+--- GPIO 21
                      |
                   [ 2 kΩ ]
                      |
                     GND
```

El `TRIG` sí va directo: ahí el ESP32 es el que habla, y el sensor entiende sus 3.3 V como
señal válida.

### Por qué esos pines y no otros

El ESP32 tiene muchos pines, pero no todos sirven:

| Pines | Por qué no se usan |
|---|---|
| 34, 35, 36, 39 | Solo sirven para leer, no para sacar señal |
| 12 | Si está en alto al encender, el chip no arranca |
| 0, 5 | Deciden cómo arranca el chip |
| 1, 3 | Los usa el USB para programar |

---

## Paso 3 — Conecta la alimentación

Esta parte tiene más cuidado que en los otros carros, porque hay tres cosas con hambres
muy distintas: los motores tiran mucha corriente, el servo da picos cortos y fuertes, y el
ESP32 necesita una alimentación estable o se reinicia.

### La batería: cuatro celdas, pero en paralelo

Los dos portapilas de 2 celdas se unen **en paralelo**: positivo con positivo, negativo
con negativo.

```
  Portapilas A:  [18650]-[18650] --+-- + --> interruptor --> L298N
                                    |
  Portapilas B:  [18650]-[18650] --+

                 los dos negativos ------ GND
```

| | Un portapilas | Los dos en paralelo |
|---|---|---|
| Voltaje | 7.4 V | **7.4 V**, igual |
| Duración | ~2500 mAh | **~5000 mAh**, el doble |
| Resistencia interna | ~0.1 Ω | **~0.05 Ω**, la mitad |

Lo que más importa es lo último: con la mitad de resistencia interna, el voltaje se cae la
mitad cuando arrancan los motores. Menos caídas, menos reinicios.

### 🚨 Nunca los conectes en serie

Cuatro celdas en serie dan **16.8 V**. A los motores, que son de 3 a 6 V, les llegarían
casi 13 V y **se queman**.

### 🚨 Antes de unir los dos portapilas

1. **Mide cada uno por separado** con el multímetro. La diferencia entre ambos tiene que
   ser **menor a 0.1 V**. Si uno está cargado y el otro no, al unirlos el lleno se
   descarga sobre el vacío con varios amperios: se calientan y se dañan.
2. **Revisa la polaridad dos veces.** Positivo con positivo. Al revés es un cortocircuito
   directo, y una celda 18650 entrega veinte amperios sin despeinarse.

### Cómo se reparte la corriente

```
                        +--> ESP32 pin VIN                + 470 µF
  4x 18650 (7.4V) ------+
                        +--> L298N Vin --+--> motores
                                         |
                                         +--> salida 5V --+--> HC-SR04
                                                          |
                                                          +--> SERVO  + 470 µF

  todos los negativos -------------------------------------- GND común
```

El **ESP32 cuelga directamente de la batería**, no del riel de 5 V del driver. Es a
propósito: si colgara del mismo riel que el servo, cada tirón del servo le movería la
alimentación y lo reiniciaría en plena maniobra.

| Qué | Cuánto consume |
|---|---|
| ESP32 | 150 mA normales, picos de 250 |
| HC-SR04 | 15 mA |
| Servo | 150 mA, **picos de 700** |
| Motores | 400 a 800 mA, picos de 2 A |

### Los condensadores

Uno **pegado al servo** y otro cerca del ESP32, cada uno entre alimentación y GND.

Un servo pide hasta 700 mA de golpe, en milisegundos. El condensador guarda un poquito de
energía justo al lado y entrega ese pico localmente, para que la caída no llegue al resto
del carro.

**El del servo va lo más cerca posible del servo.** Lo que causa la caída es el propio
cable; puesto lejos, en la placa, no sirve de nada.

> Los condensadores electrolíticos **tienen polaridad**. La pata larga es el positivo, y
> el lado con la franja clara es el negativo. Al revés se hinchan y revientan.

### Las tierras

Todos los negativos unidos en un punto: ESP32, L298N, servo, sensor y batería.

Con una excepción importante: **el negativo del servo va al `GND` del L298N, no al del
ESP32**. Si la corriente de pico del servo vuelve por la tierra del ESP32, le corre la
referencia y produce lecturas raras y reinicios.

### 🚨 Lo que nunca hay que hacer

| Nunca | Qué pasa |
|---|---|
| Batería de 9 V para los motores | No entrega la corriente; el voltaje se desploma |
| Más de 12 V al pin `VIN` | El regulador del ESP32 no lo aguanta |
| 5 V al pin `3V3` | Ese pin va directo al chip. Lo quema |
| El servo al pin `3V3` | No entrega esa corriente y el servo queda sin fuerza |
| El servo al pin `5V` del ESP32 | Ese pin **no está regulado**: es el `VIN` pasado de largo, o sea los 7.4 V de la batería |
| El servo a los 7.4 V de la batería | Un SG90 se quema arriba de 6 V |
| Las cuatro celdas en serie | 16.8 V. Quema los motores |

---

## Paso 4 — Carga el firmware

Este carro **no usa PlatformIO** sino **ESP-IDF**, que es la herramienta oficial de
Espressif. Es más pesada de instalar, pero es la que da acceso a todo lo que hace el chip.

Desde la extensión de Espressif en VS Code, o por línea de comandos:

```powershell
C:\Espressif\frameworks\esp-idf-v5.3.2\export.ps1
cd 03_Carro_ESP32
idf.py set-target esp32
idf.py build
idf.py -p COM7 flash monitor
```

Cambia `COM7` por el puerto de tu placa. Lo ves en el Administrador de dispositivos de
Windows, como "Silicon Labs CP210x".

### Si la carga falla

| Error | Qué hacer |
|---|---|
| `Cannot configure port` o `Write timeout` | Carga más despacio: `idf.py -p COM7 -b 115200 flash`. Algunos cables y adaptadores no aguantan la velocidad por defecto |
| `Failed to connect` | Mantén oprimido el botón **BOOT** de la placa mientras empieza la carga |
| No aparece ningún puerto COM | Falta el driver del CP210x, o el cable es solo de carga y no lleva datos |
| El puerto aparece pero nada funciona | Desconecta y vuelve a conectar el USB; prueba otro puerto, directo al computador |

---

## Paso 5 — Primer encendido y autoprueba

Al arrancar, el carro **se prueba solo** y cuenta lo que encuentra. Es la mejor
herramienta que tienes para saber si el cableado quedó bien.

1. **Levanta el carro**: la autoprueba mueve las ruedas.
2. Conecta la batería.
3. Mira los mensajes con `idf.py -p COM7 monitor`, o después desde el
   [panel de diagnóstico](#el-panel-de-diagnóstico) sin cables.

### Qué te va a decir

**Del sonar:**

| Mensaje | Qué significa |
|---|---|
| `9 de 9 con eco valido` | Perfecto |
| `sin flanco: el ECHO nunca subio` | El sensor no responde. Revisa sus 5 V, su GND, y el `TRIG` en GPIO 19 |
| `pulso 55 us = 0.9 cm, fuera de rango` | Eso no es un eco: es el disparo del TRIG colándose al cable del ECHO. **Separa los dos cables** |

**De los motores:** mueve una rueda a la vez, en los dos sentidos, y te dice qué pines
puso en alto.

| Lo que ves | Qué revisar |
|---|---|
| No gira ninguna | Falta batería en el driver, o quedaron los jumpers `ENA`/`ENB` |
| Gira una sola | Revisa los tres cables de esa rueda |
| Gira al revés | Cambia su `INVERTIR_*` en `config.h` |
| Gira en un solo sentido | El pin que quedó en alto no está llegando al driver |

### ⚠️ Con el USB solo, el carro se reinicia

Cuando enciende el WiFi, el ESP32 pega un tirón de corriente que **el puerto USB no
alcanza a dar**. El carro se reinicia justo ahí, una y otra vez, sin llegar a levantar la
red.

No está dañado. **Conecta la batería** y deja de pasar. El USB alcanza para programar,
pero no para funcionar.

---

## Paso 6 — Conéctate con el celular

Este carro **crea su propia red WiFi**. No usa la red del colegio ni necesita internet.

| Dato | Valor |
|---|---|
| Nombre de la red | `SmartCar-03` |
| Contraseña | `explorador` |
| Dirección del carro | `192.168.4.1` |

Se pueden conectar hasta **4 dispositivos a la vez**.

### Con la app (recomendado)

**Primero instala la app, con internet, ANTES de conectarte al carro.** La red del carro
no tiene internet: si intentas descargar el APK estando conectado a `SmartCar-03`, falla.

1. Descarga el APK:
   **https://github.com/jhersonsoto97-cell/ProyectosRoboticaI.E.T.A/releases/latest/download/SmartCar.apk**
   (o escanea el QR que te dieron). Descárgalo con **Chrome**, no desde el explorador de
   archivos.
2. Instálalo. Android te va a pedir permiso para instalar apps de origen desconocido: se
   lo das a Chrome, una sola vez.
3. Enciende el carro y espera unos segundos.
4. Abre la app y elige la tarjeta **Explorador**.
5. Toca **VINCULAR**. En Android 10 o más nuevo, la app se une sola a la red del carro. En
   versiones anteriores te manda a los ajustes de WiFi para que la elijas a mano.
6. Listo: el indicador de arriba se pone verde y el radar empieza a dibujar.

### Sin instalar nada, desde el navegador

1. Conéctate al WiFi `SmartCar-03` desde los ajustes del celular.
2. Abre el navegador y entra a **`http://192.168.4.1`**.

La página viene guardada adentro del carro. Es más básica que la app (no tiene escudo, ni
alerta de proximidad, ni ajustes guardados), pero sirve para cualquier celular y no
requiere instalar nada.

> Android puede avisarte que "esta red no tiene internet" y preguntarte si quieres seguir
> conectado. Dile que **sí**. Si le dices que no, se va por datos móviles y el carro deja
> de responder.

### Los controles de la app

| Control | Qué hace |
|---|---|
| Stick izquierdo | Acelerador |
| Stick derecho | Dirección |
| **CENTRO** | Mantiene el sonar apuntando al frente, para poder atornillar el brazo |
| **ESCUDO** | Cuando está activo, no deja acelerar si hay algo a menos de 10 cm |
| **MAPA** | Detiene el carro y levanta el plano del lugar girando sobre su eje |
| **MODO** | Cambia entre ARCADE y TANK |
| **LIMITE** | Tope de potencia: 40 %, 70 % o 100 % |
| Ventana del radar | Lo que el sonar va encontrando. El slider de al lado es el zoom |

> **Empieza en LIMITE 40 %** hasta que le tomes el pulso.

---

## Paso 7 — Centra el brazo del sonar

El servo no queda derecho por casualidad: hay que ponerlo en su centro **antes** de
apretar el tornillo del brazo. Si no, el carro va a creer que mira al frente cuando en
realidad apunta de medio lado, y todo el dibujo del radar sale corrido.

1. Con el carro encendido y la app conectada, toca **CENTRO**. El servo se planta en su
   punto medio y se queda ahí.
2. Con el servo quieto, acomoda el brazo con el HC-SR04 **apuntando exactamente al
   frente**.
3. Aprieta el tornillo.
4. Vuelve a tocar **CENTRO** para soltarlo. El sonar arranca a barrer.

Comprueba que quedó bien: pon un obstáculo justo al frente del carro y mira el radar. El
eco tiene que aparecer **arriba, en el centro** de la pantalla.

---

## Paso 8 — Que ande derecho

Acelerando a fondo vas a notar que el carro **se abre hacia un lado**. Es normal: dos
motores nunca son idénticos, y los dos canales del L298N tampoco entregan exactamente el
mismo voltaje.

En este carro se corrige **desde la app, sin recompilar nada**, y el ajuste queda guardado
en la memoria del carro.

1. **Primero descarta lo mecánico.** Levanta el carro y corre la prueba de motores del
   panel. Si una rueda va visiblemente más lenta, si algo roza, o si la batería está baja,
   arregla eso primero: compensar por software solo tapa el problema.
2. Marca una recta de unos **4 metros**.
3. En la app, engranaje → **DESVIO AL ACELERAR**.
4. Acelera **a fondo**, sin tocar la dirección, y mira para dónde se abre.

| Se va hacia... | Qué haces |
|---|---|
| la **izquierda** | **Sube** el número (`+1` / `+5`) |
| la **derecha** | **Baja** el número (`-1` / `-5`) |

Regla única: **el carro se va para un lado, mueves el número hacia el lado contrario.**

Debajo del número la app te dice qué está haciendo, por ejemplo `frena DER a 91%`. Ajusta
de a 5 primero y de a 1 al final. Suele quedar cerca de 90.

> Prueba siempre **a fondo**. Despacio la corrección casi no se nota, así que un carro que
> a media velocidad parece derecho igual se abre acelerando.

**Si necesitas bajar de 70, no sigas:** eso ya no es diferencia entre motores, es una
falla mecánica.

Este ajuste **vive en el carro, no en tu celular**. Si otra tablet se conecta, ve el mismo
valor. Está hecho así a propósito: la calibración depende de los motores de ese carro, no
de quién lo maneje.

---

## El panel de diagnóstico

`http://192.168.4.1/diag`, desde el mismo celular y sin instalar nada.

Es el Monitor Serie, pero servido por WiFi. Sin esto tendrías que tener el carro sobre la
mesa y amarrado al computador, que es justo la posición en la que no puedes probar nada:
las ruedas no tocan el piso y la batería no está alimentando.

| Qué muestra | Para qué sirve |
|---|---|
| Razón del último reinicio | `BROWNOUT` es problema de alimentación, `PANIC` es del programa. Desde afuera los dos se ven igual |
| Memoria libre | Delata una fuga en una demostración larga |
| Encendido hace | Si vuelve a cero, hubo un reinicio que quizá no notaste |
| Potencia por rueda | Lo que el carro le está mandando de verdad al driver |
| Última lectura del sonar | Ángulo y distancia, sin tocar nada |
| Consola | Todo el registro de mensajes, incluida la autoprueba de arranque |
| Ajustes | Los valores de calibración, editables ahí mismo |

Tiene además cinco botones para repetir pruebas: servo, sonar, motor izquierdo, motor
derecho y todo junto. Mueves un cable, tocas el botón y ves el resultado en segundos.

**Las pruebas de motor mueven las ruedas. Levanta el carro antes de tocarlas.**

---

## Si algo no funciona

| Lo que ves | Qué revisar |
|---|---|
| Se reinicia sin parar al encender | Estás con USB solo. Conecta la batería |
| No aparece la red `SmartCar-03` | El carro no llegó a arrancar; míralo por el Monitor Serie |
| Conecta pero no responde | Android se fue por datos móviles. Acepta seguir en la red sin internet |
| El radar no dibuja nada | El sonar no responde: revisa sus 5 V y su GND |
| El radar dibuja obstáculos que no existen | Los cables de TRIG y ECHO van pegados. Sepáralos |
| El eco aparece corrido de lado | Falta centrar el brazo (Paso 7) |
| El servo tiembla o zumba | Le falta el condensador, o está alimentado del pin equivocado |
| No gira ninguna rueda | Falta batería en el driver, o quedaron los jumpers |
| Se abre hacia un lado | Normal. Ve al [Paso 8](#paso-8--que-ande-derecho) |
| Se reinicia al acelerar | Batería descargada, o falta la tierra común |

---

## Para saber más

### Por qué el mapa se levanta con el carro quieto

Este carro no tiene encoders ni giroscopio, así que la única forma de saber cuánto avanzó
sería contar cuánta potencia se le mandó a los motores. Ese cálculo se equivoca cada vez
más, y en pocos metros el mapa queda torcido.

Escaneando **quieto** ese error no existe: un barrido desde un punto fijo es exacto. Por
eso el botón MAPA primero detiene el carro.

### Cómo está organizado por dentro

```
        ESP32 (crea la red "SmartCar-03")
   +-------------------------------------------+
   |  HTTP  -> la página web guardada adentro  |
   |  WS    -> control 20 veces por segundo    |
   |                                           |
   |  núcleo 0 : el barrido del sonar          |
   |  núcleo 1 : el servidor y el control      |
   +-------------------------------------------+
                    |
              driver -> 2 motores
```

Los dos núcleos se reparten el trabajo a propósito: una medición del ultrasónico se queda
esperando el eco hasta 25 ms, y ese tiempo no se le puede robar al control del carro.

| Archivo | De qué se encarga |
|---|---|
| `main/config.h` | Todo lo ajustable: pines, límites, calibración |
| `main/drive.c` | Motores, aceleración suave, failsafe, giro sobre el eje |
| `main/sonar.c` | Servo, medición y escaneo |
| `main/ajustes.c` | La calibración que se guarda en la memoria del carro |
| `main/web.c` | La red WiFi, el servidor y el WebSocket |
| `main/diag.c` | El panel de diagnóstico |

### Cómo se hablan la app y el carro

```
app -> carro    {"t":"c","l":-255..255,"r":-255..255}    manejar
                {"t":"scan"}                             levantar el mapa
                {"t":"stop"}                             detener
                {"t":"centrar","v":0|1}                  centrar el servo
                {"t":"trim","l":10..100,"r":10..100}     compensar el desvío

carro -> app    {"t":"s","a":grados,"d":cm}              un eco del sonar
                {"t":"p","v":0..100}                     avance del escaneo
                {"t":"e","pts":[...]}                    el mapa completo
```

Los ángulos van referidos **al frente del carro**: 0 es adelante y crece hacia la derecha.

La app manda órdenes 20 veces por segundo aunque no muevas los dedos. Si ese goteo se
corta —se cierra la app, se aleja el celular, se acaba la batería— el carro **frena solo**
en medio segundo.

### Sobre el riel de 5 V del L298N

En este carro se midió y **no sirve**: da 4.74 V sin carga y **cae a 2.31 V** al conectarle
el ESP32.

Un regulador sano daría 5.00 V clavados. Que ya salga bajo *sin carga* dice que el
problema no es el consumo sino ese pin. La causa más común es el **jumper de `5V-EN`** del
módulo: si está quitado, ese terminal queda flotando y se hunde con cualquier carga.

Antes de darlo por muerto, revisa ese jumper y vuelve a medir. Si con el jumper puesto
aparecen 5.0 V, el riel sirve para el servo y el sensor. Si sigue en 4.74 V, el regulador
está dañado y hace falta un módulo reductor (LM2596).

El ESP32 va a la batería en cualquiera de los dos casos.

### El calor del regulador del ESP32

El regulador de la placa baja de 7.4 V a 3.3 V con 150 mA, o sea unos **0.7 W**. Queda
caliente al tacto pero está dentro de lo normal, y trae protección: si se pasa, se apaga
solo antes de romperse.

**Tócalo a los diez minutos del primer encendido.** Tibio está bien. Si quema al punto de
no poder dejar el dedo, hay que bajarle el voltaje de entrada con un módulo reductor.

---

## Glosario

| Palabra | Qué significa |
|---|---|
| **Firmware** | El programa que va adentro del ESP32 |
| **ESP-IDF** | Las herramientas oficiales para programar el ESP32 |
| **APK** | El archivo de instalación de una app de Android |
| **Punto de acceso** | Cuando un aparato crea su propia red WiFi en vez de conectarse a una |
| **Sonar / ultrasónico** | Sensor que manda un chillido que no oímos y mide cuánto tarda el eco en volver |
| **Servo** | Motor que se puede mandar a un ángulo exacto, en vez de solo girar |
| **PWM** | Cómo se controla la velocidad: prender y apagar muy rápido |
| **Trim** | Ajuste fino para emparejar las dos ruedas |
| **GND** | "Tierra": el cero del circuito. Todo tiene que compartirlo |
| **Divisor de tensión** | Dos resistencias que bajan 5 V a 3.3 V para no dañar el ESP32 |
| **Condensador** | Guarda un poco de energía y la suelta rápido, para tapar los bajones |
| **Failsafe** | La protección que frena el carro si se pierde la conexión |
| **Brownout** | Un reinicio por falta de voltaje |
| **NVS** | La memoria del ESP32 donde se guarda la calibración aunque se apague |
| **Encoder** | Sensor que cuenta cuánto giró una rueda. Este carro no lleva |
