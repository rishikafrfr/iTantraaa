package isro.itantra.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import isro.itantra.protocol.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bluetooth Classic RFCOMM (SPP) transport — the path for phone-to-phone
 * without Wi-Fi and for ESP32/HC-05 style embedded receivers. Mirrors TcpLink's
 * callback shape so CommApp can switch transports transparently.
 */
class BtLink(
    private val onFrame: (Wire.Frame) -> Unit,
    private val onState: (LinkState, String?) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    private var server: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null
    private var out: DataOutputStream? = null
    private var readerJob: Job? = null
    private var heartbeatJob: Job? = null
    private var sendLock = Any()

    private fun adapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private lateinit var context: Context

    fun attach(context: Context) {
        this.context = context.applicationContext
    }

    /** Accept one incoming SPP connection (blocks in background). */
    @SuppressLint("MissingPermission") // every call below is inside a SecurityException handler
    fun host() {
        if (!::context.isInitialized) throw IllegalStateException("call attach() first")
        val a = adapter() ?: run { onState(LinkState.ERROR, "no bluetooth adapter"); return }
        if (!a.isEnabled) { onState(LinkState.ERROR, "Bluetooth is off"); return }
        reset()
        // adapter.name needs BLUETOOTH_CONNECT on API 31+; this call sits outside the
        // launch block, so an ungranted permission threw SecurityException straight
        // onto the UI thread and killed the app
        onState(LinkState.HOSTING, runCatching { a.name }.getOrNull())
        scope.launch {
            try {
                @SuppressLint("MissingPermission")
                val ss = a.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
                server = ss
                val s = ss.accept()
                server?.close()
                onConnected(s)
            } catch (e: SecurityException) {
                onState(LinkState.ERROR, "bluetooth permission denied")
            } catch (e: IOException) {
                if (running.get()) onState(LinkState.ERROR, "bt host: ${e.message}")
            }
        }
    }

    /** Connect to an already-paired device by name fragment or MAC. */
    @SuppressLint("MissingPermission") // every call below is inside a SecurityException handler
    fun join(peer: String) {
        if (!::context.isInitialized) throw IllegalStateException("call attach() first")
        val a = adapter() ?: run { onState(LinkState.ERROR, "no bluetooth adapter"); return }
        if (!a.isEnabled) { onState(LinkState.ERROR, "Bluetooth is off"); return }
        reset()
        onState(LinkState.CONNECTING, peer)
        scope.launch {
            try {
                @SuppressLint("MissingPermission")
                val device = a.bondedDevices.firstOrNull {
                    it.address.equals(peer, true) || it.name?.contains(peer, true) == true
                } ?: run {
                    onState(LinkState.ERROR, "no paired device matching '$peer'")
                    return@launch
                }
                val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                // no cancelDiscovery(): we only ever connect to already-bonded
                // devices, and that call needs BLUETOOTH_SCAN (API 31+) which this
                // app does not hold — it threw SecurityException and failed the join
                s.connect()
                onConnected(s)
            } catch (e: SecurityException) {
                onState(LinkState.ERROR, "bluetooth permission denied")
            } catch (e: IOException) {
                onState(LinkState.ERROR, "bt join $peer: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun onConnected(s: BluetoothSocket) {
        socket = s
        out = DataOutputStream(s.outputStream)
        running.set(true)
        onState(
            LinkState.CONNECTED,
            runCatching { s.remoteDevice.name }.getOrNull() ?: s.remoteDevice.address,
        )
        readerJob = scope.launch {
            val parser = Wire.StreamParser()
            val buf = ByteArray(8192)
            try {
                val ins = DataInputStream(s.inputStream)
                while (running.get()) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    parser.feed(buf, n)
                    parser.drain().forEach(onFrame)
                }
                onState(LinkState.DISCONNECTED, "peer closed")
            } catch (_: IOException) {
                if (running.get()) onState(LinkState.DISCONNECTED, "connection lost")
            }
        }
        heartbeatJob = scope.launch {
            var pingId = 1L
            while (running.get()) {
                delay(2000)
                runCatching { send(Wire.Frame(Wire.TYPE_PING, msgId = pingId++)) }
            }
        }
    }

    fun send(frame: Wire.Frame): Boolean {
        val o = out ?: return false
        return try {
            synchronized(sendLock) {
                o.write(Wire.encode(frame))
                o.flush()
            }
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun reset() {
        running.set(false)
        readerJob?.cancel()
        heartbeatJob?.cancel()
        runCatching { server?.close() }
        runCatching { socket?.close() }
        server = null
        socket = null
        out = null
    }

    fun close() {
        reset()
        onState(LinkState.DISCONNECTED, "closed")
        // scope stays alive so a later host()/join() still works (see TcpLink.close)
    }

    companion object {
        private const val SERVICE_NAME = "itantra"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
