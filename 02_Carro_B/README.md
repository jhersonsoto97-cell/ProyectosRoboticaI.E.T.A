# 🎮 Carro B · Se maneja por Bluetooth

Un carro de dos motores que manejas **desde el celular**, con la app IETA Smart Car. La
conexión es por Bluetooth, usando un módulo HC-05.

Esta guía te lleva desde la caja de materiales hasta manejarlo con el celular en la mano.
Sigue los pasos en orden.

¿Palabras raras? Al final hay un [glosario](#glosario).

---

## Qué hay en esta carpeta

| Carpeta | Qué es |
|---|---|
| `src/` | El programa que va adentro del Arduino |
| `android_app/` | La app del celular ([su README](android_app/README.md)) |
| `simulador/` | Un carro de mentiras en el computador, para probar la app sin armar nada ([su README](simulador/README.md)) |

La app manda las órdenes así:

```
                       por Bluetooth
   Celular  ─────────────────────────►  HC-05 ─► Arduino Mega ─► L298N ─► motores
```

---

## Paso 0 — Reúne los materiales

### Electrónica

- [ ] 1 × Arduino Mega 2560, con su cable USB
- [ ] 1 × Driver L298N (el módulo rojo con el disipador de aluminio)
- [ ] 1 × Módulo Bluetooth **HC-05**
- [ ] 2 × Resistencias: una de **1 kΩ** y una de **2 kΩ** (para el divisor, ver Paso 2)
- [ ] 1 × Portapilas para los motores
- [ ] Cables Dupont

### Chasis

- [ ] 1 × Chasis de robot de dos ruedas
- [ ] 2 × Motores amarillos (motorreductores TT) con sus soportes
- [ ] 2 × Ruedas
- [ ] 1 × Rueda loca, con sus separadores
- [ ] Tornillos M3 y separadores

### Y además

- [ ] Un celular Android
- [ ] Un computador con VS Code
- [ ] Destornillador de estrella, alicate de punta

### La batería

Los motores **nunca** se alimentan desde el Arduino:

| Opción | ¿Sirve? | Por qué |
|---|---|---|
| 4 pilas AA (6 V) | Sí | La más sencilla |
| 2 baterías 18650 (7.4 V) | Sí | Dura mucho más y da más fuerza |
| Batería de 9 V (la cuadrada) | **No** | No aguanta la corriente de los motores |

---

## Paso 1 — Arma el chasis

1. **Pon los motores.** Cada uno con su soporte metálico en U y dos tornillos, uno a cada
   lado del chasis y con el eje hacia afuera.
2. **Mete las ruedas** a presión en los ejes, hasta el fondo, sin martillar.
3. **Instala la rueda loca** en un extremo, con sus separadores. El chasis tiene que
   quedar **paralelo al piso**.
4. **Pega el portapilas debajo** del chasis: el carro queda más estable y arriba queda
   espacio libre.
5. **Monta las placas arriba**, con separadores o cinta doble faz:
   - El **Arduino** con su USB **accesible desde el borde**.
   - El **L298N** cerca de los motores, para que los cables no crucen todo el carro.
   - El **HC-05** en un lugar despejado, sin placas metálicas encima. Es una antena: si lo
     tapas, el alcance baja.

---

## Paso 2 — Conecta los cables

### El driver L298N

| Arduino | L298N | Mueve la rueda |
|---|---|---|
| `D11` | `ENA` | **izquierda** |
| `D9` | `IN1` | izquierda |
| `D8` | `IN2` | izquierda |
| `D5` | `ENB` | **derecha** |
| `D7` | `IN3` | derecha |
| `D6` | `IN4` | derecha |
| `GND` | `GND` | (tierra común) |

Y las borneras de tornillo:

| Bornera del L298N | Se conecta a |
|---|---|
| `OUT1` y `OUT2` | Motor **izquierdo** |
| `OUT3` y `OUT4` | Motor **derecho** |
| `+12V` | Cable **rojo** de la batería |
| `GND` | Cable **negro** de la batería, **y también** el `GND` del Arduino |

### ⚠️ Quita los dos jumpers

El L298N viene con dos jumpers puestos sobre `ENA` y `ENB`. Mientras estén ahí, **los
motores andan siempre a toda velocidad** y el Arduino no puede regularlos.

Es difícil de detectar porque el carro igual arranca, frena y gira. Lo único que no
responde es el acelerador. **Quítalos antes de seguir** y guárdalos.

### El módulo Bluetooth HC-05

| Arduino | HC-05 | Ojo con esto |
|---|---|---|
| `D18` (TX1) | `RXD` | **Necesita divisor de tensión**, ver abajo |
| `D19` (RX1) | `TXD` | Va directo |
| `5V` | `VCC` | Verifica la serigrafía de tu módulo |
| `GND` | `GND` | Tierra común |

#### El divisor de tensión (no te lo saltes)

El Arduino Mega habla con **5 voltios**, pero la entrada `RXD` del HC-05 espera
**3.3 voltios**. Si los conectas directo, con el tiempo el módulo se daña.

Se arregla con dos resistencias, que reparten los 5 V y dejan pasar unos 3.3 V:

```
   D18 (TX1) ---[ 1 kΩ ]---+--- RXD del HC-05
                           |
                        [ 2 kΩ ]
                           |
                          GND
```

El otro cable (`TXD` → `D19`) sí va directo: ahí el HC-05 es el que habla, con 3.3 V, y el
Arduino entiende eso sin problema.

> Algunos módulos vienen en una placa que ya trae el divisor incluido (suelen decir
> "HC-05 con regulador" o traer un chip extra). Si es tu caso, conecta directo. Si tienes
> dudas, **pon el divisor igual**: no estorba.

### La tierra común

El cable negro de la batería, el `GND` del L298N, el `GND` del HC-05 y el `GND` del
Arduino tienen que estar **todos unidos**. Sin eso nada funciona bien y los síntomas son
confusos.

---

## Paso 3 — Carga el programa en el Arduino

1. Instala [VS Code](https://code.visualstudio.com/) y la extensión **PlatformIO IDE**.
2. Abre esta carpeta (`02_Carro_B`) como proyecto.
3. Conecta el Arduino por USB, **con la batería de los motores desconectada**.
4. Oprime **Build** y luego **Upload**.
5. El Monitor Serie va a **115200 baudios**, solo para ver mensajes. (El HC-05 habla
   internamente a 9600, pero eso lo maneja el programa.)

El programa está en [`src/main.cpp`](src/main.cpp).

---

## Paso 4 — Prueba los motores sin la app

Antes de meter el celular en la ecuación, comprueba que el carro se mueve. Esta prueba
funciona por el cable USB, sin Bluetooth.

1. **Levanta el carro** para que las ruedas giren en el aire.
2. Conecta la batería de los motores.
3. Abre el Monitor Serie a 115200, escribe **`T`** y envía.

El carro va a mover **una rueda a la vez**, en cuatro fases:

| Fase | Qué gira | Cómo debe girar |
|---|---|---|
| 1 | Rueda izquierda | hacia adelante |
| 2 | Rueda izquierda | hacia atrás |
| 3 | Rueda derecha | hacia adelante |
| 4 | Rueda derecha | hacia atrás |

Se prueba una sola rueda a la vez a propósito: con las dos girando es imposible distinguir
un motor invertido de un giro pedido a propósito.

### Si una rueda gira al revés

Los dos motores están montados en espejo, uno frente al otro. Por pura geometría, si los
cableas iguales, **uno gira al revés**. No es un error tuyo.

Dos formas de arreglarlo, las dos válidas:

- **Por hardware:** intercambia los dos cables de ese motor en la bornera del L298N.
- **Por software:** en `src/main.cpp`, pon en `true` la constante de esa rueda:

```cpp
const bool INVERTIR_IZQUIERDA = true;
const bool INVERTIR_DERECHA = false;
```

### Otros comandos que puedes escribir

| Comando | Qué hace |
|---|---|
| `A` | Avanzar |
| `R` | Retroceder |
| `I` | Girar a la izquierda |
| `D` | Girar a la derecha |
| `S` | Detener |
| `T` | La prueba de las cuatro fases |

Estos comandos **quedan fijos** hasta que escribas otro. Si pones `A`, el carro sigue
avanzando: acuérdate de mandar `S` para detenerlo.

---

## Paso 5 — Instala la app en el celular

Apunta la cámara del celular a este código:

<p align="center">
  <img src="../docs/qr_smartcar.png" alt="Código QR para descargar la app IETA Smart Car" width="240">
</p>

O escribe la dirección a mano:

**https://github.com/jhersonsoto97-cell/ProyectosRoboticaI.E.T.A/releases/latest/download/SmartCar.apk**

El código siempre te trae la **última versión**, así que sirve igual dentro de un año. No
hace falta cambiarlo cuando salga una actualización.

Tres cosas que se atraviesan la primera vez:

1. **Descárgalo con Chrome**, no con el explorador de archivos. Si abres el archivo desde
   el explorador, algunos celulares dicen "no se admite el formato" y no pasa nada.
2. **Hazlo con internet, antes de conectarte al carro.** (Esto importa más para el carro
   03, que tiene su propio WiFi sin internet.)
3. Android te va a pedir permiso para **instalar apps de origen desconocido**. Es normal:
   la app no está en la Play Store. Se lo das a Chrome una sola vez.

---

## Paso 6 — Conecta el celular con el carro

El emparejamiento se hace **una sola vez**, y desde los ajustes de Android, no desde la
app.

1. **Enciende el carro.** El LED del HC-05 parpadea rápido: quiere decir "todavía no estoy
   emparejado con nadie".
2. En el celular, entra a **Ajustes → Bluetooth**.
3. Busca el `HC-05` en la lista y tócalo para emparejar.
4. Te pide un PIN: es **`1234`** o **`0000`**.
5. Abre la app **IETA Smart Car**.
6. En la pantalla de selección, elige la tarjeta **Carro B**.
7. Toca el botón de Bluetooth y elige el `HC-05` de la lista.
8. El indicador de arriba se pone **verde** y el LED del HC-05 queda **encendido fijo**.

Ya puedes manejar.

### Los controles

| Control | Qué hace |
|---|---|
| Stick izquierdo | Acelerador: adelante y atrás |
| Stick derecho | Dirección: izquierda y derecha |
| Botón **MODO** | Cambia entre ARCADE (un stick acelera, el otro dobla) y TANK (cada stick es una rueda) |
| Botón **LIMITE** | Tope de potencia: 40 %, 70 % o 100 % |
| Botón **PARO** | Frena de inmediato y bloquea todo hasta que lo toques otra vez |
| Barras de arriba | Cuánta potencia está recibiendo cada rueda |

> **Empieza en LIMITE 40 %.** Al 100 % el carro es más rápido de lo que parece y se
> estrella antes de que alcances a reaccionar.

### Si se corta la conexión, el carro frena solo

Si el celular se aleja demasiado, se cierra la app o se descarga la batería del celular,
el carro **se detiene solo** en menos de medio segundo. No sale corriendo.

Esto funciona porque la app manda órdenes 20 veces por segundo aunque no muevas los
dedos. Cuando el Arduino deja de recibirlas, frena.

---

## Paso 7 — Que ande derecho

Ya manejando, vas a notar que el carro **se abre hacia un lado** aunque no toques la
dirección. Es normal: dos motores nunca son idénticos. La diferencia de fábrica en estos
motores llega al 20 %.

### Primero: comprueba que el acelerador sí funciona

Antes de calibrar nada, asegúrate de que puedes controlar las dos ruedas.

Con el carro **levantado**, acelera y ve cambiando el botón **LIMITE** entre 40 % y 100 %.
**Las dos ruedas tienen que cambiar de velocidad.**

¿Una no cambia? Le quedó puesto el jumper, o le falta el cable del enable. Arréglalo antes
de seguir: si no, vas a pasar la tarde bajándole potencia a la rueda sana persiguiendo a
una que no se puede bajar.

### Calibrar el avance

Esto se hace **cambiando el programa** y volviéndolo a cargar.

1. Batería **cargada**. Una batería floja exagera la diferencia y la calibración sale mal.
2. Marca una recta de 3 metros en piso liso.
3. En la app, pon **LIMITE en 70 %**. A fondo no sirve: el motor rápido ya está al máximo
   y solo se puede compensar frenándolo, con lo que pierdes velocidad.
4. Suelta el carro sobre la línea, acelerador al fondo, y mide cuánto se desvió al llegar
   a los 3 metros.
5. Ajusta en `src/main.cpp`:
   - Se abre hacia la **derecha** → la izquierda corre más → **baja** `TRIM_IZQUIERDA_ADELANTE`
   - Se abre hacia la **izquierda** → la derecha corre más → **baja** `TRIM_DERECHA_ADELANTE`
6. Punto de partida: **1 punto de trim por cada 5 cm** de desvío en 3 metros.
7. Carga y repite hasta que el desvío baje de 10 cm.

**Solo se baja, nunca se sube por encima de 100.** El trim únicamente recorta.

No busques la perfección: por debajo de 10 cm en 3 metros el resultado ya se mueve con el
piso, el desgaste de las llantas y la carga de la batería.

### Calibrar la reversa, desde la app

La reversa se ajusta **sin recompilar**, desde el botón del engranaje. Hay dos controles,
`RUEDA IZQUIERDA` y `RUEDA DERECHA`, y los cambios viajan al carro al instante.

Se ajusta aparte porque **un motor no es simétrico**: el desgaste de las escobillas y el
juego de la caja reductora cambian según el sentido de giro. Si calibras solo hacia
adelante, en reversa te vas a pasar de corrección y el carro se abrirá hacia el otro lado.

Con el avance ya derecho:

1. Marca la recta, ahora para retroceder.
2. Retrocede a fondo y mira hacia dónde se abre.
3. Baja el trim de la rueda que corre más, de a 5.
4. Cuando el desvío **cambie de lado**, ya te pasaste: el valor bueno está entre las dos
   últimas pruebas. De ahí ve por la mitad.

Tres o cuatro intentos alcanzan.

### El piso de cada motor

Es donde más se nota la diferencia, porque cada motor necesita un empujón distinto para
empezar a moverse.

1. Levanta el carro y manda **`T`** por el Monitor Serie.
2. Mira cuál rueda arranca con dificultad o zumba antes de girar.
3. **Sube** el `PWM_MIN_*` de esa rueda, de a 5, hasta que las dos arranquen parejo.

Sube el de la rueda floja; no bajes el de la otra, porque la dejas sin fuerza.

---

## Si algo no funciona

| Lo que ves | Qué revisar |
|---|---|
| No se mueve ninguna rueda | ¿Batería conectada al L298N? ¿Quitaste los jumpers? |
| Se mueve solo una rueda | Revisa los tres cables de ese canal y su bornera `OUT` |
| Anda siempre a la misma velocidad | Quedó puesto el jumper de ese canal |
| Al pedir "adelante" gira sobre su eje | Una rueda está invertida: usa `INVERTIR_*` o cambia sus cables |
| El HC-05 parpadea rápido y no conecta | No está emparejado. Hazlo desde Ajustes de Android, PIN `1234` |
| El HC-05 no aparece en la lista | Revisa `VCC` y `GND` del módulo; sin alimentación no se anuncia |
| Empareja pero la app no conecta | Revisa que `TXD` y `RXD` no estén cambiados. Es el error más común |
| Conecta y a los segundos se corta | El HC-05 está tapado por una placa, o la batería está floja |
| El carro se reinicia al acelerar | Batería floja, o falta la tierra común |
| Se va de lado al acelerar | Normal. Ve al [Paso 7](#paso-7--que-ande-derecho) |

---

## Todo lo que puedes ajustar

En [`src/main.cpp`](src/main.cpp), arriba del archivo:

| Constante | Valor | Qué hace |
|---|---|---|
| `INVERTIR_IZQUIERDA` / `_DERECHA` | `true` / `false` | Invierte el sentido de una rueda |
| `TRIM_IZQUIERDA_ADELANTE` | 75 | Recorta la rueda que corre más, avanzando |
| `TRIM_DERECHA_ADELANTE` | 100 | Lo mismo del otro lado |
| `PWM_MIN_IZQUIERDA` / `_DERECHA` | 60 | El empujón mínimo con el que cada motor arranca |
| `RAMPA_PASO` | 12 | Qué tan brusco puede acelerar. Evita que el pico de corriente reinicie el Arduino |
| `TICK_MS` | 10 | Cada cuánto se recalcula la aceleración |
| `FAILSAFE_MS` | 400 | Si pasa este tiempo sin órdenes, el carro frena solo |

Los trims de **reversa** no están aquí: los pone la app en caliente y el Arduino los
recibe con un paquete `{L,R}`.

---

## ⚠️ Seguridad

- **Nunca** conectes los motores al pin `5V` del Arduino.
- Verifica la tierra común **antes** de encender.
- Desconecta la batería mientras programas por USB.
- Revisa **dos veces** la polaridad del portapilas: rojo con rojo, negro con negro.
- Prueba siempre con el carro **levantado** la primera vez.
- Si algo se calienta mucho o huele raro: **desconecta la batería primero**, pregunta
  después.

---

## Para saber más

### Cómo se hablan la app y el carro

```
<L,R>     L y R son números entre -255 y 255, uno por rueda.
          El signo indica el sentido de giro.
```

`<180,-120>` hace avanzar la rueda izquierda y retroceder la derecha. La app manda 20 de
estos paquetes por segundo, muevas o no los sticks. Ese goteo constante es lo que alimenta
el failsafe.

```
{L,R}     Trim de reversa, en porcentaje de 25 a 100. La app lo reenvía cada segundo.
```

### La solución de fondo al desvío

Todo lo del Paso 7 es **lazo abierto**: mides la diferencia una vez y la compensas a mano.
Deja de servir cuando cambia la carga, el piso o la batería.

Las dos soluciones reales, para cuando quieras llevar el proyecto más lejos:

| Qué agregar | Qué resuelve |
|---|---|
| Encoders en las ruedas | El carro mide cuánto giró cada rueda de verdad y corrige solo |
| Sensor MPU6050 (giroscopio) | El carro sabe hacia dónde apunta y mantiene el rumbo |

Para ir derecho, el MPU6050 es lo más directo: corrige sobre el rumbo, que es lo que
realmente importa, en vez de sobre las vueltas de cada rueda.

### Una mejora de hardware

El L298N es un diseño viejo: se "come" unos **2 voltios** entre la batería y los motores.
Con una batería de 7.4 V, a los motores les llegan apenas 5.4 V.

Un **TB6612FNG** se conecta igual, pierde solo 0.1 V y entrega bastante más fuerza con la
misma batería.

---

## Glosario

| Palabra | Qué significa |
|---|---|
| **Firmware** | El programa que va adentro del Arduino |
| **APK** | El archivo de instalación de una app de Android |
| **PWM** | Cómo el Arduino controla la velocidad: prende y apaga el motor miles de veces por segundo |
| **Trim** | Un ajuste fino para emparejar las dos ruedas |
| **GND** | "Tierra": el cero del circuito. Todo tiene que compartirlo |
| **Jumper** | Una tapita plástica que une dos pines |
| **Driver / Puente H** | El módulo que le entrega la corriente fuerte a los motores. El Arduino solo, no puede |
| **Bornera** | Los conectores de tornillo del L298N |
| **Divisor de tensión** | Dos resistencias que bajan 5 V a 3.3 V para no dañar el HC-05 |
| **Failsafe** | La protección que frena el carro si se pierde la conexión |
| **Emparejar** | Presentar dos aparatos Bluetooth para que se reconozcan. Se hace una sola vez |
| **Monitor Serie** | La ventana del computador donde el Arduino escribe mensajes |

---

<p align="center">
  <img src="../docs/logo_colegio.png" alt="I.E.T.A San Diego" height="120">
  &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="../docs/logo_dennir.png">
    <img src="../docs/logo_dennir_claro.png" alt="DENNIR" height="72">
  </picture>
</p>

<p align="center">
  Proyecto de robótica con fines educativos 🤖<br>
  <sub><b>Docente:</b> Yerson Soto</sub>
</p>
