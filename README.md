# 🚗 Seguidor de línea · Arduino Mega

Proyecto educativo de un carro móvil que sigue una línea negra usando **dos sensores infrarrojos digitales** y un controlador de motores **L298N**. El firmware está preparado para compilarse y cargarse con **PlatformIO** en un Arduino Mega 2560.

## ✨ Funcionamiento

Los sensores se ubican a izquierda y derecha de la línea. El Arduino lee su salida digital y ajusta la velocidad de cada motor para mantener el carro centrado:

| Lectura de sensores | Acción del carro |
| --- | --- |
| Ambos detectan la línea | Avanza recto |
| Solo izquierdo detecta la línea | Corrige a la izquierda |
| Solo derecho detecta la línea | Corrige a la derecha |
| Ninguno detecta la línea | La busca hacia el último lado detectado |

> En este carro, los sensores entregan `HIGH` sobre la cinta negra y `LOW` sobre la baldosa; por eso `LINEA_ES_LOW` está configurado como `false` en [`src/main.cpp`](src/main.cpp).

## 🧰 Componentes

- 1 × Arduino Mega 2560
- 1 × Driver L298N (o puente H compatible)
- 2 × Motores DC con ruedas
- 2 × Sensores infrarrojos digitales para línea/obstáculos
- 1 × Chasis para robot y rueda loca
- Batería o fuente externa adecuada para los motores
- Cables Dupont

## 🔌 Pinout

### Sensores infrarrojos

| Sensor | Pin del módulo | Arduino Mega |
| --- | --- | --- |
| Izquierdo | `DO` | `D2` |
| Derecho | `DO` | `D3` |
| Ambos | `VCC` | `5V` |
| Ambos | `GND` | `GND` |

### Driver L298N y motores

| L298N | Arduino Mega | Función |
| --- | --- | --- |
| `ENA` | `D5` (PWM) | Velocidad del motor izquierdo |
| `IN1` | `D6` | Dirección del motor izquierdo |
| `IN2` | `D7` | Dirección del motor izquierdo |
| `ENB` | `D8` (PWM) | Velocidad del motor derecho |
| `IN3` | `D9` | Dirección del motor derecho |
| `IN4` | `D10` | Dirección del motor derecho |
| `OUT1` / `OUT2` | Motor izquierdo | Salida al motor |
| `OUT3` / `OUT4` | Motor derecho | Salida al motor |

> **Importante:** retira los jumpers `ENA` y `ENB` del L298N para que el Arduino controle la velocidad por PWM. Alimenta los motores con una fuente externa y une el **GND de la fuente, el L298N y el Arduino Mega**.

## 📁 Estructura

```text
.
├── src/
│   └── main.cpp          # Lógica del seguidor de línea
├── platformio.ini        # Configuración para Arduino Mega 2560
└── README.md
```

## 🚀 Uso con PlatformIO

1. Instala [Visual Studio Code](https://code.visualstudio.com/) y la extensión **PlatformIO IDE**.
2. Clona este repositorio o abre esta carpeta como proyecto PlatformIO.
3. Conecta el Arduino Mega mediante USB y selecciona su puerto si PlatformIO lo solicita.
4. Ejecuta **Build** para compilar y **Upload** para cargar el firmware.
5. Ajusta el potenciómetro de cada sensor para que detecte con estabilidad la línea negra.

La comunicación serial está configurada a **115200 baudios** para el monitor de PlatformIO.

## ⚙️ Ajustes rápidos

En [`src/main.cpp`](src/main.cpp) puedes modificar:

- `LINEA_ES_LOW`: polaridad de detección de los sensores.
- `MODO_PRUEBA_SENSORES`: ponlo en `true` para detener los motores y revisar las lecturas en el Monitor Serial a `115200` baudios.
- `VELOCIDAD_BASE`: velocidad normal del carro (`0` a `255`).
- `VELOCIDAD_GIRO`: velocidad de la rueda interna durante una corrección.

Este chasis tiene ambos motores montados en sentido inverso; el código ya invierte su dirección para que el carro avance. Si en otro chasis un motor gira al revés, intercambia sus dos cables en el L298N o invierte los niveles de dirección correspondientes en el código.

### Prueba de sensores

1. Cambia `MODO_PRUEBA_SENSORES` a `true` en `src/main.cpp` y carga el programa.
2. Abre el **Monitor Serial** de PlatformIO a `115200` baudios.
3. Acerca y aleja cada sensor de la línea negra. El valor `linea: SI` debe cambiar solamente cuando el sensor esté sobre ella.
4. Si se comporta al contrario, cambia `LINEA_ES_LOW` y repite la prueba. Al terminar, devuelve `MODO_PRUEBA_SENSORES` a `false`.

## ⚠️ Seguridad eléctrica

No alimentes los motores desde el pin `5V` del Arduino. Utiliza una fuente apropiada para el motor y verifica que todos los módulos compartan tierra (`GND`) antes de encender el robot.

---

Proyecto para fines educativos de robótica. 🤖

**Docente:** Yerson Soto
