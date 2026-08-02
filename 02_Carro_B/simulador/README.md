# Simulador del Smart Car

Corre el carro en el PC. Recibe por TCP las mismas tramas `<L,R>` que la app Android le
enviaria al HC-05 y dibuja el vehiculo moviendose con fisica de traccion diferencial.

Sirve para probar la app, el manejo y el protocolo **sin encender el carro**: se evita
gastar bateria, arriesgar el chasis y depender de que el HC-05 este a mano.

```
App Android (emulador o telefono)  --- TCP <L,R> --->  car_simulator.py
                                   <--- telemetria ---
```

## Requisitos

Solo Python 3.8 o superior. Usa unicamente la libreria estandar (`socket`, `threading`,
`tkinter`). No hay nada que instalar con pip.

## Arrancar

```powershell
cd 02_Carro_B\simulador
python car_simulator.py
```

Queda escuchando en el puerto **8080** de todas las interfaces. Para cambiarlo:

```powershell
python car_simulator.py --port 9000
```

Windows puede pedir permiso del firewall la primera vez. Aceptar para redes privadas;
sin eso un telefono real no podra conectarse (el emulador si, porque entra por loopback).

## Conectar la app

| Desde | Direccion a escribir en la app |
|---|---|
| Emulador de Android | `10.0.2.2:8080` (es el alias fijo del PC anfitrion) |
| Telefono real por WiFi | `IP_DEL_PC:8080`, con el PC y el telefono en la misma red |

Para averiguar la IP del PC: `ipconfig` y buscar la IPv4 del adaptador WiFi.

En la app: boton Bluetooth del centro > **CONECTAR AL SIMULADOR**.

## Que muestra

| Panel | Contenido |
|---|---|
| `PWM PEDIDO` | Lo que acaba de pedir el joystick, ya pasado por el piso de torque |
| `PWM APLICADO` | Lo que realmente sale al puente H despues de la rampa |
| `CINEMATICA` | Velocidad, rumbo y distancia recorrida |
| `ENLACE` | `vivo` mientras llegan tramas; `inactivo` cuando el failsafe corta |

La diferencia entre *pedido* y *aplicado* es la rampa trabajando: al soltar el stick de
golpe se ve como el aplicado persigue al pedido en vez de saltar.

Cada rueda del dibujo se colorea segun su propio PWM: cian hacia adelante, rojo hacia
atras, mas intenso cuanta mas potencia. La estela guarda los ultimos 60 m de recorrido.

## Teclado

Activo solo cuando **no** hay ninguna app conectada, para no pelear con el mando:

| Tecla | Accion |
|---|---|
| `W` / `S` | Avanzar / retroceder |
| `A` / `D` | Girar izquierda / derecha |
| `ESPACIO` | Frenar |
| `R` | Reiniciar posicion y borrar la estela |

## Fidelidad del modelo

El bloque de control es una **replica linea por linea** de `src/main.cpp`:

| Constante | Valor | Significado |
|---|---|---|
| `PWM_MIN` | 60 | Piso de torque |
| `RAMPA_PASO` | 12 | Cambio maximo de PWM por tick |
| `TICK_MS` | 10 | Periodo del lazo de rampa |
| `FAILSAFE_MS` | 400 | Corte por perdida de enlace |

Si se cambia una constante en el firmware hay que cambiarla aqui tambien, o el simulador
dejara de predecir el comportamiento real.

Lo mecanico si es una estimacion y se puede ajustar arriba del archivo:

| Constante | Valor | Significado |
|---|---|---|
| `WHEEL_MAX_SPEED` | 0.85 m/s | Velocidad de rueda con PWM 255 |
| `TRACK_WIDTH` | 0.155 m | Separacion entre ruedas; define el radio de giro |
| `MOTOR_TAU` | 0.12 s | Inercia del rotor |

Para calibrarlo contra el carro real: medir cuanto tarda en recorrer 2 m a fondo y
cuanto tarda en dar una vuelta completa girando sobre el eje, y ajustar
`WHEEL_MAX_SPEED` y `TRACK_WIDTH` hasta que el simulador de los mismos tiempos.

## Lo que el simulador NO reemplaza

- Alcance y cortes reales del enlace Bluetooth
- Caida de tension del L298N y bateria descargandose
- Patinaje de ruedas, desnivel del piso, motores desparejos
- Ruido electrico de los motores sobre la alimentacion del Mega

Sirve para validar logica de control, protocolo e interfaz. La prueba en pista sigue
siendo obligatoria.
