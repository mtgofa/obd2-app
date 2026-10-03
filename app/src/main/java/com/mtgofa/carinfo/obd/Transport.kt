package com.mtgofa.carinfo.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlin.concurrent.thread

/** A byte pipe to an ELM327 adapter. Incoming bytes are queued; [read] waits for the next chunk. */
abstract class Transport {
    protected val incoming = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile
    var alive = false
        protected set

    abstract val label: String
    abstract suspend fun open()
    abstract suspend fun write(data: ByteArray)
    abstract fun close()

    suspend fun read(timeoutMs: Long): ByteArray? =
        withTimeoutOrNull(timeoutMs.coerceAtLeast(1)) { incoming.receive() }

    fun drain() {
        while (incoming.tryReceive().isSuccess) Unit
    }
}

/** Shared reader/writer for socket-like transports (Bluetooth SPP, TCP). */
abstract class StreamTransport : Transport() {
    private var out: OutputStream? = null

    protected fun startReader(input: InputStream, output: OutputStream) {
        out = output
        alive = true
        thread(name = "obd-rx", isDaemon = true) {
            val buf = ByteArray(1024)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) incoming.trySend(buf.copyOf(n))
                }
            } catch (_: IOException) {
            }
            alive = false
        }
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        val o = out ?: throw IOException("Not connected")
        o.write(data)
        o.flush()
    }
}

val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

@SuppressLint("MissingPermission")
class ClassicBtTransport(
    private val device: BluetoothDevice,
    private val adapter: BluetoothAdapter?,
) : StreamTransport() {
    private var socket: BluetoothSocket? = null
    override val label: String get() = "Bluetooth · ${device.name ?: device.address}"

    override suspend fun open() = withContext(Dispatchers.IO) {
        runCatching { adapter?.cancelDiscovery() }
        // Cheap clones don't always publish an SDP record, so fall back to insecure and raw channel 1.
        val attempts = listOf<() -> BluetoothSocket>(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            {
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            },
        )
        var last: Exception? = null
        for (attempt in attempts) {
            var s: BluetoothSocket? = null
            try {
                s = attempt()
                s.connect()
                socket = s
                break
            } catch (e: Exception) {
                last = e
                runCatching { s?.close() }
                delay(300)
            }
        }
        val s = socket ?: throw IOException("Bluetooth connection failed: ${last?.message ?: "unknown"}")
        startReader(s.inputStream, s.outputStream)
    }

    override fun close() {
        alive = false
        runCatching { socket?.close() }
        socket = null
    }
}

class WifiTransport(
    private val context: Context,
    private val host: String,
    private val port: Int,
) : StreamTransport() {
    private var socket: Socket? = null
    override val label: String get() = "Wi-Fi · $host:$port"

    override suspend fun open() = withContext(Dispatchers.IO) {
        // The adapter's hotspot has no internet, so Android may prefer mobile data. Pin to Wi-Fi.
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val wifi = cm?.allNetworks?.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        val s = wifi?.socketFactory?.createSocket() ?: Socket()
        try {
            s.connect(InetSocketAddress(host, port), 6000)
            s.tcpNoDelay = true
        } catch (e: Exception) {
            runCatching { s.close() }
            throw IOException("Wi-Fi adapter not reachable at $host:$port (${e.message})")
        }
        socket = s
        startReader(s.getInputStream(), s.getOutputStream())
    }

    override fun close() {
        alive = false
        runCatching { socket?.close() }
        socket = null
    }
}

/** BLE ELM327 clones (Vgate iCar, Konnwei, V-Link...) expose a UART-like service with notify + write chars. */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
) : Transport() {
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val ready = CompletableDeferred<Unit>()
    @Volatile private var writeAck: CompletableDeferred<Unit>? = null
    override val label: String get() = "BLE · ${device.name ?: device.address}"

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                alive = false
                if (!ready.isCompleted) ready.completeExceptionally(IOException("BLE disconnected (status $status)"))
                runCatching { g.close() }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val (notify, write) = pickCharacteristics(g)
            if (notify == null || write == null) {
                ready.completeExceptionally(IOException("No OBD serial service on this BLE device"))
                return
            }
            writeChar = write
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CCCD)
            if (cccd == null) {
                ready.complete(Unit)
                return
            }
            val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, value)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            alive = true
            ready.complete(Unit)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            c.value?.let { incoming.trySend(it.copyOf()) }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            incoming.trySend(value.copyOf())
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeAck?.complete(Unit)
        }
    }

    override suspend fun open() {
        gatt = withContext(Dispatchers.Main) {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        }
        try {
            withTimeout(15_000) { ready.await() }
            alive = true
        } catch (e: Exception) {
            close()
            throw IOException(e.message ?: "BLE connection timed out")
        }
    }

    override suspend fun write(data: ByteArray) {
        val g = gatt ?: throw IOException("Not connected")
        val c = writeChar ?: throw IOException("Not connected")
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        for (chunk in data.toList().chunked(20)) {
            val bytes = chunk.toByteArray()
            val ack = CompletableDeferred<Unit>().also { writeAck = it }
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, bytes, type) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.value = bytes
                c.writeType = type
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
            if (!ok) throw IOException("BLE write failed")
            withTimeoutOrNull(1000) { ack.await() }
        }
    }

    override fun close() {
        alive = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }

    private fun pickCharacteristics(g: BluetoothGatt): Pair<BluetoothGattCharacteristic?, BluetoothGattCharacteristic?> {
        fun BluetoothGattCharacteristic.canNotify() =
            properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        fun BluetoothGattCharacteristic.canWrite() =
            properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

        // Known adapter layouts first, then any custom service with a notify + write pair.
        val known = listOf("0000fff0", "0000ffe0", "000018f0", "e7810a71")
        val services = g.services.sortedBy { s ->
            val i = known.indexOfFirst { s.uuid.toString().startsWith(it) }
            if (i < 0) 99 else i
        }
        for (s in services) {
            val id = s.uuid.toString()
            if (STANDARD_SERVICES.any { id.startsWith(it) }) continue
            val notify = s.characteristics.firstOrNull { it.canNotify() } ?: continue
            val write = s.characteristics.firstOrNull { it.canWrite() && it.uuid != notify.uuid }
                ?: s.characteristics.firstOrNull { it.canWrite() } ?: continue
            return notify to write
        }
        return null to null
    }

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val STANDARD_SERVICES = listOf("00001800", "00001801", "0000180a", "0000180f")
    }
}
