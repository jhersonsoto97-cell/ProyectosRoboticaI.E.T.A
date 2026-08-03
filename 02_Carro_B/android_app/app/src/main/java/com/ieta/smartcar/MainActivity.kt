package com.ieta.smartcar

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ieta.smartcar.ui.GamepadScreen
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
                LaunchedEffect(Unit) {
                    if (requiredPermissions.isNotEmpty()) {
                        permissionLauncher.launch(requiredPermissions)
                    }
                }
                GamepadScreen(viewModel = controller)
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
