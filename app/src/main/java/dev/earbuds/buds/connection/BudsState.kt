package dev.earbuds.buds.connection

import dev.earbuds.buds.protocol.EarbudsSession
import dev.earbuds.buds.protocol.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ConnectionPhase { DISCONNECTED, CONNECTING, CONNECTED, ERROR }
data class BudsState(
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val snapshot: Snapshot? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

/** The foreground service owns the session; UI observes its durable process state. */
object BudsRepository {
    private val mutable=MutableStateFlow(BudsState())
    val state=mutable.asStateFlow()
    @Volatile var session: EarbudsSession? = null
        internal set
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
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
                if (session===active) mutable.update { it.copy(busy=false,message=error.message ?: "Command failed") }
            }
        }
    }
}
