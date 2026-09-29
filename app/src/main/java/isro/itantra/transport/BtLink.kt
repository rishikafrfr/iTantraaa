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

    private val sendLock = Any()

    private lateinit var context: Context

    private fun adapter(): BluetoothAdapter? {
        if (!::context.isInitialized) return null

        return (
                context.getSystemService(Context.BLUETOOTH_SERVICE)
                        as? BluetoothManager
                )?.adapter
    }

    fun attach(context: Context) {
        this.context = context.applicationContext
    }

    /**
     * Start listening for one incoming Bluetooth RFCOMM connection.
     */
    fun host() {
        if (!::context.isInitialized) {
            onState(LinkState.ERROR, "Bluetooth not initialized")
            return
        }

        scope.launch {
            try {
                val a = adapter()

                if (a == null) {
                    onState(LinkState.ERROR, "no bluetooth adapter")
                    return@launch
                }

                if (!a.isEnabled) {
                    onState(LinkState.ERROR, "Bluetooth is off")
                    return@launch
                }

                reset()

                val deviceName =
                    runCatching { a.name }.getOrNull() ?: "Bluetooth"

                onState(LinkState.HOSTING, deviceName)

                val ss = a.listenUsingRfcommWithServiceRecord(
                    SERVICE_NAME,
                    SPP_UUID
                )

                server = ss

                val acceptedSocket = ss.accept()

                runCatching { ss.close() }
                server = null

                onConnected(acceptedSocket)

            } catch (e: SecurityException) {
                onState(
                    LinkState.ERROR,
                    "Bluetooth permission denied"
                )
            } catch (e: IOException) {
                if (running.get()) {
                    onState(
                        LinkState.ERROR,
                        "Bluetooth host failed: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Connect to an already-paired Bluetooth device.
     *
     * The argument can be either the device MAC address
     * or part/all of its Bluetooth name.
     */
    fun join(peer: String) {
        if (!::context.isInitialized) {
            onState(LinkState.ERROR, "Bluetooth not initialized")
            return
        }

        scope.launch {
            try {
                val a = adapter()

                if (a == null) {
                    onState(LinkState.ERROR, "No Bluetooth adapter")
                    return@launch
                }

                if (!a.isEnabled) {
                    onState(LinkState.ERROR, "Bluetooth is off")
                    return@launch
                }

                reset()
                onState(LinkState.CONNECTING, peer)

                @SuppressLint("MissingPermission")
                val device = a.bondedDevices.firstOrNull {
                    it.address.equals(peer.trim(), ignoreCase = true) ||
                            (it.name?.trim()?.equals(peer.trim(), ignoreCase = true) == true)
                }

                if (device == null) {
                    onState(
                        LinkState.ERROR,
                        "No paired Bluetooth device matching '$peer'"
                    )
                    return@launch
                }

                @SuppressLint("MissingPermission")
                val newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)

                socket = newSocket

                newSocket.connect()

                onConnected(newSocket)

            } catch (e: SecurityException) {
                onState(
                    LinkState.ERROR,
                    "Bluetooth permission denied"
                )
            } catch (e: IOException) {
                runCatching { socket?.close() }
                socket = null
                out = null

                onState(
                    LinkState.ERROR,
                    "Bluetooth connection failed: ${e.message}"
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun onConnected(s: BluetoothSocket) {
        // Do NOT close the socket we have just connected with.
        // join() already stores this same socket in socket.
        if (socket != null && socket !== s) {
            runCatching { socket?.close() }
        }

        socket = s
        out = DataOutputStream(s.outputStream)

        running.set(true)

        val remoteName =
            runCatching { s.remoteDevice.name }.getOrNull()
                ?: runCatching { s.remoteDevice.address }.getOrNull()
                ?: "Bluetooth device"

        onState(
            LinkState.CONNECTED,
            remoteName
        )

        readerJob?.cancel()
        heartbeatJob?.cancel()

        readerJob = scope.launch {
            val parser = Wire.StreamParser()
            val buf = ByteArray(8192)

            try {
                val ins = DataInputStream(s.inputStream)

                while (running.get() && socket === s) {
                    val n = ins.read(buf)

                    if (n < 0) break
                    if (n == 0) continue

                    parser.feed(buf, n)

                    parser
                        .drain()
                        .forEach { frame ->
                            onFrame(frame)
                        }
                }

            } catch (e: IOException) {
                if (running.get() && socket === s) {
                    onState(
                        LinkState.DISCONNECTED,
                        "connection lost: ${e.message ?: "Bluetooth socket closed"}"
                    )
                }
            } finally {
                // Only clean up if this is still the active socket.
                if (socket === s) {
                    running.set(false)
                    socket = null
                    out = null

                    onState(
                        LinkState.DISCONNECTED,
                        "connection lost"
                    )
                }
            }
        }

        heartbeatJob = scope.launch {
            var pingId = 1L

            while (running.get() && socket === s) {
                delay(2000)

                if (!running.get() || socket !== s) break

                val ok = send(
                    Wire.Frame(
                        type = Wire.TYPE_PING,
                        msgId = pingId++
                    )
                )

                if (!ok && running.get() && socket === s) {
                    running.set(false)
                    runCatching { s.close() }
                    onState(
                        LinkState.DISCONNECTED,
                        "Bluetooth send failed"
                    )
                    break
                }
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

        readerJob = null
        heartbeatJob = null

        runCatching {
            server?.close()
        }

        runCatching {
            socket?.close()
        }

        server = null
        socket = null
        out = null
    }

    fun close() {
        reset()

        onState(
            LinkState.DISCONNECTED,
            "closed"
        )
    }

    companion object {
        private const val SERVICE_NAME = "itantra"

        val SPP_UUID: UUID =
            UUID.fromString(
                "00001101-0000-1000-8000-00805F9B34FB"
            )
    }
}