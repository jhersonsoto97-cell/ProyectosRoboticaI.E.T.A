"""
============================================================
  Simulador del Smart Car - Proyecto 02_Carro_B
============================================================
  Recibe por TCP las mismas tramas <L,R> que la app Android
  le enviaria al HC-05 y dibuja el carro moviendose con
  fisica de traccion diferencial.

  El modelo de control replica src/main.cpp linea por linea:
  piso de torque, rampa de aceleracion y failsafe. Lo que se
  ve en pantalla es lo que hara el carro real, de modo que la
  app y la logica de manejo se validan sin hardware.

  Uso:
      python car_simulator.py [--port 8080]

  Teclado (util cuando no hay ninguna app conectada):
      W/S avanzar-retroceder   A/D girar
      ESPACIO frenar           R reiniciar posicion
============================================================
"""

import argparse
import math
import socket
import sys
import threading
import time
import tkinter as tk
from collections import deque

# ----------------------------------------------------------
# Constantes espejo del firmware (src/main.cpp).
# Cambiar una aqui obliga a cambiarla alla, y viceversa.
# ----------------------------------------------------------
PWM_MAX = 255
RAMPA_PASO = 12
TICK_MS = 10
FAILSAFE_MS = 400

# Compensacion entre motores. Espejo de las constantes del firmware: si se ajusta una
# alla hay que ajustarla aqui, o el simulador dejara de predecir al carro real.
TRIM_IZQUIERDA_ADELANTE = 75
TRIM_DERECHA_ADELANTE = 100
PWM_MIN_IZQUIERDA = 60
PWM_MIN_DERECHA = 60

# Los de reversa llegan por el paquete {L,R} y estos son solo el arranque, igual que en
# el firmware. Debajo de TRIM_MINIMO el techo cae al piso de torque y la rueda queda a
# velocidad fija, con lo que el acelerador deja de actuar sobre ella.
TRIM_IZQUIERDA_ATRAS = 60
TRIM_DERECHA_ATRAS = 100
TRIM_MINIMO = 25

# ----------------------------------------------------------
# Parametros mecanicos del chasis 2WD tipico del proyecto.
# ----------------------------------------------------------
WHEEL_MAX_SPEED = 0.85      # m/s con PWM 255 y bateria cargada
TRACK_WIDTH = 0.155         # separacion entre ruedas, en metros
MOTOR_TAU = 0.12            # constante de tiempo de la inercia del motor, en segundos

# ----------------------------------------------------------
# Presentacion: misma paleta que la app Android.
# ----------------------------------------------------------
BG = "#05070f"
PANEL = "#0b1224"
GRID = "#101c33"
OUTLINE = "#1e2c4a"
CYAN = "#00e5ff"
BLUE = "#2979ff"
DANGER = "#ff3d5a"
WARNING = "#ffb300"
OK = "#00e676"
TEXT = "#e6f3ff"
MUTED = "#7a8ca8"

PIXELS_PER_METER = 115
CANVAS_W = 1040
CANVAS_H = 620
HUD_W = 250
TRAIL_STEP = 0.02   # metros entre puntos de la estela


def escalar_potencia(crudo, pwm_min, trim_adelante, trim_atras):
    """Reparte el recorrido util del joystick sobre pwm_min..techo.

    Identica a la funcion del firmware: por debajo del piso de torque el motor solo
    zumba, asi que mapear 0..255 sobre ese rango desperdiciaria un cuarto del stick.
    El trim recorta el techo y no la salida ya calculada, para que los valores bajos
    no terminen cayendo en la zona muerta del motor compensado. Cada sentido lleva su
    propio trim porque un motor DC no se comporta igual hacia adelante que hacia atras.
    """
    if crudo == 0:
        return 0
    trim = trim_adelante if crudo > 0 else trim_atras
    techo = min(max((PWM_MAX * trim) // 100, pwm_min), PWM_MAX)
    magnitud = min(max(abs(crudo), 1), PWM_MAX)
    magnitud = pwm_min + ((magnitud - 1) * (techo - pwm_min)) // (PWM_MAX - 1)
    return -magnitud if crudo < 0 else magnitud


class FirmwareModel:
    """Replica del lazo de control del Arduino."""

    def __init__(self):
        self.objetivo = [0, 0]
        self.aplicado = [0.0, 0.0]
        self.enlace_vivo = False
        self.failsafe_disparado = False
        self.trim_izq_atras = TRIM_IZQUIERDA_ATRAS
        self.trim_der_atras = TRIM_DERECHA_ATRAS
        self._ultimo_paquete = 0.0
        self._deuda_tick = 0.0

    def ajustar_trim(self, izquierda, derecha):
        """Aplica el paquete {L,R} de calibracion que manda la app."""
        self.trim_izq_atras = min(max(izquierda, TRIM_MINIMO), 100)
        self.trim_der_atras = min(max(derecha, TRIM_MINIMO), 100)

    def _aplicar(self, izquierda, derecha):
        self.objetivo[0] = escalar_potencia(
            max(-PWM_MAX, min(PWM_MAX, izquierda)), PWM_MIN_IZQUIERDA,
            TRIM_IZQUIERDA_ADELANTE, self.trim_izq_atras)
        self.objetivo[1] = escalar_potencia(
            max(-PWM_MAX, min(PWM_MAX, derecha)), PWM_MIN_DERECHA,
            TRIM_DERECHA_ADELANTE, self.trim_der_atras)

    def recibir_paquete(self, izquierda, derecha):
        self._aplicar(izquierda, derecha)
        self._ultimo_paquete = time.monotonic()
        self.enlace_vivo = True
        self.failsafe_disparado = False

    def comando_manual(self, izquierda, derecha):
        """Entrada por teclado: enclavada, sin vigilancia de failsafe."""
        self._aplicar(izquierda, derecha)
        self.enlace_vivo = False
        self.failsafe_disparado = False

    def soltar_enlace(self):
        self.enlace_vivo = False
        self.objetivo = [0, 0]

    def step(self, dt):
        """Avanza la rampa tantos ticks de TICK_MS como quepan en dt."""
        ahora = time.monotonic()

        if self.enlace_vivo and (ahora - self._ultimo_paquete) * 1000.0 > FAILSAFE_MS:
            self.objetivo = [0, 0]
            self.enlace_vivo = False
            self.failsafe_disparado = True

        self._deuda_tick += dt
        paso = TICK_MS / 1000.0
        while self._deuda_tick >= paso:
            self._deuda_tick -= paso
            for lado in (0, 1):
                delta = self.objetivo[lado] - self.aplicado[lado]
                if delta > RAMPA_PASO:
                    self.aplicado[lado] += RAMPA_PASO
                elif delta < -RAMPA_PASO:
                    self.aplicado[lado] -= RAMPA_PASO
                else:
                    self.aplicado[lado] = float(self.objetivo[lado])

        return self.aplicado[0], self.aplicado[1]


class CarPhysics:
    """Cinematica de traccion diferencial con inercia de motor de primer orden."""

    def __init__(self):
        self.reset()

    def reset(self):
        self.x = 0.0
        self.y = 0.0
        self.theta = -math.pi / 2   # apuntando hacia arriba en pantalla
        self.v_left = 0.0
        self.v_right = 0.0
        self.distancia = 0.0

    def step(self, pwm_left, pwm_right, dt):
        objetivo_izq = (pwm_left / PWM_MAX) * WHEEL_MAX_SPEED
        objetivo_der = (pwm_right / PWM_MAX) * WHEEL_MAX_SPEED

        # El PWM no se convierte en velocidad al instante: el rotor tiene inercia.
        # Sin este filtro el carro simulado giraria mas seco que el real.
        alpha = 1.0 - math.exp(-dt / MOTOR_TAU)
        self.v_left += (objetivo_izq - self.v_left) * alpha
        self.v_right += (objetivo_der - self.v_right) * alpha

        v = (self.v_left + self.v_right) / 2.0
        omega = (self.v_right - self.v_left) / TRACK_WIDTH

        self.theta += omega * dt
        self.x += v * math.cos(self.theta) * dt
        self.y += v * math.sin(self.theta) * dt
        self.distancia += abs(v) * dt

        return v, omega


class CommandServer(threading.Thread):
    """Servidor TCP que habla el mismo protocolo que el HC-05.

    Atiende un cliente a la vez, igual que el modulo real: el HC-05 solo admite una
    conexion simultanea, y permitir varias aqui daria una falsa sensacion de que el
    hardware lo soporta.
    """

    def __init__(self, port, on_packet, on_trim, on_client_change):
        super().__init__(daemon=True)
        self.port = port
        self.on_packet = on_packet
        self.on_trim = on_trim
        self.on_client_change = on_client_change
        self._client = None
        self._lock = threading.Lock()
        self._running = True

    def run(self):
        servidor = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        servidor.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        servidor.bind(("0.0.0.0", self.port))
        servidor.listen(1)

        while self._running:
            try:
                conexion, direccion = servidor.accept()
            except OSError:
                break

            conexion.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            with self._lock:
                if self._client:
                    try:
                        self._client.close()
                    except OSError:
                        pass
                self._client = conexion

            self.on_client_change(direccion[0])
            self._atender(conexion)

            with self._lock:
                if self._client is conexion:
                    self._client = None
            self.on_client_change(None)

    def _atender(self, conexion):
        # Espejo del parser del firmware: '<' abre conduccion y '{' abre calibracion.
        # Un solo buffer atiende ambos porque los paquetes nunca se solapan.
        cierres = {"<": ">", "{": "}"}
        buffer = ""
        cierre = None
        try:
            while self._running:
                datos = conexion.recv(256)
                if not datos:
                    break
                for byte in datos.decode("ascii", errors="ignore"):
                    if byte in cierres:
                        cierre = cierres[byte]
                        buffer = ""
                    elif cierre is not None and byte == cierre:
                        self._despachar(buffer, cierre)
                        cierre = None
                    elif cierre is not None:
                        if len(buffer) < 15:
                            buffer += byte
                        else:
                            cierre = None
        except OSError:
            pass
        finally:
            try:
                conexion.close()
            except OSError:
                pass

    def _despachar(self, texto, cierre):
        partes = texto.split(",")
        if len(partes) != 2:
            return
        try:
            izquierda, derecha = int(partes[0]), int(partes[1])
        except ValueError:
            return

        if cierre == ">":
            self.on_packet(izquierda, derecha)
        else:
            self.on_trim(izquierda, derecha)

    def enviar_telemetria(self, linea):
        """Devuelve texto al mando, como hace el Serial del Arduino."""
        with self._lock:
            cliente = self._client
        if not cliente:
            return
        try:
            cliente.sendall((linea + "\n").encode("ascii", errors="ignore"))
        except OSError:
            pass


class SimulatorApp:

    def __init__(self, root, port):
        self.root = root
        self.port = port
        self.firmware = FirmwareModel()
        self.car = CarPhysics()
        # La estela se muestrea por distancia y no por tiempo: asi representa 60 m de
        # recorrido sin importar cuanto rato lleve abierta la ventana, y un carro
        # detenido no la consume.
        self.trail = deque(maxlen=3000)
        self.cliente = None
        self.teclas = set()
        self.ultimo_tiempo = time.monotonic()
        self.ultima_telemetria = 0.0
        self.ultimo_pwm = (0.0, 0.0)
        self.velocidad = 0.0

        root.title(f"Smart Car - Simulador (TCP :{port})")
        root.configure(bg=BG)
        root.resizable(False, False)

        self.canvas = tk.Canvas(
            root, width=CANVAS_W, height=CANVAS_H,
            bg=BG, highlightthickness=0
        )
        self.canvas.pack()

        root.bind("<KeyPress>", self._tecla_abajo)
        root.bind("<KeyRelease>", self._tecla_arriba)
        root.focus_set()

        self.servidor = CommandServer(
            port, self._paquete_recibido, self._trim_recibido, self._cambio_cliente)
        self.servidor.start()

        self._loop()

    # ---------- entrada ----------

    def _paquete_recibido(self, izquierda, derecha):
        self.firmware.recibir_paquete(izquierda, derecha)

    def _trim_recibido(self, izquierda, derecha):
        anterior = (self.firmware.trim_izq_atras, self.firmware.trim_der_atras)
        self.firmware.ajustar_trim(izquierda, derecha)
        nuevo = (self.firmware.trim_izq_atras, self.firmware.trim_der_atras)
        if nuevo != anterior:
            self.servidor.enviar_telemetria(f"TRIM {nuevo[0]}/{nuevo[1]}")

    def _cambio_cliente(self, direccion):
        self.cliente = direccion
        if direccion is None:
            self.firmware.soltar_enlace()

    def _tecla_abajo(self, evento):
        tecla = evento.keysym.lower()
        if tecla == "r":
            self.car.reset()
            self.trail.clear()
            return
        self.teclas.add(tecla)

    def _tecla_arriba(self, evento):
        self.teclas.discard(evento.keysym.lower())

    def _aplicar_teclado(self):
        """Solo actua si no hay mando conectado, para no pelear con la app."""
        if self.cliente:
            return

        avance = 0
        giro = 0
        if "w" in self.teclas:
            avance += 200
        if "s" in self.teclas:
            avance -= 200
        if "a" in self.teclas:
            giro -= 150
        if "d" in self.teclas:
            giro += 150
        if "space" in self.teclas:
            avance, giro = 0, 0

        izquierda = max(-255, min(255, avance + giro))
        derecha = max(-255, min(255, avance - giro))
        self.firmware.comando_manual(izquierda, derecha)

    # ---------- lazo principal ----------

    def _loop(self):
        ahora = time.monotonic()
        dt = min(ahora - self.ultimo_tiempo, 0.1)   # evita saltos si la ventana se congela
        self.ultimo_tiempo = ahora

        self._aplicar_teclado()
        pwm_izq, pwm_der = self.firmware.step(dt)
        self.ultimo_pwm = (pwm_izq, pwm_der)
        self.velocidad, _ = self.car.step(pwm_izq, pwm_der, dt)

        if not self.trail or math.hypot(
            self.car.x - self.trail[-1][0], self.car.y - self.trail[-1][1]
        ) > TRAIL_STEP:
            self.trail.append((self.car.x, self.car.y))

        self._enviar_telemetria(ahora)
        self._dibujar()

        self.root.after(20, self._loop)

    def _enviar_telemetria(self, ahora):
        if self.firmware.failsafe_disparado:
            self.servidor.enviar_telemetria("FAILSAFE: enlace perdido, frenando.")
            self.firmware.failsafe_disparado = False
            return

        if ahora - self.ultima_telemetria < 0.5:
            return
        self.ultima_telemetria = ahora

        rumbo = int(math.degrees(self.car.theta) + 90) % 360
        self.servidor.enviar_telemetria(
            f"SIM v={self.velocidad:+.2f}m/s hdg={rumbo:03d} d={self.car.distancia:.1f}m"
        )

    # ---------- dibujo ----------

    def _mundo_a_pantalla(self, x, y):
        """Camara centrada en el carro: nunca se pierde de vista."""
        cx = (CANVAS_W - HUD_W) / 2
        cy = CANVAS_H / 2
        return (
            cx + (x - self.car.x) * PIXELS_PER_METER,
            cy + (y - self.car.y) * PIXELS_PER_METER
        )

    def _dibujar(self):
        self.canvas.delete("all")
        self._dibujar_cuadricula()
        self._dibujar_estela()
        self._dibujar_carro()
        self._dibujar_hud()

    def _dibujar_cuadricula(self):
        paso = 0.5
        ancho_util = CANVAS_W - HUD_W
        alcance_x = (ancho_util / 2) / PIXELS_PER_METER + paso
        alcance_y = (CANVAS_H / 2) / PIXELS_PER_METER + paso

        inicio_x = math.floor((self.car.x - alcance_x) / paso) * paso
        inicio_y = math.floor((self.car.y - alcance_y) / paso) * paso

        pasos_x = int((alcance_x * 2) / paso) + 2
        pasos_y = int((alcance_y * 2) / paso) + 2

        for i in range(pasos_x):
            x = inicio_x + i * paso
            px, _ = self._mundo_a_pantalla(x, 0)
            if 0 <= px <= ancho_util:
                grueso = abs(x % 1.0) < 1e-6
                self.canvas.create_line(
                    px, 0, px, CANVAS_H,
                    fill=OUTLINE if grueso else GRID, width=1
                )

        for i in range(pasos_y):
            y = inicio_y + i * paso
            _, py = self._mundo_a_pantalla(0, y)
            if 0 <= py <= CANVAS_H:
                grueso = abs(y % 1.0) < 1e-6
                self.canvas.create_line(
                    0, py, ancho_util, py,
                    fill=OUTLINE if grueso else GRID, width=1
                )

    def _dibujar_estela(self):
        if len(self.trail) < 2:
            return
        puntos = []
        for x, y in self.trail:
            px, py = self._mundo_a_pantalla(x, y)
            puntos.extend((px, py))
        self.canvas.create_line(*puntos, fill="#1f6b8f", width=3, smooth=True)

    def _dibujar_carro(self):
        largo = 0.20 * PIXELS_PER_METER
        ancho = 0.14 * PIXELS_PER_METER
        cx, cy = self._mundo_a_pantalla(self.car.x, self.car.y)
        cos_t = math.cos(self.car.theta)
        sin_t = math.sin(self.car.theta)

        def local(dx, dy):
            return (
                cx + dx * cos_t - dy * sin_t,
                cy + dx * sin_t + dy * cos_t
            )

        chasis = [
            local(-largo / 2, -ancho / 2),
            local(largo / 2, -ancho / 2),
            local(largo / 2, ancho / 2),
            local(-largo / 2, ancho / 2),
        ]
        self.canvas.create_polygon(
            [c for punto in chasis for c in punto],
            fill=PANEL, outline=CYAN, width=2
        )

        # Cada rueda se pinta segun su propio PWM: se ve al instante cual empuja mas.
        for lado, pwm in ((-1, self.ultimo_pwm[0]), (1, self.ultimo_pwm[1])):
            intensidad = min(abs(pwm) / PWM_MAX, 1.0)
            color = self._mezclar(OUTLINE, DANGER if pwm < 0 else CYAN, intensidad)
            rueda = [
                local(-largo * 0.22, lado * ancho / 2 - 3),
                local(largo * 0.22, lado * ancho / 2 - 3),
                local(largo * 0.22, lado * ancho / 2 + 3),
                local(-largo * 0.22, lado * ancho / 2 + 3),
            ]
            self.canvas.create_polygon(
                [c for punto in rueda for c in punto],
                fill=color, outline=""
            )

        nariz = local(largo / 2 + 10, 0)
        self.canvas.create_line(cx, cy, nariz[0], nariz[1], fill=BLUE, width=2, arrow=tk.LAST)

    def _dibujar_hud(self):
        x0 = CANVAS_W - HUD_W
        self.canvas.create_rectangle(x0, 0, CANVAS_W, CANVAS_H, fill=PANEL, outline=OUTLINE)

        if self.cliente:
            estado, color = f"MANDO {self.cliente}", OK
        else:
            estado, color = f"ESCUCHANDO :{self.port}", WARNING

        y = 24
        self.canvas.create_text(
            x0 + 18, y, text="SMART CAR / SIM", anchor="w",
            fill=CYAN, font=("Consolas", 13, "bold")
        )
        y += 26
        self.canvas.create_oval(x0 + 18, y - 4, x0 + 26, y + 4, fill=color, outline="")
        self.canvas.create_text(
            x0 + 34, y, text=estado, anchor="w", fill=TEXT, font=("Consolas", 9)
        )

        rumbo = int(math.degrees(self.car.theta) + 90) % 360
        filas = [
            ("", ""),
            ("PWM PEDIDO", ""),
            ("  izquierda", f"{self.firmware.objetivo[0]:+4d}"),
            ("  derecha", f"{self.firmware.objetivo[1]:+4d}"),
            ("", ""),
            ("PWM APLICADO", "tras rampa"),
            ("  izquierda", f"{self.ultimo_pwm[0]:+7.1f}"),
            ("  derecha", f"{self.ultimo_pwm[1]:+7.1f}"),
            ("", ""),
            ("CINEMATICA", ""),
            ("  velocidad", f"{self.velocidad:+.2f} m/s"),
            ("  rumbo", f"{rumbo:3d} deg"),
            ("  recorrido", f"{self.car.distancia:.1f} m"),
            ("", ""),
            ("TRIM REVERSA", ""),
            ("  izquierda", f"{self.firmware.trim_izq_atras:3d} %"),
            ("  derecha", f"{self.firmware.trim_der_atras:3d} %"),
            ("", ""),
            ("ENLACE", "vivo" if self.firmware.enlace_vivo else "inactivo"),
        ]

        y += 26
        for etiqueta, valor in filas:
            if etiqueta:
                es_titulo = not etiqueta.startswith(" ")
                self.canvas.create_text(
                    x0 + 18, y, text=etiqueta, anchor="w",
                    fill=CYAN if es_titulo else MUTED,
                    font=("Consolas", 9, "bold" if es_titulo else "normal")
                )
                if valor:
                    self.canvas.create_text(
                        CANVAS_W - 18, y, text=valor, anchor="e",
                        fill=MUTED if es_titulo else TEXT, font=("Consolas", 9)
                    )
            y += 19

        ayuda = [
            "TECLADO (sin mando)",
            "  W/S  avanzar/atras",
            "  A/D  girar",
            "  ESP  frenar",
            "  R    reiniciar",
        ]
        y = CANVAS_H - 20 - len(ayuda) * 17
        for linea in ayuda:
            self.canvas.create_text(
                x0 + 18, y, text=linea, anchor="w",
                fill=CYAN if not linea.startswith(" ") else MUTED,
                font=("Consolas", 8, "bold" if not linea.startswith(" ") else "normal")
            )
            y += 17

    @staticmethod
    def _mezclar(color_a, color_b, factor):
        a = tuple(int(color_a[i:i + 2], 16) for i in (1, 3, 5))
        b = tuple(int(color_b[i:i + 2], 16) for i in (1, 3, 5))
        mezcla = tuple(int(a[i] + (b[i] - a[i]) * factor) for i in range(3))
        return "#%02x%02x%02x" % mezcla


def habilitar_dpi_nativo():
    """Sin esto Windows estira la ventana en pantallas con escalado >100%.

    Tkinter no declara compatibilidad con DPI alto, asi que el sistema la reescala como
    si fuera un bitmap: los textos salen borrosos y el HUD se desborda de la ventana.
    """
    if sys.platform != "win32":
        return
    try:
        import ctypes
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except (ImportError, AttributeError, OSError):
        pass


def main():
    parser = argparse.ArgumentParser(description="Simulador del Smart Car")
    parser.add_argument("--port", type=int, default=8080, help="puerto TCP de escucha")
    argumentos = parser.parse_args()

    habilitar_dpi_nativo()
    root = tk.Tk()
    SimulatorApp(root, argumentos.port)
    root.mainloop()


if __name__ == "__main__":
    main()
