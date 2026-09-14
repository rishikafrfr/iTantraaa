package isro.itantra.transport

import isro.itantra.protocol.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

enum class LinkState { DISCONNECTED, HOSTING, CONNECTING, CONNECTED, ERROR }

/**
 * TCP transport for Wi-Fi LAN / hotspot links (host or join). Frame sync via
 * [Wire.StreamParser] survives partial reads and corrupt bytes; heartbeat
 * PINGs keep NAT/table entries warm and give RTT measurements (Phase 5).
 */
class TcpLink(
    private val onFrame: (Wire.Frame) -> Unit,
    private val onState: (LinkState, String?) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var out: DataOutputStream? = null
    private var readerJob: Job? = null
    private var heartbeatJob: Job? = null
    private var sendLock = Any()

    /** cleared when the connected socket dies, so a fresh onConnected is allowed */
    @Volatile private var active: Socket? = null

    val isConnected = MutableStateFlow(false)

    /** Host on [port]; accepts a peer, and keeps accepting after each disconnect. */
    fun host(port: Int = PORT) {
        reset()
        running.set(true) // the accept loop checks this before the first accept
        onState(LinkState.HOSTING, null)
        scope.launch {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                serverSocket = ss
                while (running.get()) {
                    val s = ss.accept()
                    onConnected(s)
                }
            } catch (e: Exception) {
                if (running.get()) onState(LinkState.ERROR, "host: ${e.message}")
            }
        }
    }

    fun join(host: String, port: Int = PORT) {
        reset()
        onState(LinkState.CONNECTING, host)
        scope.launch {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 5000)
                s.tcpNoDelay = true
                onConnected(s)
            } catch (e: Exception) {
                onState(LinkState.ERROR, "join $host:$port failed: ${e.message}")
            }
        }
    }

    private fun onConnected(s: Socket) {
        active = s
        socket = s
        s.tcpNoDelay = true
        out = DataOutputStream(s.getOutputStream())
        running.set(true)
        isConnected.value = true
        onState(LinkState.CONNECTED, s.inetAddress.hostAddress)
        readerJob = scope.launch {
            val parser = Wire.StreamParser()
            val buf = ByteArray(8192)
            try {
                val ins = DataInputStream(s.getInputStream())
                while (running.get()) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    parser.feed(buf, n)
                    parser.drain().forEach(onFrame)
                }
                onState(LinkState.DISCONNECTED, "peer closed")
            } catch (_: IOException) {
                if (running.get()) onState(LinkState.DISCONNECTED, "connection lost")
            } finally {
                if (active === s) {
                    runCatching { s.close() }
                    active = null
                    isConnected.value = false
                }
            }
        }
        heartbeatJob = scope.launch {
            var pingId = 1L
            while (running.get() && active === s) {
                delay(2000)
                val t0 = android.os.SystemClock.elapsedRealtime() * 1000
                val payload = java.nio.ByteBuffer.allocate(8).putLong(t0).array()
                runCatching { send(Wire.Frame(Wire.TYPE_PING, msgId = pingId++, payload = payload)) }
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
        runCatching { serverSocket?.close() }
        runCatching { socket?.close() }
        serverSocket = null
        socket = null
        out = null
        isConnected.value = false
    }

    fun close() {
        reset()
        onState(LinkState.DISCONNECTED, "closed")
        // NB: the scope stays alive on purpose — cancelling it made host()/join()
        // silently no-op forever after a single disconnect()
    }

    companion object {
        const val PORT = 4747
    }
}

/** Utility: run a one-shot server accept on a worker (used by tests). */
suspend fun <T> withContextIo(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
