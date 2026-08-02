package com.ieta.smartcar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ieta.smartcar.ControllerViewModel
import com.ieta.smartcar.control.DriveMode
import com.ieta.smartcar.link.LinkState
import com.ieta.smartcar.link.PairedDevice
import com.ieta.smartcar.link.TcpClient
import com.ieta.smartcar.ui.theme.Neon

@Composable
fun GamepadScreen(viewModel: ControllerViewModel) {
    val link = viewModel.link
    val linkState by link.state.collectAsState()
    val deviceName by link.endpointName.collectAsState()
    val lastError by link.lastError.collectAsState()
    val telemetry by link.telemetry.collectAsState()

    var showDevices by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf(emptyList<PairedDevice>()) }

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

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                TopBar(
                    linkState = linkState,
                    deviceName = deviceName,
                    leftPower = viewModel.wheelPower.left,
                    rightPower = viewModel.wheelPower.right,
                    onSettings = {
                        devices = viewModel.pairedDevices()
                        showDevices = true
                    }
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    JoystickPad(
                        label = if (viewModel.mode == DriveMode.ARCADE) "ACELERADOR" else "ORUGA IZQ",
                        axis = StickAxis.VERTICAL,
                        diameter = stickDiameter,
                        accent = Neon.Cyan,
                        onChange = viewModel::onLeftStick
                    )

                    CenterConsole(
                        linkState = linkState,
                        telemetry = telemetry,
                        error = lastError,
                        onToggleConnection = {
                            if (linkState == LinkState.CONNECTED) {
                                viewModel.disconnect()
                            } else {
                                devices = viewModel.pairedDevices()
                                showDevices = true
                            }
                        }
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
                        onChange = viewModel::onRightStick
                    )
                }

                BottomBar(
                    mode = viewModel.mode,
                    speedCapLabel = viewModel.speedCapLabel,
                    emergencyStop = viewModel.emergencyStop,
                    onToggleMode = viewModel::toggleMode,
                    onCycleSpeed = viewModel::cycleSpeedCap,
                    onEmergencyStop = viewModel::toggleEmergencyStop
                )
            }
        }
    }

    if (showDevices) {
        ConnectionDialog(
            devices = devices,
            bluetoothReady = viewModel.spp.isBluetoothReady,
            onPickDevice = {
                viewModel.connectBluetooth(it.address)
                showDevices = false
            },
            onPickSimulator = {
                viewModel.connectSimulator(it)
                showDevices = false
            },
            onDismiss = { showDevices = false }
        )
    }
}

@Composable
private fun TopBar(
    linkState: LinkState,
    deviceName: String?,
    leftPower: Int,
    rightPower: Int,
    onSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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
    onToggleConnection: () -> Unit
) {
    val accent = statusColor(linkState)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(150.dp)
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
    onEmergencyStop: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp),
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
    devices: List<PairedDevice>,
    bluetoothReady: Boolean,
    onPickDevice: (PairedDevice) -> Unit,
    onPickSimulator: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var endpoint by remember { mutableStateOf(TcpClient.DEFAULT_ENDPOINT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Neon.Surface,
        titleContentColor = Neon.TextPrimary,
        textContentColor = Neon.TextMuted,
        title = { Text("Conectar", fontSize = 16.sp) },
        text = {
            Column {
                SectionLabel("SIMULADOR EN EL PC")

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
                    text = "10.0.2.2 es el PC visto desde el emulador. Desde un telefono " +
                        "real, usa la IP del PC en la red WiFi.",
                    color = Neon.TextMuted,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )

                Spacer(Modifier.height(8.dp))

                TextButton(
                    onClick = { onPickSimulator(endpoint) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("CONECTAR AL SIMULADOR", color = Neon.Cyan, fontSize = 12.sp)
                }

                Spacer(Modifier.height(10.dp))
                SectionLabel("HC-05 EMPAREJADO")

                when {
                    !bluetoothReady -> Text(
                        text = "Bluetooth apagado o no disponible en este dispositivo.",
                        color = Neon.TextMuted,
                        fontSize = 12.sp
                    )
                    devices.isEmpty() -> Text(
                        text = "No hay dispositivos emparejados. Empareja el HC-05 desde " +
                            "los ajustes de Bluetooth de Android (PIN 1234 o 0000).",
                        color = Neon.TextMuted,
                        fontSize = 12.sp
                    )
                    else -> LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
                        items(devices) { device ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onPickDevice(device) }
                                    .padding(vertical = 10.dp, horizontal = 8.dp)
                            ) {
                                Text(device.name, color = Neon.TextPrimary, fontSize = 14.sp)
                                Text(device.address, color = Neon.TextMuted, fontSize = 11.sp)
                            }
                        }
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
