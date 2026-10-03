package com.mtgofa.carinfo.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.mtgofa.carinfo.Settings
import com.mtgofa.carinfo.obd.LinkState
import com.mtgofa.carinfo.obd.Obd
import com.mtgofa.carinfo.obd.Target
import kotlinx.coroutines.delay

data class FoundDevice(val address: String, val name: String, val type: Int, val paired: Boolean, val rssi: Int? = null) {
    val looksLikeObd: Boolean
        get() = OBD_NAMES.any { name.contains(it, ignoreCase = true) }
    val typeLabel: String
        get() = when (type) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"
            BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
            BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual"
            else -> "Bluetooth"
        }
}

private val OBD_NAMES = listOf(
    "OBD", "ELM", "Vgate", "iCar", "V-LINK", "VLINK", "Konnwei", "KW9", "Viecar", "Veepeak", "OBDLink",
    "Carista", "BAFX", "Scan", "CAN", "LELink", "Torque", "Kiwi", "BlueDriver", "FIXD",
)

fun btPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

fun hasBtPermissions(ctx: Context) =
    btPermissions().all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

@SuppressLint("MissingPermission")
fun pairedDevices(ctx: Context): List<FoundDevice> {
    if (!hasBtPermissions(ctx)) return emptyList()
    val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
    return runCatching {
        adapter.bondedDevices.map { FoundDevice(it.address, it.name ?: it.address, it.type, true) }
    }.getOrDefault(emptyList())
}

@SuppressLint("MissingPermission")
@Composable
fun ConnectScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val link by Obd.link.collectAsState()
    val adapter = remember { ctx.getSystemService(BluetoothManager::class.java)?.adapter }
    var granted by remember { mutableStateOf(hasBtPermissions(ctx)) }
    var btOn by remember { mutableStateOf(adapter?.isEnabled == true) }
    val found = remember { mutableStateMapOf<String, FoundDevice>() }
    var scanning by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf(Settings.wifiHost) }
    var port by remember { mutableStateOf(Settings.wifiPort.toString()) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasBtPermissions(ctx)
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        btOn = adapter?.isEnabled == true
    }

    LaunchedEffect(granted, btOn) {
        if (granted) pairedDevices(ctx).forEach { found[it.address] = it }
    }

    // Classic discovery results.
    DisposableEffect(granted) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                            i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        val rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                        if (d != null) runCatching {
                            val name = d.name ?: return@runCatching
                            found[d.address] = FoundDevice(d.address, name, d.type, d.bondState == BluetoothDevice.BOND_BONDED, rssi)
                        }
                    }
                    BluetoothAdapter.ACTION_STATE_CHANGED -> btOn = adapter?.isEnabled == true
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose {
            runCatching { ctx.unregisterReceiver(receiver) }
            if (hasBtPermissions(ctx)) runCatching { adapter?.cancelDiscovery() }
        }
    }

    val bleCallback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                val name = runCatching { d.name }.getOrNull() ?: result.scanRecord?.deviceName ?: return
                val existing = found[d.address]
                found[d.address] = FoundDevice(d.address, name, existing?.type ?: BluetoothDevice.DEVICE_TYPE_LE, existing?.paired ?: false, result.rssi)
            }
        }
    }

    LaunchedEffect(scanning) {
        if (!scanning) return@LaunchedEffect
        runCatching { adapter?.startDiscovery() }
        runCatching { adapter?.bluetoothLeScanner?.startScan(bleCallback) }
        delay(12_000)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(bleCallback) }
        runCatching { adapter?.cancelDiscovery() }
        scanning = false
    }
    DisposableEffect(Unit) {
        onDispose { if (hasBtPermissions(ctx)) runCatching { adapter?.bluetoothLeScanner?.stopScan(bleCallback) } }
    }

    // Go back to the previous screen once we're connected.
    var wasConnecting by remember { mutableStateOf(false) }
    LaunchedEffect(link.state) {
        if (link.state == LinkState.Connecting || link.state == LinkState.Initializing) wasConnecting = true
        if (link.state == LinkState.Connected && wasConnecting) onBack()
    }

    fun connectBt(d: FoundDevice) {
        if (scanning) scanning = false
        Obd.connect(Target.Bt(d.address, d.name))
    }

    ScreenScaffold("Connect adapter", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            AppCard(Modifier.fillMaxWidth(), accent = when (link.state) {
                LinkState.Connected -> AppColors.green; LinkState.Error -> AppColors.red; else -> null
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (link.state) {
                                LinkState.Connected -> "Connected"
                                LinkState.Connecting, LinkState.Initializing -> link.message
                                LinkState.Error -> "Couldn't connect"
                                LinkState.Disconnected -> "Not connected"
                            },
                            color = AppColors.text, fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                        )
                        val sub = when (link.state) {
                            LinkState.Connected -> link.via
                            LinkState.Error -> link.message
                            LinkState.Disconnected -> "Plug the ELM327 into the OBD port, turn the ignition on, then pick it below."
                            else -> link.via
                        }
                        if (sub.isNotBlank()) Text(sub, color = if (link.state == LinkState.Error) AppColors.red else AppColors.dim, fontSize = 13.sp)
                    }
                    Box(Modifier.clip(RoundedCornerShape(50)).clickable {
                        // Quick reconnect to the last adapter used.
                        if (link.state == LinkState.Disconnected || link.state == LinkState.Error)
                            Target.decode(Settings.lastTarget)?.let { Obd.connect(it) }
                    }) { StatusPill(link.state) }
                }
                if (link.state == LinkState.Connected || link.state == LinkState.Connecting || link.state == LinkState.Initializing) {
                    Spacer(Modifier.height(10.dp))
                    AppButton("Disconnect", { Obd.disconnect() }, Modifier.fillMaxWidth(), color = AppColors.red)
                }
            }

            SectionLabel("Bluetooth")
            when {
                adapter == null -> Text("This phone has no Bluetooth.", color = AppColors.dim)
                !granted -> {
                    Text("Allow Bluetooth access to find your OBD2 adapter.", color = AppColors.dim, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    AppButton("Allow Bluetooth", { permLauncher.launch(btPermissions()) }, Modifier.fillMaxWidth())
                }
                !btOn -> AppButton("Turn on Bluetooth", {
                    enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }, Modifier.fillMaxWidth())
                else -> {
                    AppButton(
                        if (scanning) "Scanning…" else "Scan for adapters (Classic + BLE)",
                        { scanning = true }, Modifier.fillMaxWidth(), enabled = !scanning,
                    )
                    Spacer(Modifier.height(10.dp))
                    val list = found.values.sortedWith(
                        compareByDescending<FoundDevice> { it.looksLikeObd }.thenByDescending { it.paired }.thenBy { it.name }
                    )
                    if (list.isEmpty()) Text(
                        "No devices yet. Pair your adapter in Android Bluetooth settings (PIN is usually 1234 or 0000), " +
                            "or tap Scan — BLE adapters don't need pairing.",
                        color = AppColors.dim, fontSize = 13.sp,
                    )
                    list.forEach { d -> DeviceRow(d, link.state) { connectBt(d) } }
                    if (scanning) Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = AppColors.cyan, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Looking for nearby adapters…", color = AppColors.dim, fontSize = 13.sp)
                    }
                }
            }

            SectionLabel("Wi-Fi adapter")
            Text("Join the adapter's Wi-Fi network first (often \"WiFi_OBDII\").", color = AppColors.dim, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(host, { host = it }, Modifier.weight(1f), label = { Text("IP") }, singleLine = true, colors = fieldColors())
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit) }, Modifier.width(100.dp), label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = fieldColors(),
                )
            }
            Spacer(Modifier.height(8.dp))
            AppButton("Connect over Wi-Fi", {
                val p = port.toIntOrNull() ?: 35000
                Settings.updateWifi(host.trim(), p)
                Obd.connect(Target.Wifi(host.trim(), p))
            }, Modifier.fillMaxWidth(), color = AppColors.blue)

            SectionLabel("No adapter?")
            AppButton("Try the demo car", { Obd.connect(Target.Demo) }, Modifier.fillMaxWidth(), color = AppColors.magenta)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DeviceRow(d: FoundDevice, state: LinkState, onClick: () -> Unit) {
    val busy = state == LinkState.Connecting || state == LinkState.Initializing
    val accent = if (d.looksLikeObd) AppColors.cyan else AppColors.cardBorder
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(AppColors.card)
            .border(1.dp, if (d.looksLikeObd) accent.copy(alpha = 0.5f) else accent, RoundedCornerShape(16.dp))
            .clickable(enabled = !busy) { onClick() }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (d.name.contains("wifi", true)) Icons.Rounded.Wifi else Icons.Rounded.Bluetooth, null,
            tint = if (d.looksLikeObd) AppColors.cyan else AppColors.dim,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, color = AppColors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(d.address, d.typeLabel, if (d.paired) "Paired" else null, d.rssi?.takeIf { it > -200 }?.let { "$it dBm" })
                    .joinToString(" · "),
                color = AppColors.dim, fontSize = 11.sp,
            )
        }
        if (d.looksLikeObd) Text(
            "OBD", color = AppColors.cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(AppColors.cyan.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

