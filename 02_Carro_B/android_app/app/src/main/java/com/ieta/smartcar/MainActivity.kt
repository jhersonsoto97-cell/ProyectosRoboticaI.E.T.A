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
import com.ieta.smartcar.ui.SplashScreen
import com.ieta.smartcar.ui.theme.SmartCarTheme

class MainActivity : ComponentActivity() {

    private val controller: ControllerViewModel by viewModels()

    // Android 12 movio los permisos Bluetooth a tiempo de ejecucion. Antes de esa version
    // el descubrimiento se apoyaba en el permiso de ubicacion, porque la lista de
    // dispositivos cercanos permite deducir donde esta el usuario.
    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

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
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            SmartCarTheme {
                var mostrarSplash by remember { mutableStateOf(true) }

                LaunchedEffect(Unit) {
                    if (requiredPermissions.isNotEmpty()) {
                        permissionLauncher.launch(requiredPermissions)
                    }
                }

                // Cruce en vez de corte seco: el fondo es el mismo en las dos pantallas,
                // asi que el desvanecido se lee como que la interfaz aparece encima del
                // logo y no como un cambio de vista.
                Crossfade(
                    targetState = mostrarSplash,
                    animationSpec = tween(420),
                    label = "arranque"
                ) { enSplash ->
                    if (enSplash) {
                        SplashScreen(onTerminado = { mostrarSplash = false })
                    } else {
                        GamepadScreen(viewModel = controller)
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Perder la app de vista equivale a soltar el mando: se arma el paro de emergencia
        // para que el carro no siga con la ultima orden si algo quedo transmitiendo.
        controller.engageEmergencyStop()
    }
}
