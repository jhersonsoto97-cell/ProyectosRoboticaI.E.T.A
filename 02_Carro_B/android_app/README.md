# IETA Smart Car — Mando Android

Aplicacion Android nativa (Kotlin + Jetpack Compose) que controla el carro del proyecto
`02_Carro_B` por Bluetooth. Interfaz tipo mando de consola: dos joysticks analogicos
dibujados en Canvas, telemetria por rueda y paro de emergencia.

![stack](https://img.shields.io/badge/Kotlin-2.0.20-7F52FF)
![stack](https://img.shields.io/badge/Compose-BOM%202024.09-4285F4)
![stack](https://img.shields.io/badge/minSdk-24-3DDC84)

---

## Por que nativo y no una app web

El HC-05 usa **Bluetooth Classic, perfil SPP (RFCOMM)**. Web Bluetooth del navegador solo
habla **BLE (GATT)**, que es un protocolo distinto. Ninguna PWA, Capacitor con plugin web
ni pagina HTML puede abrir un socket SPP. Por eso la app es nativa.

---

## Compilar

### Desde la terminal (no necesita Android Studio)

El wrapper de Gradle ya esta incluido. Basta con el Android SDK y un JDK 17 o superior:

```powershell
cd 02_Carro_B\android_app
.\gradlew assembleDebug
```

Si Gradle no encuentra el SDK, crear `local.properties` en esta carpeta con la ruta:

```properties
sdk.dir=C\:\\Users\\TU_USUARIO\\AppData\\Local\\Android\\Sdk
```

El APK queda en `app/build/outputs/apk/debug/app-debug.apk` (~8.4 MB). Se copia al
telefono y se instala directo; no requiere Play Store.

### Build de release, para repartir

```powershell
cd 02_Carro_B\android_app
.\gradlew assembleRelease
```

Queda en `app/build/outputs/apk/release/app-release.apk`, alrededor de **1 MB** contra
los 8.5 MB del debug. La diferencia la hace R8, que descarta las clases y los recursos
de Compose que la app no usa.

### Debug y release comparten la clave

Ambas variantes se firman con la misma clave, asi que **cualquiera se instala sobre
cualquiera** sin desinstalar y sin perder los ajustes guardados de calibracion.

De fabrica, Gradle firma el debug con una clave generica distinta de la del release, y
Android no permite actualizar una instalacion cambiando de clave. Eso obligaba a
desinstalar cada vez que se alternaba entre una y otra, con la calibracion perdiendose en
el camino. Igualarlas elimina el problema de raiz.

Si alguien clona el repositorio **sin el `.jks`**, el debug cae a la clave generica y el
proyecto compila igual. En ese caso sus APK si chocaran con los firmados con la clave
del proyecto, y habra que desinstalar para alternar.

### Cual repartir

El de `release`. Pesa 1.1 MB contra 8.5 MB del debug, no lleva la instrumentacion de
depuracion, y es el que corresponde entregar.

#### La clave de firma

Vive en `keystore/ieta-smartcar.jks` con sus datos en `keystore.properties`, y **ninguno
de los dos entra al repositorio**: quien tenga ese archivo puede publicar actualizaciones
que Android acepta como si vinieran de la app original.

**Respaldalo.** Si se pierde, las instalaciones existentes ya no se pueden actualizar y
hay que desinstalar en cada telefono para poner una version firmada con otra clave.

Quien clone el repositorio sin el `.jks` igual puede compilar: el release cae a la clave
de depuracion en vez de fallar. Para generar una clave propia:

```powershell
keytool -genkeypair -v -keystore keystore\ieta-smartcar.jks -alias smartcar `
  -keyalg RSA -keysize 2048 -validity 10000
```

Y crear `keystore.properties` junto a `settings.gradle.kts`:

```properties
storeFile=keystore/ieta-smartcar.jks
storePassword=tu_clave
keyAlias=smartcar
keyPassword=tu_clave
```

### Desde Android Studio

1. **File > Open** y seleccionar `02_Carro_B/android_app`.
2. Esperar el **Gradle Sync**.
3. **Run > Run 'app'** con el telefono en modo desarrollador y depuracion USB activa.

### Versiones verificadas

| Componente | Version |
|---|---|
| Gradle | 8.9 |
| Android Gradle Plugin | 8.5.2 |
| Kotlin | 2.0.20 |
| Compose BOM | 2024.09.02 |
| compileSdk / targetSdk | 34 |
| minSdk | 24 (Android 7.0) |

---

## Probar sin hardware (emulador + simulador)

El emulador de Android **no tiene radio Bluetooth Classic**, asi que no puede hablar con
un HC-05. Para poder probar igual, la app trae un segundo transporte que manda el mismo
protocolo `<L,R>` sobre TCP contra el [simulador del PC](../simulador/README.md).

```powershell
# terminal 1: el carro simulado
cd 02_Carro_B\simulador
python car_simulator.py

# terminal 2: emulador + app
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd Medium_Phone_API_35
cd 02_Carro_B\android_app
.\gradlew installDebug
```

En la app: boton Bluetooth del centro > **CONECTAR AL SIMULADOR** con `10.0.2.2:8080`.

Desde un telefono real por WiFi funciona igual, cambiando `10.0.2.2` por la IP del PC.

## Uso con el carro real

1. Alimentar el carro. El LED del HC-05 parpadea rapido (sin emparejar).
2. En **Ajustes de Android > Bluetooth**, emparejar el `HC-05`. PIN `1234` o `0000`.
   Esto se hace **una sola vez** y desde el sistema, no desde la app.
3. Abrir la app. Tocar el boton Bluetooth del centro.
4. Elegir el `HC-05` de la lista de emparejados. El chip superior pasa a verde.
5. Manejar. El LED del HC-05 queda encendido fijo mientras hay enlace.

### Controles

Los sticks van anclados a las esquinas de abajo, donde descansan los pulgares
sosteniendo el telefono con las dos manos, y su area sensible **desborda al circulo
dibujado**: manejando no se mira la pantalla, y exigir que el dedo caiga justo adentro
obliga a mirarla.

La respuesta no es lineal sino **expo**, como en las emisoras de radiocontrol. Con
respuesta lineal el tramo util del pulgar se gasta en la mitad alta del recorrido y
maniobrar despacio se vuelve imposible; la curva achata el centro y conserva el extremo,
de modo que se gana precision sin perder velocidad maxima.

### Sensibilidad, en el boton de engranaje

Los tres valores se guardan en el telefono y se aplican en la app, sin viajar al carro.

| Ajuste | Que hace | Si el carro... |
|---|---|---|
| `RECORRIDO DEL STICK` | Cuanto debe moverse el dedo para llegar al tope | ...cambia demasiado con poco movimiento: subirlo |
| `SUAVIDAD ACELERADOR` | Achata el centro del stick de gas | ...arranca de golpe: subirla |
| `SUAVIDAD DIRECCION` | Lo mismo para el stick de giro | ...corrige brusco: subirla |
| `FUERZA DE GIRO` | Cuanta diferencia entre ruedas puede pedir la direccion | ...trompea en vez de curvar: bajarla |

**Recorrido del stick** separa lo que recorre el dedo de lo que recorre la perilla. El
circulo no puede crecer mucho mas sin comerse la pantalla, pero con un pulgar grueso un
desplazamiento corto sobre el vidrio ya barre todo el rango util. Al 150 % hay que mover
el dedo una vez y media mas para el mismo efecto, y la perilla sigue llegando al borde
justo cuando el dedo llega al tope, de modo que lo que se ve coincide con lo que se manda.

El tope es 200 % porque los sticks van pegados a las esquinas: mas recorrido que ese no
cabe entre el centro del stick y el borde de la pantalla.

**Fuerza de giro** es la que mas se nota. Al 100 % el stick al tope hace girar el carro
sobre su propio eje, que sirve para maniobrar en el sitio pero vuelve imposible trazar
una curva. Bajarla abre el radio: al 65 % el carro dobla en vez de trompear, y a valores
menores describe curvas cada vez mas amplias.

Las dos suavidades no recortan la velocidad maxima, solo reparten distinto el recorrido
del pulgar. Con el stick al tope la salida es la misma con suavidad 0 que con 90.

| Elemento | Funcion |
|---|---|
| Stick izquierdo | Modo ARC: acelerador (adelante/atras). Modo TANK: oruga izquierda |
| Stick derecho | Modo ARC: direccion (izq/der). Modo TANK: oruga derecha |
| Boton **MODO** | Alterna ARCADE (mezcla acelerador + giro) y TANK (una rueda por stick) |
| Boton **LIMITE** | Cicla el tope de potencia: 40% / 70% / 100% |
| Boton **PARO** | Paro de emergencia. Fuerza `<0,0>` hasta volver a tocarlo |
| Barras superiores | Potencia real enviada a cada rueda, con signo |
| Texto bajo el boton BT | Ultima linea que emitio el Arduino (util para ver el FAILSAFE) |

El paro de emergencia se arma solo si la app pasa a segundo plano.

---

## Protocolo

La app transmite **20 tramas por segundo**, cambien o no los sticks:

```
<L,R>\n     L y R enteros en -255..255
```

Ejemplo: `<180,-120>` = rueda izquierda adelante al 70%, rueda derecha atras al 47%.

Ese flujo constante es lo que alimenta el **failsafe** del firmware: si deja de llegar por
mas de 400 ms, el Arduino frena solo. Cortar la transmision equivale a ordenar un frenado,
lo que hace que una desconexion, un cierre de app o un telefono sin bateria terminen en
parada segura en vez de en un carro descontrolado.

A 9600 baudios el enlace mueve ~960 B/s; 20 tramas de ~11 bytes usan ~220 B/s. Sobra margen.

---

## Pantalla de arranque

Son dos capas con propositos distintos.

La **del sistema** cubre el hueco entre el toque en el icono y el primer fotograma de
Compose. Sin ella ese instante muestra el fondo de la ventana en blanco, que sobre una
interfaz oscura se percibe como un destello. Se apoya en `core-splashscreen` para que
tambien funcione en Android 11 y anteriores, donde la API nativa no existe.

La **de marca** viene despues y dura 1.4 s: tres anillos se expanden desde donde luego
quedan los sticks, y el wordmark entra en el centro. Los anillos no son decoracion
arbitraria, anticipan la disposicion de la pantalla de manejo, asi que al terminar el ojo
ya sabe donde mirar.

**Se puede saltar tocando la pantalla.** Una presentacion que no se puede omitir estorba
a la decima vez que se abre la app, y durante una demostracion se abre muchas veces.

## Arquitectura

```
MainActivity                  permisos, pantalla encendida, modo inmersivo
  └─ ControllerViewModel       estado de sticks + lazo fijo de 20 Hz
       ├─ DriveMixer           joystick -> potencia por rueda (arcade / tank)
       └─ CarLink              flujos de estado, escritura CONFLATED, lectura de telemetria
            ├─ SppClient       socket RFCOMM contra el HC-05 real
            └─ TcpClient       socket TCP contra el simulador del PC
  └─ GamepadScreen             composicion de la interfaz
       ├─ JoystickPad          stick analogico dibujado en Canvas
       └─ NeonWidgets          chip de estado, barras de rueda, botones circulares
```

**Por que `CarLink` es abstracta:** Bluetooth y TCP se diferencian solo en como se abre
el socket. Una vez abierto, el trafico es identico. Toda la mecanica de bombeo de flujos
vive una sola vez en la clase base y cada transporte solo aporta `openEndpoint()` y
`closeEndpoint()`. Asi es imposible que el simulador y el carro real diverjan en
comportamiento, que es justo lo que haria inutil al simulador.

**Detalle de diseno:** el canal de escritura es `Channel.CONFLATED`. Si el medio se
atasca, las tramas viejas se descartan y solo sale la posicion actual del stick. En
control en tiempo real un dato viejo es peor que ningun dato: encolar produce un mando
que responde con retraso creciente.

---

## Solucion de problemas

| Sintoma | Causa probable | Solucion |
|---|---|---|
| La lista sale vacia | El HC-05 no esta emparejado en el sistema | Emparejar desde Ajustes de Android |
| "read failed, socket might closed" | El HC-05 esta apagado o ya conectado a otro telefono | Reiniciar el modulo; una sola conexion a la vez |
| Conecta y se cae al instante | Alimentacion insuficiente del HC-05 | Fuente propia; no colgarlo del regulador del Mega bajo carga |
| Conecta pero el carro no se mueve | Jumpers ENA/ENB puestos | Retirarlos: con jumper el enable queda fijo e ignora el PWM |
| El carro frena solo cada rato | Enlace intermitente, disparando el failsafe | Acercar el telefono; revisar antena y tierra comun |
| Se mueve pero muy lento | Caida de ~2 V del L298N | Subir voltaje de bateria o migrar a TB6612FNG |
| Permiso denegado en Android 12+ | Se rechazo `BLUETOOTH_CONNECT` | Ajustes > Apps > IETA Smart Car > Permisos > Dispositivos cercanos |
