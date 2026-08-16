package com.ieta.smartcar

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ieta.smartcar.ui.GamepadScreen
import com.ieta.smartcar.ui.GarajeScreen
import com.ieta.smartcar.ui.SplashScreen
import com.ieta.smartcar.ui.theme.SmartCarTheme

/**
 * Las tres pantallas del arranque.
 *
 * El garaje va en el medio y no escondido en los ajustes: es la primera decision de
 * cada sesion, y mostrarla de entrada deja ver que la app maneja dos carros.
 */
private enum class Pantalla { PRESENTACION, GARAJE, MANDO }

class MainActivity : ComponentActivity() {

    private val controller: ControllerViewModel by viewModels()

    // Android 12 movio los permisos Bluetooth a tiempo de ejecucion. Antes de esa version
    // el descubrimiento se apoyaba en el permiso de ubicacion, porque la lista de
    // dispositivos cercanos permite deducir donde esta el usuario.
    private val requiredPermissions: Array<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }

            // Unirse a la red que levanta un carro necesita su propio permiso, y cual
            // depende de la version: hasta Android 12 es el de ubicacion, porque las
            // redes cercanas delatan donde esta el usuario; desde Android 13 hay uno
            // dedicado que no arrastra la ubicacion consigo.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }.distinct().toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Antes de super.onCreate: la pantalla del sistema tiene que quedar instalada
        // antes de que la ventana se dibuje por primera vez.
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Un mando que se apaga a mitad de una maniobra deja el carro sin comandos,
        // asi que la pantalla se mantiene encendida mientras la app este al frente.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        ocultarBarras()

        setContent {
            SmartCarTheme {
                var pantalla by remember { mutableStateOf(Pantalla.PRESENTACION) }

                LaunchedEffect(Unit) {
                    if (requiredPermissions.isNotEmpty()) {
                        permissionLauncher.launch(requiredPermissions)
                    }
                }

                // Cruce en vez de corte seco: el fondo es el mismo en las tres pantallas,
                // asi que el desvanecido se lee como que una aparece encima de la otra y
                // no como un cambio de vista.
                Crossfade(
                    targetState = pantalla,
                    animationSpec = tween(420),
                    label = "navegacion"
                ) { actual ->
                    when (actual) {
                        Pantalla.PRESENTACION -> SplashScreen(
                            onTerminado = { pantalla = Pantalla.GARAJE }
                        )

                        Pantalla.GARAJE -> GarajeScreen(
                            ultimoUsado = controller.ultimoCarroUsado,
                            onElegir = { elegido ->
                                controller.elegirCarro(elegido)
                                pantalla = Pantalla.MANDO
                            }
                        )

                        Pantalla.MANDO -> GamepadScreen(
                            viewModel = controller,
                            onCambiarCarro = { pantalla = Pantalla.GARAJE }
                        )
                    }
                }
            }
        }
    }

    /**
     * Vuelve a esconder las barras del sistema cada vez que la app recupera el foco.
     *
     * Esconderlas una sola vez en onCreate no alcanza. Bajar la cortina de notificaciones
     * le quita el foco a la ventana, y al cerrarla las barras se quedan puestas: el mando
     * queda recortado por arriba hasta que se cierra y se vuelve a abrir la app. Con la
     * tablet en manos de un estudiante, esa cortina se baja sin querer todo el tiempo.
     *
     * El foco es la senal correcta y no onResume, porque la cortina se superpone sin
     * pausar la actividad: onResume no llega a ejecutarse en ese caso.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) ocultarBarras()
    }

    private fun ocultarBarras() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            // Deslizar desde el borde las muestra un momento y se vuelven a ir solas. La
            // alternativa las deja fijas, y cualquier roce en el borde le comeria una
            // franja de pantalla al mando en plena maniobra.
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onStop() {
        super.onStop()
        // Perder la app de vista equivale a soltar el mando: se arma el paro de emergencia
        // para que el carro no siga con la ultima orden si algo quedo transmitiendo.
        controller.engageEmergencyStop()
    }
}
