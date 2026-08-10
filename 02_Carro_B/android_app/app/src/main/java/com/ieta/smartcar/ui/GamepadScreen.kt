package com.ieta.smartcar.ui

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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ieta.smartcar.ControllerViewModel
import com.ieta.smartcar.R
import com.ieta.smartcar.control.DriveMode
import com.ieta.smartcar.link.BtDevice
import com.ieta.smartcar.link.LinkState
import com.ieta.smartcar.link.Radio
import com.ieta.smartcar.link.TcpClient
import com.ieta.smartcar.ui.theme.Neon

@Composable
fun GamepadScreen(viewModel: ControllerViewModel) {
    val link = viewModel.link
    val linkState by link.state.collectAsState()
    val deviceName by link.endpointName.collectAsState()
    val lastError by link.lastError.collectAsState()
    val telemetry by link.telemetry.collectAsState()

    val devices by viewModel.scanner.devices.collectAsState()
    val scanning by viewModel.scanner.scanning.collectAsState()
    val scanError by viewModel.scanner.lastError.collectAsState()

    var showDevices by remember { mutableStateOf(false) }
    var showCalibration by remember { mutableStateOf(false) }

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
            val stickDiameter = (maxHeight * 0.52f).coerceIn(150.dp, 260.dp)

            TopBar(
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
            Image(
                painter = painterResource(R.drawable.ic_escudo),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = maxHeight * 0.17f, start = 18.dp)
                    .height((maxHeight * 0.175f).coerceIn(48.dp, 80.dp))
                    .alpha(0.9f)
            )

            // Logo del autor, arrimado a la derecha bajo el engranaje. La banda que
            // queda entre la barra superior y el borde de los sticks esta libre de
            // controles, asi que ninguno de los dos estorba al manejar.
            //
            // Se dimensiona por altura y el ancho lo resuelve la proporcion de la
            // imagen, de modo que las trazas del wordmark se leen completas.
            Image(
                painter = painterResource(R.drawable.ic_brand),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = maxHeight * 0.17f, end = 18.dp)
                    .height((maxHeight * 0.10f).coerceIn(26.dp, 44.dp))
                    .alpha(0.8f)
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
                    .padding(start = 10.dp, bottom = 6.dp)
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
                    .padding(end = 10.dp, bottom = 6.dp)
            )

            CenterConsole(
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
                // Centrada en el pasillo que queda entre los dos sticks. Anclarla arriba
                // dejaba un hueco muerto en el medio de la pantalla.
                modifier = Modifier.align(Alignment.Center)
            )

            BottomBar(
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

    if (showDevices) {
        // Al abrir la ventana se busca solo. Obligar a tocar "buscar" es un paso extra
        // que nadie quiere dar cuando lo unico que busca es su carro.
        LaunchedEffect(Unit) { viewModel.startScan() }
        DisposableEffect(Unit) { onDispose { viewModel.stopScan() } }

        ConnectionDialog(
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
                Hint("Mas alto, hay que mover mas el dedo para el mismo efecto.")

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
            BluetoothGlyph(tint = accent, glyphSize = 34.dp)
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

@Composable
private fun ConnectionDialog(
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
        title = { Text("Conectar", fontSize = 16.sp) },
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
