package dev.earbuds.buds.connection

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.earbuds.buds.protocol.EarbudsSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class EarbudsService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var connection: Job?=null
    private lateinit var live: LiveStatus
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate(); live=LiveStatus(this)
    }
    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action==DISCONNECT) {
            disconnect(); stopSelf(); return START_NOT_STICKY
        }
        val address=intent?.getStringExtra(ADDRESS) ?: return START_NOT_STICKY
        disconnect()
        BudsRepository.update(BudsState(ConnectionPhase.CONNECTING))
        val notification=live.notification(BudsRepository.state.value)
        if (Build.VERSION.SDK_INT>=29) startForeground(LiveStatus.ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(LiveStatus.ID,notification)
        connection=scope.launch {
            var session: EarbudsSession?=null
            try {
                val adapter=getSystemService(BluetoothManager::class.java).adapter
                    ?: error("This phone has no Bluetooth adapter")
                check(adapter.isEnabled) { "Turn Bluetooth on to connect" }
                val wire=BluetoothWire.connect(adapter.getRemoteDevice(address))
                session=EarbudsSession(wire)
                session.authenticate()
                val snapshot=session.snapshot()
                BudsRepository.session=session
                BudsRepository.update(BudsState(ConnectionPhase.CONNECTED,snapshot))
                val notifications=launch {
                    BudsRepository.state.collectLatest {
                        if (it.phase==ConnectionPhase.CONNECTED) live.update(it)
                    }
                }
                try {
                    while (true) {
                        delay(15_000)
                        BudsRepository.snapshot(session.snapshot())
                    }
                } finally { notifications.cancel() }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                BudsRepository.session=null
                BudsRepository.update(BudsState(ConnectionPhase.ERROR,message=
                    (error.message ?: "Connection failed")+". Close Xiaomi Earbuds if its control session is still open."))
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            } finally {
                session?.close()
                if (BudsRepository.session===session) BudsRepository.session=null
            }
        }
        return START_NOT_STICKY
    }
    private fun disconnect(resetState: Boolean = true) {
        connection?.cancel(); connection=null
        BudsRepository.session?.close(); BudsRepository.session=null
        if (resetState) BudsRepository.update(BudsState())
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
    override fun onDestroy() {
        disconnect(resetState=BudsRepository.state.value.phase!=ConnectionPhase.ERROR)
        scope.cancel(); super.onDestroy()
    }
    companion object {
        const val CONNECT="dev.earbuds.buds.CONNECT"
        const val DISCONNECT="dev.earbuds.buds.DISCONNECT"
        const val ADDRESS="address"
    }
}
