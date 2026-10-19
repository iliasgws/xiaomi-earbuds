package dev.earbuds.buds.connection

import dev.earbuds.buds.protocol.EarbudsSession
import dev.earbuds.buds.protocol.FirmwareCatalog
import dev.earbuds.buds.protocol.Region
import dev.earbuds.buds.protocol.Snapshot
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ConnectionPhase { DISCONNECTED, CONNECTING, CONNECTED, ERROR }
data class OtaState(val stage: String, val progress: Int)
data class BudsState(
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val snapshot: Snapshot? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val ota: OtaState? = null,
    val justFlashed: Boolean = false,
)

/** The foreground service owns the session; UI observes its durable process state. */
object BudsRepository {
    private val mutable=MutableStateFlow(BudsState())
    val state=mutable.asStateFlow()
    @Volatile var session: EarbudsSession? = null
        internal set
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var flashing=false
    @Volatile private var flashCompleted=false
    internal fun update(value: BudsState) { mutable.value=value }
    internal fun snapshot(value: Snapshot) { mutable.update { it.copy(snapshot=value) } }
    fun clearMessage() { mutable.update { it.copy(message=null) } }
    fun command(action: suspend EarbudsSession.() -> Unit) {
        synchronized(this) {
            if (mutable.value.busy || mutable.value.phase!=ConnectionPhase.CONNECTED) return
            mutable.update { it.copy(busy=true,message=null) }
        }
        scope.launch {
            val active=session
            try {
                checkNotNull(active) { "Connection is no longer available" }
                active.action()
                val snapshot=active.snapshot()
                if (session===active) mutable.update { it.copy(snapshot=snapshot,busy=false) }
            } catch (error: Exception) {
                if (dev.earbuds.buds.BuildConfig.DEBUG) android.util.Log.w("BudsCommand", "Earbud command failed", error)
                if (session===active) mutable.update { it.copy(busy=false,message=error.message ?: "Command failed") }
            }
        }
    }

    /** Download and flash [target] region firmware over the live session. */
    fun flash(cacheDir: File, target: Region) {
        synchronized(this) {
            if (flashing || mutable.value.busy || mutable.value.phase!=ConnectionPhase.CONNECTED) return
            flashing=true; flashCompleted=false
            mutable.update { it.copy(busy=true,message=null,justFlashed=false,ota=OtaState("Downloading firmware",0)) }
        }
        scope.launch {
            val active=session
            try {
                checkNotNull(active) { "Connection is no longer available" }
                val image=FirmwareFetcher.fetch(cacheDir,FirmwareCatalog.forRegion(target)) { progress ->
                    if (session===active) mutable.update { it.copy(ota=OtaState("Downloading firmware",progress)) }
                }
                if (session===active) mutable.update { it.copy(ota=OtaState("Installing firmware",0)) }
                active.flash(image,
                    onProgress={ progress ->
                        if (session===active) mutable.update { it.copy(ota=OtaState("Installing firmware",progress)) }
                    },
                    onFlashed={ flashCompleted=true })
                flashSucceeded()
            } catch (error: Exception) {
                if (dev.earbuds.buds.BuildConfig.DEBUG) android.util.Log.w("BudsFirmware", "Firmware operation failed", error)
                mutable.update { it.copy(busy=false,ota=null,message=error.message ?: "Flashing failed") }
            } finally {
                synchronized(this) { flashing=false }
            }
        }
    }

    /** Final success state; safe to call from either the flash job or the service. */
    internal fun flashSucceeded() {
        synchronized(this) { flashing=false }
        mutable.update {
            BudsState(justFlashed=true,message=
                "Firmware flashed. Keep the case open while the earbuds restart, then reconnect.")
        }
    }

    /**
     * True when a flash finished cleanly and the link died because the earbuds
     * rebooted, so the service must not report it as a connection error.
     */
    internal fun consumeFlashCompletion(): Boolean = synchronized(this) {
        if (!flashCompleted) return false
        flashCompleted=false; true
    }
}
