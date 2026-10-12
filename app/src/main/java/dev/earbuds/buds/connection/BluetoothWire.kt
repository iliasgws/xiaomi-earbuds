package dev.earbuds.buds.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import dev.earbuds.buds.BuildConfig
import dev.earbuds.buds.protocol.Wire
import dev.earbuds.buds.protocol.hexString
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Uses Android's paired-device RFCOMM API; no root or vendor SDK dependency. */
class BluetoothWire private constructor(private val socket: BluetoothSocket) : Wire {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val chunks=Channel<ByteArray>(64)
    private val closed=AtomicBoolean(false)
    init {
        scope.launch {
            try {
                val buffer=ByteArray(4096)
                while (!closed.get()) {
                    val count=socket.inputStream.read(buffer)
                    if (count<0) break
                    if (count>0) {
                        val data=buffer.copyOf(count)
                        if (BuildConfig.DEBUG) Log.d("BudsWire","RX ${data.hexString()}")
                        chunks.send(data)
                    }
                }
                chunks.close()
            } catch (error: Exception) { chunks.close(error) }
        }
    }
    override suspend fun send(data: ByteArray) = withContext(Dispatchers.IO) {
        check(!closed.get()) { "Bluetooth connection is closed" }
        socket.outputStream.write(data)
        socket.outputStream.flush()
        if (BuildConfig.DEBUG) Log.d("BudsWire","TX ${data.hexString()}")
    }
    override suspend fun receive() = chunks.receive()
    override fun close() {
        if (closed.compareAndSet(false,true)) {
            runCatching { socket.close() }
            chunks.close(); scope.cancel()
        }
    }
    companion object {
        val MIUI_UUID: UUID = UUID.fromString("0000fd2d-0000-1000-8000-00805f9b34fb")
        @SuppressLint("MissingPermission")
        suspend fun connect(device: BluetoothDevice): BluetoothWire = withTimeout(15_000) {
            val socket=device.createRfcommSocketToServiceRecord(MIUI_UUID)
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { runCatching { socket.close() } }
                Thread({
                    try {
                        socket.connect()
                        val wire=BluetoothWire(socket)
                        continuation.resume(wire) { _, value, _ -> value.close() }
                    } catch (error: Exception) {
                        runCatching { socket.close() }
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                },"Buds-RFCOMM-connect").apply { isDaemon=true }.start()
            }
        }
    }
}
