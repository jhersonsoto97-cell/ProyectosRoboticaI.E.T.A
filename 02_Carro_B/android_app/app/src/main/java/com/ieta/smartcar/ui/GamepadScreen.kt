package com.ieta.smartcar.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ieta.smartcar.ControllerViewModel
import com.ieta.smartcar.carro.Carro
import com.ieta.smartcar.R
import com.ieta.smartcar.control.DriveMode
import com.ieta.smartcar.link.BtDevice
import com.ieta.smartcar.link.LinkState
import com.ieta.smartcar.link.Radio
import com.ieta.smartcar.link.RedWifi
import com.ieta.smartcar.link.TcpClient
import com.ieta.smartcar.ui.theme.Neon

/** Lo que ocupa la barra superior con su margen. Sirve para repartir lo que queda. */
private val ALTO_BARRA = 58.dp

/** Lo que ocupa la fila de botones de abajo con su margen. */
private val ALTO_BOTONERA = 98.dp

/**
 * Lo que separa cada stick del borde de abajo.
 *
 * Es una constante y no un numero suelto porque de el depende donde empieza el borde
 * superior de los sticks, y de ahi cuelga el tamano del radar y la altura de los logos.
 */
private val MARGEN_STICK = 6.dp

@Composable
fun GamepadScreen(
    viewModel: ControllerViewModel,
    onCambiarCarro: () -> Unit = {},
) {
    val link = viewModel.link
    val linkState by link.state.collectAsState()
    val deviceName by link.endpointName.collectAsState()
    val lastError by link.lastError.collectAsState()
    val telemetry by link.telemetry.collectAsState()

    val devices by viewModel.scanner.devices.collectAsState()
    val scanning by viewModel.scanner.scanning.collectAsState()
    val scanError by viewModel.scanner.lastError.collectAsState()

    val contexto = LocalContext.current

    var showDevices by remember { mutableStateOf(false) }
    var showCalibration by remember { mutableStateOf(false) }
    var showSocial by remember { mutableStateOf(false) }

    // El boton atras del sistema vuelve al garaje en vez de cerrar la app. Es donde
    // cualquiera lo busca primero, y sin esto la unica salida quedaba enterrada dentro
    // del dialogo de conexion.
    BackHandler(enabled = true) { onCambiarCarro() }

    // La telemetria periodica pisa el eco de la calibracion a los pocos cientos de
    // milisegundos, asi que se retiene aparte. Si no, la confirmacion aparece y
    // desaparece antes de que alcance a leerse.
    var trimEcho by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(telemetry) {
        if (telemetry.startsWith("TRIM")) trimEcho = telemetry
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0A1428), Neon.Background, Color(0xFF04060D))
                )
            )
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // El stick se dimensiona contra el alto util: en landscape esa es la
            // dimension escasa y asi la interfaz aguanta desde un telefono chico
            // hasta una tablet sin recortarse.
            //
            // El techo llega a 380 y no a 260 por las tablets. Con 260, en una pantalla
            // de diez pulgadas los sticks quedaban del tamano de los de un telefono,
            // perdidos en un mar de espacio vacio. Ese tope se agarra recien pasando los
            // 730 dp de alto, asi que en telefono no cambia nada.
            //
            // Y no mas de 310: el pulgar no se alarga porque la pantalla sea mas grande,
            // asi que pasado ese tamano el stick solo pide mas recorrido para dar la misma
            // orden. El ancho que deja de comerse se lo lleva el radar, que si gana algo
            // con cada punto extra.
            //
            // En 310 las dos cotas del radar dan el mismo numero en una tablet de diez
            // pulgadas: el pasillo entre los sticks y la banda que queda encima de ellos.
            // Que coincidan quiere decir que no sobra ni ancho ni alto, que es lo mas
            // grande que el radar puede ser sin empujar a nadie.
            val stickDiameter = (maxHeight * 0.52f).coerceIn(150.dp, 310.dp)

            // Geometria que comparten los logos y el radar: la banda entre la barra de
            // arriba y el borde superior de los sticks. Es la unica zona de la pantalla
            // sin controles, asi que ahi va todo lo que se mira y no se toca.
            val topeBanda = ALTO_BARRA + 10.dp
            val bandaSobreSticks = maxHeight - stickDiameter - MARGEN_STICK - topeBanda
            val centroBanda = topeBanda + bandaSobreSticks / 2

            TopBar(
                carro = viewModel.carro,
                onCambiarCarro = onCambiarCarro,
                linkState = linkState,
                deviceName = deviceName,
                leftPower = viewModel.wheelPower.left,
                rightPower = viewModel.wheelPower.right,
                onSettings = { showCalibration = true },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            )

            // Escudo de la institucion, en el flanco izquierdo. Se le da mas altura que
            // al wordmark porque es una figura vertical: igualando alturas se veria la
            // mitad de grande, y a este tamano el escudo se reconoce por su silueta y
            // sus colores aunque las leyendas no alcancen a leerse.
            //
            // Centrado en la banda, igual que el de la derecha y que el radar. Antes cada
            // uno colgaba de una fraccion distinta del alto y en una tablet, con la banda
            // mucho mas alta, esas fracciones los dejaban a distintas alturas y flotando
            // sin relacion con nada.
            val altoEscudo = (maxHeight * 0.21f).coerceIn(58.dp, 170.dp)
            Image(
                painter = painterResource(R.drawable.ic_escudo),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = centroBanda - altoEscudo / 2, start = 18.dp)
                    .height(altoEscudo)
            )

            // Logo del autor, arrimado a la derecha bajo el engranaje. La banda que
            // queda entre la barra superior y el borde de los sticks esta libre de
            // controles, asi que ninguno de los dos estorba al manejar.
            //
            // Se dimensiona por altura y el ancho lo resuelve la proporcion de la
            // imagen, de modo que las trazas del wordmark se leen completas.
            //
            // Comparte el centro de la banda con el escudo, asi los dos quedan sobre la
            // misma linea sin depender de que sus alturas coincidan.
            //
            // Va a opacidad plena. Es trazo blanco sobre fondo oscuro, y atenuado se leia
            // como un elemento apagado al lado del escudo, que es a todo color.
            //
            // Tocandolo se abren las redes del autor. Es el unico control de la pantalla
            // sin rotulo, y esta bien asi: nadie lo va a pulsar sin querer mientras maneja
            // porque queda fuera del alcance de los pulgares.
            val altoMarca = (maxHeight * 0.12f).coerceIn(32.dp, 96.dp)
            Image(
                painter = painterResource(R.drawable.ic_brand),
                contentDescription = "Redes del autor",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = centroBanda - altoMarca / 2, end = 18.dp)
                    .height(altoMarca)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showSocial = true }
            )

            // Los sticks van anclados a las esquinas de abajo y no centrados a media
            // altura: sosteniendo el telefono con las dos manos, los pulgares descansan
            // ahi. Centrarlos obliga a estirar el pulgar en cada maniobra.
            JoystickPad(
                label = if (viewModel.mode == DriveMode.ARCADE) "ACELERADOR" else "ORUGA IZQ",
                axis = StickAxis.VERTICAL,
                diameter = stickDiameter,
                accent = Neon.Cyan,
                travelScale = viewModel.stickTravelScale,
                onChange = viewModel::onLeftStick,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 10.dp, bottom = MARGEN_STICK)
            )

            JoystickPad(
                label = if (viewModel.mode == DriveMode.ARCADE) "DIRECCION" else "ORUGA DER",
                axis = if (viewModel.mode == DriveMode.ARCADE) {
                    StickAxis.HORIZONTAL
                } else {
                    StickAxis.VERTICAL
                },
                diameter = stickDiameter,
                accent = Neon.Blue,
                travelScale = viewModel.stickTravelScale,
                onChange = viewModel::onRightStick,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 10.dp, bottom = MARGEN_STICK)
            )

            // Un carro con sonar usa el centro para el radar y baja el enlace a la fila
            // de botones. Mirar el radar mientras se maneja es lo que le da sentido: un
            // obstaculo a la derecha solo sirve si se ve antes de doblar.
            if (viewModel.carro.capacidades.radar) {
                // Hay dos formas distintas de que el radar quepa, y gana la que da mas
                // tamano:
                //
                //  - Metido en el pasillo entre los sticks. Puede bajar todo lo que
                //    quiera, pero no puede ser mas ancho que ese pasillo.
                //  - Apoyado encima de los sticks. Usa el ancho entero de la pantalla,
                //    pero no puede pasar del borde de arriba de ellos.
                //
                // En un telefono gana la primera y nada cambia. En una tablet gana la
                // segunda por lejos: sobra ancho y lo que falta es alto, mientras que el
                // pasillo entre los sticks sigue igual de angosto por mas grande que sea
                // la pantalla. Calcular solo el pasillo, que es lo que se hacia, dejaba
                // el radar del tamano de un telefono con media pantalla vacia al lado.
                //
                // Los 44 dp que se descuentan en ambas son el aire de arriba y de abajo
                // mas el renglon de rotulos.
                val pasillo = maxWidth - (stickDiameter + 20.dp) * 2 - 20.dp
                val bandaEntreSticks = maxHeight - ALTO_BARRA - ALTO_BOTONERA

                val entreSticks = minOf(pasillo, (bandaEntreSticks - 44.dp) / 0.62f)
                val sobreSticks =
                    minOf(maxWidth - 40.dp, (bandaSobreSticks - 44.dp) / 0.62f)

                RadarPanel(
                    ecos = viewModel.ecos,
                    anguloActual = viewModel.anguloSonar,
                    conectado = linkState == LinkState.CONNECTED,
                    progresoEscaneo = viewModel.progresoEscaneo,
                    alcanceCm = viewModel.alcanceRadar,
                    onAlcance = viewModel::ajustarAlcanceRadar,
                    servoCentrado = viewModel.servoCentrado,
                    onCentrar = viewModel::alternarCentradoServo,
                    riesgo = viewModel.riesgoProximidad,
                    escudoActivo = viewModel.escudoActivo,
                    escudoFrenando = viewModel.escudoFrenando,
                    onEscudo = viewModel::alternarEscudo,
                    ancho = maxOf(entreSticks, sobreSticks).coerceIn(190.dp, 620.dp),
                    // Colgado de la barra y no centrado: centrado dejaba un hueco muerto
                    // arriba, y ese hueco es justo lo que le faltaba de tamano.
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = topeBanda)
                )
            } else {
                CenterConsole(
                    porWifi = viewModel.carro.red != null,
                    linkState = linkState,
                    telemetry = telemetry,
                    error = lastError,
                    onToggleConnection = {
                        if (linkState == LinkState.CONNECTED) {
                            viewModel.disconnect()
                        } else {
                            showDevices = true
                        }
                    },
                    // Centrada en el pasillo que queda entre los dos sticks. Anclarla
                    // arriba dejaba un hueco muerto en el medio de la pantalla.
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            BottomBar(
                carro = viewModel.carro,
                linkState = linkState,
                onToggleConnection = {
                    if (linkState == LinkState.CONNECTED) {
                        viewModel.disconnect()
                    } else {
                        showDevices = true
                    }
                },
                onEscanear = viewModel::escanear,
                mode = viewModel.mode,
                speedCapLabel = viewModel.speedCapLabel,
                emergencyStop = viewModel.emergencyStop,
                onToggleMode = viewModel::toggleMode,
                onCycleSpeed = viewModel::cycleSpeedCap,
                onEmergencyStop = viewModel::toggleEmergencyStop,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp)
            )
        }
    }

    if (showDevices && viewModel.carro.red != null) {
        // Un carro que levanta su propia red no aparece en ninguna busqueda Bluetooth.
        // Buscarlo ahi seria esperar para siempre algo que no existe.
        RedDelCarroDialog(
            carro = viewModel.carro,
            estadoRed = viewModel.redWifi.estado.collectAsState().value,
            detalleRed = viewModel.redWifi.detalle.collectAsState().value,
            redActual = viewModel.redWifi.redActual(),
            hayWifi = viewModel.redWifi.hayWifi(),
            puedeUnirseSolo = viewModel.redWifi.puedeUnirseSolo,
            onAbrirAjustesWifi = { abrirAjustesWifi(contexto) },
            errorEnlace = if (linkState == LinkState.ERROR) lastError else null,
            onUnirse = { viewModel.unirseAlExplorador() },
            onConectar = {
                viewModel.conectarExplorador()
                showDevices = false
            },
            onCambiarCarro = {
                showDevices = false
                onCambiarCarro()
            },
            onDismiss = { showDevices = false }
        )
    } else if (showDevices) {
        // Al abrir la ventana se busca solo. Obligar a tocar "buscar" es un paso extra
        // que nadie quiere dar cuando lo unico que busca es su carro.
        LaunchedEffect(Unit) { viewModel.startScan() }
        DisposableEffect(Unit) { onDispose { viewModel.stopScan() } }

        ConnectionDialog(
            carro = viewModel.carro,
            onCambiarCarro = {
                showDevices = false
                onCambiarCarro()
            },
            devices = devices,
            scanning = scanning,
            bluetoothReady = viewModel.scanner.isBluetoothReady,
            error = if (linkState == LinkState.ERROR) lastError else scanError,
            errorTitle = if (linkState == LinkState.ERROR) "NO SE PUDO CONECTAR" else "AVISO",
            onRescan = { viewModel.startScan() },
            onPickDevice = {
                viewModel.connectBluetooth(it)
                showDevices = false
            },
            onPickSimulator = {
                viewModel.connectSimulator(it)
                showDevices = false
            },
            onDismiss = { showDevices = false }
        )
    }

    if (showSocial) {
        SocialDialog(onDismiss = { showSocial = false })
    }

    if (showCalibration) {
        CalibrationDialog(
            trimLeft = viewModel.reverseTrimLeft,
            trimRight = viewModel.reverseTrimRight,
            throttleExpo = viewModel.throttleExpo,
            steerExpo = viewModel.steerExpo,
            steerAuthority = viewModel.steerAuthority,
            stickTravel = viewModel.stickTravel,
            echo = trimEcho,
            onAdjustLeft = viewModel::adjustReverseTrimLeft,
            onAdjustRight = viewModel::adjustReverseTrimRight,
            onAdjustThrottleExpo = viewModel::adjustThrottleExpo,
            onAdjustSteerExpo = viewModel::adjustSteerExpo,
            onAdjustAuthority = viewModel::adjustSteerAuthority,
            onAdjustTravel = viewModel::adjustStickTravel,
            onDismiss = { showCalibration = false }
        )
    }
}

/**
 * Redes del autor, detras del logo de la esquina.
 *
 * Se llega por el logo y no por un boton propio: la barra ya esta llena de controles que
 * si se usan manejando, y esto no merece quitarles sitio.
 */
@Composable
private fun SocialDialog(onDismiss: () -> Unit) {
    val contexto = LocalContext.current
    var fallo by remember { mutableStateOf<String?>(null) }

    val abrir: (String) -> Unit = { url ->
        // Un telefono sin navegador lanza ActivityNotFoundException. Es raro, pero que la
        // app se cierre por tocar un logo seria absurdo.
        try {
            contexto.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            onDismiss()
        } catch (sinAplicacion: Exception) {
            fallo = "No hay ninguna app que pueda abrir el enlace."
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Neon.Surface,
        titleContentColor = Neon.TextPrimary,
        textContentColor = Neon.TextMuted,
        title = { Text("Credits", fontSize = 16.sp) },
        text = {
            Column {
                Text("De un egresado, para ustedes.", fontSize = 12.sp)

                Spacer(Modifier.height(18.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    SocialTile("INSTAGRAM", R.drawable.ic_instagram, Color(0xFFE1306C)) {
                        abrir(URL_INSTAGRAM)
                    }
                    SocialTile("GITHUB", R.drawable.ic_github, Neon.TextPrimary) {
                        abrir(URL_GITHUB)
                    }
                }

                fallo?.let { mensaje ->
                    Spacer(Modifier.height(12.dp))
                    Text(mensaje, color = Neon.Danger, fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar", color = Neon.Cyan)
            }
        }
    )
}

@Composable
private fun SocialTile(
    label: String,
    @DrawableRes icon: Int,
    accent: Color,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(62.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(listOf(accent.copy(alpha = 0.20f), Neon.Surface))
                )
                .border(1.dp, accent.copy(alpha = 0.6f), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = label,
                tint = accent,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = label,
            color = Neon.TextMuted,
            fontSize = 9.sp,
            letterSpacing = 1.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Ajuste de la compensacion de reversa sin recompilar.
 *
 * Solo la reversa: el avance ya quedo calibrado y exponerlo aqui invitaria a desajustarlo
 * sin querer. La ventana se puede dejar abierta mientras se maneja, que es justamente
 * como conviene calibrar, probando y corrigiendo sobre la marcha.
 */
@Composable
private fun CalibrationDialog(
    trimLeft: Int,
    trimRight: Int,
    throttleExpo: Int,
    steerExpo: Int,
    steerAuthority: Int,
    stickTravel: Int,
    echo: String?,
    onAdjustLeft: (Int) -> Unit,
    onAdjustRight: (Int) -> Unit,
    onAdjustThrottleExpo: (Int) -> Unit,
    onAdjustSteerExpo: (Int) -> Unit,
    onAdjustAuthority: (Int) -> Unit,
    onAdjustTravel: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Neon.Surface,
        titleContentColor = Neon.TextPrimary,
        textContentColor = Neon.TextMuted,
        title = { Text("Ajustes del mando", fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // El panel se usa manejando y en landscape entra poca altura. Cada
                // explicacion se resume a un renglon: el detalle esta en el README, y
                // aqui solo estorbaria entre el usuario y los botones.
                SectionLabel("SENSIBILIDAD")
                TrimRow("RECORRIDO DEL STICK", stickTravel, Neon.Ok, onAdjustTravel)
                Hint("100 llega al tope en el borde. Menos, antes; mas, hay que pasarse.")

                Spacer(Modifier.height(10.dp))
                TrimRow("SUAVIDAD ACELERADOR", throttleExpo, Neon.Cyan, onAdjustThrottleExpo)
                Hint("Mas alto, mas fino cerca del centro. Igual llega al maximo.")

                Spacer(Modifier.height(10.dp))
                TrimRow("SUAVIDAD DIRECCION", steerExpo, Neon.Blue, onAdjustSteerExpo)

                Spacer(Modifier.height(10.dp))
                TrimRow("FUERZA DE GIRO", steerAuthority, Neon.Warning, onAdjustAuthority)
                Hint("Mas bajo, curvas mas abiertas. Mas alto, gira sobre su eje.")

                Spacer(Modifier.height(18.dp))
                SectionLabel("COMPENSACION DE REVERSA")
                TrimRow("RUEDA IZQUIERDA", trimLeft, Neon.Cyan, onAdjustLeft)
                Spacer(Modifier.height(10.dp))
                TrimRow("RUEDA DERECHA", trimRight, Neon.Blue, onAdjustRight)
                Hint("Recorta la rueda que corre mas al retroceder. No afecta el avance.")

                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (echo != null) {
                        "Carro confirma: $echo"
                    } else {
                        "Esperando confirmacion del carro..."
                    },
                    color = if (echo != null) Neon.Ok else Neon.TextMuted,
                    fontSize = 10.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar", color = Neon.Cyan)
            }
        }
    )
}

@Composable
private fun TrimRow(
    label: String,
    value: Int,
    accent: Color,
    onAdjust: (Int) -> Unit
) {
    Column {
        Text(
            text = label,
            color = accent.copy(alpha = 0.8f),
            fontSize = 10.sp,
            letterSpacing = 1.5.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StepButton("-5", accent) { onAdjust(-5) }
            StepButton("-1", accent) { onAdjust(-1) }

            Text(
                text = "$value%",
                color = Neon.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            StepButton("+1", accent) { onAdjust(1) }
            StepButton("+5", accent) { onAdjust(5) }
        }
    }
}

@Composable
private fun StepButton(label: String, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TopBar(
    carro: Carro,
    onCambiarCarro: () -> Unit,
    linkState: LinkState,
    deviceName: String?,
    leftPower: Int,
    rightPower: Int,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Que carro se esta manejando, y la puerta de vuelta al garaje en el mismo
        // gesto. Van juntos porque quien quiere cambiar de carro mira primero cual
        // tiene puesto.
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(99.dp))
                .background(Neon.Surface)
                .border(1.dp, Neon.Outline, RoundedCornerShape(99.dp))
                .clickable(onClick = onCambiarCarro)
                .padding(start = 9.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("‹", color = Neon.Cyan, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(7.dp))
            Text(
                text = carro.nombre.uppercase(),
                color = Neon.TextPrimary,
                fontSize = 11.sp,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }

        Spacer(Modifier.width(10.dp))

        StatusChip(text = statusText(linkState, deviceName), color = statusColor(linkState))

        Spacer(Modifier.width(16.dp))

        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Neon.Surface.copy(alpha = 0.6f))
                .border(1.dp, Neon.Outline, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            WheelMeter("RUEDA IZQ", leftPower, Modifier.weight(1f))
            WheelMeter("RUEDA DER", rightPower, Modifier.weight(1f))
        }

        Spacer(Modifier.width(16.dp))

        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Neon.Surface)
                .border(1.dp, Neon.Outline, CircleShape)
                .clickable(onClick = onSettings),
            contentAlignment = Alignment.Center
        ) {
            Text("⚙", color = Neon.TextMuted, fontSize = 16.sp)
        }
    }
}

@Composable
private fun CenterConsole(
    porWifi: Boolean,
    linkState: LinkState,
    telemetry: String,
    error: String?,
    onToggleConnection: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = statusColor(linkState)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.width(190.dp)
    ) {
        Box(
            modifier = Modifier
                .size(78.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.22f), Neon.Surface)
                    )
                )
                .border(2.dp, accent.copy(alpha = 0.7f), CircleShape)
                .clickable(onClick = onToggleConnection),
            contentAlignment = Alignment.Center
        ) {
            // El simbolo dice por que medio se conecta este carro. Con dos carros que se
            // conectan distinto, un icono de Bluetooth sobre un enlace WiFi manda a
            // buscar el problema en la radio equivocada.
            if (porWifi) {
                WifiGlyph(tint = accent, glyphSize = 34.dp)
            } else {
                BluetoothGlyph(tint = accent, glyphSize = 34.dp)
            }
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = when (linkState) {
                LinkState.CONNECTED -> "TOCA PARA DESCONECTAR"
                LinkState.CONNECTING -> "CONECTANDO..."
                else -> "TOCA PARA VINCULAR"
            },
            color = Neon.TextMuted,
            fontSize = 8.sp,
            letterSpacing = 1.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(Modifier.height(8.dp))

        // Ultima linea que mando el Arduino. Deja ver el FAILSAFE en vivo durante pruebas.
        val note = error ?: telemetry
        if (note.isNotBlank()) {
            Text(
                text = note.take(60),
                color = if (error != null) Neon.Danger else Neon.TextMuted.copy(alpha = 0.8f),
                fontSize = 8.sp,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun BottomBar(
    carro: Carro,
    linkState: LinkState,
    onToggleConnection: () -> Unit,
    onEscanear: () -> Unit,
    mode: DriveMode,
    speedCapLabel: String,
    emergencyStop: Boolean,
    onToggleMode: () -> Unit,
    onCycleSpeed: () -> Unit,
    onEmergencyStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.height(84.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // El enlace baja aqui solo cuando el centro esta ocupado por el radar. Con el
        // centro libre se queda arriba, que es donde ya estaba y donde es mas visible
        // para quien todavia no conecto.
        if (carro.capacidades.radar) {
            BotonEnlace(
                porWifi = carro.red != null,
                linkState = linkState,
                onClick = onToggleConnection
            )

            Spacer(Modifier.width(22.dp))

            NeonRoundButton(
                caption = "MAPA",
                value = "SCAN",
                active = false,
                accent = Neon.Ok,
                onClick = onEscanear
            )

            Spacer(Modifier.width(22.dp))
        }

        NeonRoundButton(
            caption = "MODO",
            value = if (mode == DriveMode.ARCADE) "ARC" else "TANK",
            active = mode == DriveMode.TANK,
            accent = Neon.Blue,
            onClick = onToggleMode
        )

        Spacer(Modifier.width(28.dp))

        NeonRoundButton(
            caption = "LIMITE",
            value = speedCapLabel,
            active = true,
            accent = Neon.Cyan,
            onClick = onCycleSpeed
        )

        Spacer(Modifier.width(28.dp))

        NeonRoundButton(
            caption = "PARO",
            value = if (emergencyStop) "OFF" else "STOP",
            active = emergencyStop,
            accent = Neon.Danger,
            diameter = 70.dp,
            onClick = onEmergencyStop
        )
    }
}

/**
 * Boton de enlace con la forma del resto de la fila.
 *
 * Lleva el glifo del medio en vez de un rotulo: al lado de MODO, LIMITE y PARO, otra
 * palabra mas se pierde entre las demas, y el simbolo se reconoce sin leer.
 */
@Composable
private fun BotonEnlace(
    porWifi: Boolean,
    linkState: LinkState,
    onClick: () -> Unit,
) {
    val acento = statusColor(linkState)
    val conectado = linkState == LinkState.CONNECTED

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(62.dp)
                .clip(CircleShape)
                .background(if (conectado) acento.copy(alpha = 0.14f) else Neon.Surface)
                .border(1.5.dp, if (conectado) acento else Neon.Outline, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (porWifi) {
                WifiGlyph(tint = acento, glyphSize = 26.dp)
            } else {
                BluetoothGlyph(tint = acento, glyphSize = 26.dp)
            }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = when (linkState) {
                LinkState.CONNECTED -> "ENLACE"
                LinkState.CONNECTING -> "..."
                LinkState.ERROR -> "FALLO"
                LinkState.DISCONNECTED -> "VINCULAR"
            },
            color = Neon.TextMuted,
            fontSize = 9.sp,
            letterSpacing = 1.sp
        )
    }
}

/**
 * Conexion a un carro que levanta su propia red.
 *
 * Guia paso a paso en vez de presentar un boton de conectar a secas. La union a una red
 * sin internet es donde todo se rompe en silencio: el telefono figura conectado al carro
 * y el sistema sigue mandando el trafico por datos moviles, asi que un fallo generico
 * dejaria buscando el problema en el carro, que esta bien.
 */
@Composable
private fun RedDelCarroDialog(
    carro: Carro,
    estadoRed: RedWifi.Estado,
    detalleRed: String?,
    redActual: String?,
    hayWifi: Boolean,
    puedeUnirseSolo: Boolean,
    onAbrirAjustesWifi: () -> Unit,
    errorEnlace: String?,
    onUnirse: () -> Unit,
    onConectar: () -> Unit,
    onCambiarCarro: () -> Unit,
    onDismiss: () -> Unit,
) {
    val enSuRed = redActual == carro.red || estadoRed == RedWifi.Estado.UNIDO

    /* Hay WiFi pero el sistema no suelta el nombre. Es el caso normal en las tablets del
     * colegio, no una rareza: sin permiso de ubicacion, Android 8.1 en adelante esconde
     * el SSID. Decir "ninguna" ahi seria mentir sobre algo que el usuario esta viendo
     * conectado en su barra de estado. */
    val redIlegible = redActual == null && hayWifi

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Neon.Surface,
        titleContentColor = Neon.TextPrimary,
        textContentColor = Neon.TextMuted,
        title = {
            Column {
                Text("Conectar", fontSize = 16.sp)
                Text(
                    text = "${carro.nombre.uppercase()}  ·  ${carro.medio}",
                    color = Neon.Blue.copy(alpha = 0.9f),
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (errorEnlace != null) {
                    Aviso(titulo = "NO SE PUDO CONECTAR", cuerpo = errorEnlace, color = Neon.Danger)
                    Spacer(Modifier.height(12.dp))
                }

                SectionLabel("PASO 1  ·  UNIRSE A SU RED")

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    enSuRed -> Neon.Ok
                                    redIlegible -> Neon.Warning
                                    else -> Neon.TextMuted
                                }
                            )
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = when {
                            enSuRed -> "Estás en ${carro.red}"
                            redActual != null -> "Red actual: $redActual"
                            hayWifi -> "WiFi conectado, sin poder ver a cuál"
                            else -> "Sin WiFi"
                        },
                        color = if (enSuRed) Neon.Ok else Neon.TextPrimary,
                        fontSize = 12.sp
                    )
                }

                if (!enSuRed) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Red  ${carro.red}\nClave  ${carro.clave}",
                        color = Neon.TextMuted,
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(6.dp))

                    // Antes de Android 10 no existe forma de que la app una el telefono
                    // a una red. Ofrecer igual ese boton seria prometer algo que no
                    // puede cumplir, asi que se lo lleva derecho a los ajustes.
                    if (puedeUnirseSolo) {
                        TextButton(
                            onClick = onUnirse,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (estadoRed == RedWifi.Estado.PIDIENDO) {
                                    "ESPERANDO CONFIRMACION..."
                                } else {
                                    "UNIRSE DESDE LA APP"
                                },
                                color = Neon.Cyan,
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        TextButton(
                            onClick = onAbrirAjustesWifi,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("ABRIR AJUSTES DE WIFI", color = Neon.Cyan, fontSize = 12.sp)
                        }
                        Text(
                            text = "Este Android no deja que la app se una sola. " +
                                "Unite a ${carro.red} y volvé acá.",
                            color = Neon.TextMuted,
                            fontSize = 10.sp
                        )
                    }
                }

                // El aviso va aqui y no al final: en landscape el dialogo scrollea, y al
                // final quedaba fuera de la pantalla. Un mensaje que nadie ve equivale a
                // no haberlo escrito, y fue exactamente lo que paso.
                detalleRed?.let {
                    Spacer(Modifier.height(8.dp))
                    Aviso(titulo = "AVISO", cuerpo = it, color = Neon.Warning)
                }

                Spacer(Modifier.height(10.dp))
                SectionLabel("PASO 2  ·  ABRIR EL MANDO")

                /* Siempre habilitado, aunque no se sepa a que red esta unido el telefono.
                 *
                 * Antes se exigia reconocer el SSID para dejar tocarlo, y en Android 9 ese
                 * nombre no se puede leer sin permiso de ubicacion: el boton quedaba muerto
                 * para siempre con el carro perfectamente conectado.
                 *
                 * El SSID era ademas el dato equivocado para decidir. Lo que importa no es
                 * como se llama la red sino si el carro contesta, y eso se sabe abriendo el
                 * socket, que tarda cinco segundos y falla con un motivo concreto. Un boton
                 * apagado no explica nada; un intento fallido si. */
                TextButton(
                    onClick = onConectar,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "CONECTAR AL CARRO",
                        color = if (enSuRed) Neon.Ok else Neon.Cyan,
                        fontSize = 13.sp,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Hint("Si avisa que la red no tiene internet, mantené la conexión.")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar", color = Neon.Cyan)
            }
        },
        dismissButton = {
            TextButton(onClick = onCambiarCarro) {
                Text("Cambiar de carro", color = Neon.TextMuted, fontSize = 13.sp)
            }
        }
    )
}

@Composable
private fun Aviso(titulo: String, cuerpo: String, color: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Text(
            text = titulo,
            color = color,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(text = cuerpo, color = Neon.TextPrimary, fontSize = 12.sp)
    }
}

@Composable
private fun ConnectionDialog(
    carro: Carro,
    onCambiarCarro: () -> Unit,
    devices: List<BtDevice>,
    scanning: Boolean,
    bluetoothReady: Boolean,
    error: String?,
    errorTitle: String,
    onRescan: () -> Unit,
    onPickDevice: (BtDevice) -> Unit,
    onPickSimulator: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var endpoint by remember { mutableStateOf(TcpClient.DEFAULT_ENDPOINT) }
    var showSimulator by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Neon.Surface,
        titleContentColor = Neon.TextPrimary,
        textContentColor = Neon.TextMuted,
        title = {
            // Que carro se esta conectando, arriba de todo: con dos carros que se
            // conectan por medios distintos, equivocarse de uno lleva a buscar un
            // dispositivo que nunca va a aparecer.
            Column {
                Text("Conectar", fontSize = 16.sp)
                Text(
                    text = "${carro.nombre.uppercase()}  ·  ${carro.medio}",
                    color = Neon.Cyan.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            // En landscape el dialogo es muy bajo. Sin scroll, la lista de dispositivos
            // queda fuera de la pantalla y el usuario cree que la app no los encuentra.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // El error del intento anterior se muestra aqui y no bajo el boton del
                // mando: alli entra en un texto diminuto y estos mensajes son la unica
                // pista util cuando el modulo no coopera.
                if (error != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Neon.Danger.copy(alpha = 0.10f))
                            .border(1.dp, Neon.Danger.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = errorTitle,
                            color = Neon.Danger,
                            fontSize = 10.sp,
                            letterSpacing = 1.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(text = error, color = Neon.TextPrimary, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SectionLabel("DISPOSITIVOS BLUETOOTH")
                    if (scanning) {
                        CircularProgressIndicator(
                            color = Neon.Cyan,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        TextButton(
                            onClick = onRescan,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("BUSCAR", color = Neon.Cyan, fontSize = 11.sp)
                        }
                    }
                }

                when {
                    !bluetoothReady -> Text(
                        text = "Bluetooth apagado. Activalo desde los ajustes del telefono " +
                            "y vuelve a abrir esta ventana.",
                        color = Neon.TextMuted,
                        fontSize = 12.sp
                    )
                    devices.isEmpty() -> Text(
                        text = if (scanning) {
                            "Buscando dispositivos cercanos..."
                        } else {
                            "No se encontro ningun dispositivo. Verifica que el carro este " +
                                "encendido y que el LED del HC-05 parpadee."
                        },
                        color = Neon.TextMuted,
                        fontSize = 12.sp
                    )
                    // Column y no LazyColumn: la lista es corta y anidar un contenedor
                    // con scroll propio dentro de otro revienta la medicion de altura.
                    else -> Column {
                        devices.forEach { device ->
                            DeviceRow(device) { onPickDevice(device) }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // El simulador es una herramienta de desarrollo: se guarda detras de un
                // toque para que no compita con el caso real de uso, que es el HC-05.
                TextButton(
                    onClick = { showSimulator = !showSimulator },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (showSimulator) {
                            "▾  SIMULADOR EN EL PC"
                        } else {
                            "▸  USAR SIMULADOR EN EL PC"
                        },
                        color = Neon.TextMuted,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp
                    )
                }

                if (showSimulator) {
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        singleLine = true,
                        label = { Text("host:puerto", fontSize = 11.sp) },
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Neon.TextPrimary,
                            unfocusedTextColor = Neon.TextPrimary,
                            focusedBorderColor = Neon.Cyan,
                            unfocusedBorderColor = Neon.Outline,
                            focusedLabelColor = Neon.Cyan,
                            unfocusedLabelColor = Neon.TextMuted
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "Desde un telefono real, la IP del PC en la red WiFi. " +
                            "Desde el emulador, 10.0.2.2.",
                        color = Neon.TextMuted,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )

                    TextButton(
                        onClick = { onPickSimulator(endpoint) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("CONECTAR AL SIMULADOR", color = Neon.Cyan, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar", color = Neon.Cyan)
            }
        },
        dismissButton = {
            TextButton(onClick = onCambiarCarro) {
                Text("Cambiar de carro", color = Neon.TextMuted, fontSize = 13.sp)
            }
        }
    )
}

@Composable
private fun DeviceRow(device: BtDevice, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(device.name, color = Neon.TextPrimary, fontSize = 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(device.address, color = Neon.TextMuted, fontSize = 11.sp)
                // La radio decide el protocolo. Un modulo rotulado HC-05 que aparezca
                // como BLE es un clon y no habla RFCOMM; mostrarlo evita media hora de
                // pelea contra el transporte equivocado.
                radioLabel(device.radio)?.let { etiqueta ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = etiqueta,
                        color = if (device.radio == Radio.LE) Neon.Warning else Neon.Blue,
                        fontSize = 9.sp,
                        letterSpacing = 0.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (device.bonded) {
            Text(
                text = "VINCULADO",
                color = Neon.Ok,
                fontSize = 9.sp,
                letterSpacing = 1.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // El RSSI evita el juego de adivinar cual de tres HC-05 identicos es el propio:
        // el que este sobre la mesa marcara bastante mas que los del resto del salon.
        device.rssi?.let { rssi ->
            Spacer(Modifier.width(10.dp))
            Text(
                text = "$rssi dBm",
                color = if (rssi > -70) Neon.Cyan else Neon.TextMuted,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        color = Neon.TextMuted,
        fontSize = 9.sp,
        modifier = Modifier.padding(top = 4.dp, start = 2.dp)
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = Neon.Cyan.copy(alpha = 0.75f),
        fontSize = 10.sp,
        letterSpacing = 1.5.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

/* Cuentas del autor. Si alguna cambia, se cambia aqui y en ningun otro lado. */
private const val URL_INSTAGRAM = "https://www.instagram.com/jeison.cstml_"
private const val URL_GITHUB = "https://github.com/DENCODE31"

/**
 * Abre la pantalla de WiFi del sistema.
 *
 * Es la unica via en telefonos anteriores a Android 10, y ahorra explicar con palabras
 * un recorrido por los ajustes que cambia de marca en marca.
 */
private fun abrirAjustesWifi(contexto: android.content.Context) {
    runCatching {
        contexto.startActivity(
            Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun radioLabel(radio: Radio): String? = when (radio) {
    Radio.LE -> "BLE"
    Radio.CLASSIC -> "CLASSIC"
    Radio.DUAL -> "DUAL"
    Radio.UNKNOWN -> null
}

private fun statusText(state: LinkState, deviceName: String?): String = when (state) {
    LinkState.CONNECTED -> deviceName?.uppercase() ?: "CONECTADO"
    LinkState.CONNECTING -> "CONECTANDO"
    LinkState.ERROR -> "ERROR"
    LinkState.DISCONNECTED -> "SIN ENLACE"
}

private fun statusColor(state: LinkState): Color = when (state) {
    LinkState.CONNECTED -> Neon.Ok
    LinkState.CONNECTING -> Neon.Warning
    LinkState.ERROR -> Neon.Danger
    LinkState.DISCONNECTED -> Neon.TextMuted
}
