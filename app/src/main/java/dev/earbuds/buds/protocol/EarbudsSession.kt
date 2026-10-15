package dev.earbuds.buds.protocol

import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

interface Wire : AutoCloseable {
    suspend fun send(data: ByteArray)
    suspend fun receive(): ByteArray
}

/** One serialized session. Replies match BOTH opcode and sequence. */
class EarbudsSession(
    private val wire: Wire,
    private val randomFactor: () -> ByteArray = { ByteArray(16).also { SecureRandom().nextBytes(it) } },
    private val timeoutMillis: Long = 8_000,
) : AutoCloseable {
    private val mutex=Mutex()
    private val decoder=FrameDecoder()
    private val packets=ArrayDeque<Packet>()
    private var sequence=0
    private var authenticated=false
    private var peerChallenge=false
    private var peerVerified=false
    private var modelVerified=false
    private val cached=mutableMapOf<Int,ByteArray>()
    /** Firmware region of the connected earbuds, or null before authentication. */
    var region: Region?=null; private set
    private var installedVersion: String?=null

    override fun close() { authenticated=false; wire.close() }
    private suspend fun next(): Packet {
        while (packets.isEmpty()) {
            val data=wire.receive()
            if (data.isEmpty()) throw ProtocolException("Earbuds closed the connection")
            packets.addAll(decoder.feed(data))
        }
        return packets.removeFirst()
    }
    private suspend fun incoming(packet: Packet) {
        when(packet.opcode) {
            0x50 -> {
                if (authenticated || packet.payload.size!=17 || packet.payload[0].u()!=1)
                    throw AuthenticationException("Invalid peer challenge")
                wire.send(packet.reply(byteArrayOf(1)+Auth.response(packet.payload.copyOfRange(1,17))).encode())
                peerChallenge=true
            }
            0x51 -> {
                wire.send(packet.reply(byteArrayOf(1)).encode())
                if (!peerChallenge || !packet.payload.contentEquals(byteArrayOf(1,0)))
                    throw AuthenticationException("Earbuds rejected the authentication response")
                peerVerified=true
            }
            else -> {
                if (!authenticated) throw AuthenticationException("Unexpected pre-auth command")
                if (packet.opcode==0xf4) cached.putAll(configValues(packet.payload))
                if (packet.opcode==0x0e || packet.responseRequired)
                    wire.send(packet.reply(result=if (packet.opcode in listOf(0x0e,0xf4)) 0 else 2).encode())
            }
        }
    }
    private suspend fun request(opcode: Int, data: ByteArray): ByteArray {
        val serial=sequence; sequence=(sequence+1) and 255
        wire.send(Packet.command(opcode,serial,data).encode())
        try {
            return withTimeout(timeoutMillis) {
                var result: ByteArray?=null
                while (result==null) {
                    val packet=next()
                    if (packet.isRequest) incoming(packet)
                    else if (packet.opcode==opcode && packet.sequence==serial) {
                        if (packet.status!=0) throw DeviceException(opcode,packet.status)
                        result=packet.payload
                    }
                }
                requireNotNull(result)
            }
        } catch (error: Exception) {
            if (error !is DeviceException) close()
            throw error
        }
    }
    suspend fun authenticate() = mutex.withLock {
        if (authenticated) return@withLock
        try {
            val factor=randomFactor(); require(factor.size==16)
            val response=request(0x50,byteArrayOf(1)+factor)
            if (response.size!=17 || response[0].u()!=1 ||
                !MessageDigest.isEqual(response.copyOfRange(1,17),Auth.response(factor)))
                throw AuthenticationException("Earbud challenge response does not match")
            if (!request(0x51,byteArrayOf(1,0)).contentEquals(byteArrayOf(1)))
                throw AuthenticationException("Invalid pairing-result acknowledgment")
            withTimeout(timeoutMillis) {
                while (!peerVerified) {
                    val packet=next()
                    if (packet.isRequest) incoming(packet)
                }
            }
            authenticated=true
            val info=targetInfo(request(2,hex("ffffffff")))
            if (info.vid!=0x2717) throw ProtocolException("This profile requires Redmi Buds 6 Pro (2717)")
            region=Region.from(info.pid)
            installedVersion=info.firmware
            modelVerified=true
        } catch (error: Exception) { close(); throw error }
    }
    private fun ready() {
        check(authenticated && modelVerified) { "Connect and authenticate first" }
    }
    private suspend fun configs(ids: List<Int>): Map<Int,ByteArray> {
        val result=configValues(request(0xf3,configIds(ids)))
        if (!result.keys.containsAll(ids)) throw ProtocolException("Device omitted requested configs")
        cached.putAll(result); return result
    }
    private suspend fun set(id: Int, value: ByteArray) { ready(); request(0xf2,configRecord(id,value)) }
    suspend fun snapshot(): Snapshot = mutex.withLock {
        ready()
        val info=targetInfo(request(2,hex("ffffffff")))
        installedVersion=info.firmware
        val values=configs(listOf(11,7,2,37,59,41,55))
        val noise=values.getValue(11)
        if (noise.size!=2) throw ProtocolException("Invalid noise report")
        fun one(id: Int): Int {
            val value=values.getValue(id)
            if (value.size!=1) throw ProtocolException("Invalid config $id value")
            return value[0].u()
        }
        Snapshot(info,NoiseMode.from(noise[0].u()),noise[1].u(),EqPreset.from(one(7)),
            eqProfile(values.getValue(55)),gestureValues(values.getValue(2)),
            one(37)!=0,one(59)!=0,one(41)!=0)
    }
    suspend fun noise(mode: NoiseMode, level: Int? = null) = mutex.withLock {
        ready()
        require(level==null || (mode!=NoiseMode.OFF && level in 0..if (mode==NoiseMode.ANC) 19 else 2))
        request(8,byteArrayOf(2,4,mode.code.toByte()))
        if (level!=null) set(11,byteArrayOf(mode.code.toByte(),level.toByte()))
        val current=configs(listOf(11)).getValue(11)
        if (current.size!=2 || current[0].u()!=mode.code || (level!=null && current[1].u()!=level))
            throw ProtocolException("Noise readback differs from requested setting")
    }
    suspend fun preset(preset: EqPreset) = mutex.withLock {
        set(7,byteArrayOf(preset.code.toByte()))
        if (!configs(listOf(7)).getValue(7).contentEquals(byteArrayOf(preset.code.toByte())))
            throw ProtocolException("EQ readback differs")
    }
    suspend fun toggle(id: Int, enabled: Boolean) = mutex.withLock {
        require(id in listOf(37,59,41))
        val value=byteArrayOf(if (enabled) 1 else 0)
        set(id,value)
        if (!configs(listOf(id)).getValue(id).contentEquals(value)) throw ProtocolException("Toggle readback differs")
    }
    suspend fun gesture(gesture: Gesture, right: Boolean, action: GestureAction) = mutex.withLock {
        require(action in gesture.allowedActions)
        val before=gestureValues(configs(listOf(2)).getValue(2)).getValue(gesture)
        set(2,byteArrayOf(gesture.code.toByte(),if (right) (-1).toByte() else action.code.toByte(),
            if (right) action.code.toByte() else (-1).toByte()))
        val after=gestureValues(configs(listOf(2)).getValue(2)).getValue(gesture)
        val expected=if (right) before.first to action else action to before.second
        if (after!=expected) throw ProtocolException("Gesture/untouched-ear readback differs")
    }
    suspend fun saveEq(gains: List<Int>) = mutex.withLock {
        val profile=eqProfile(configs(listOf(55)).getValue(55))
        require(gains.size==profile.bands.size && gains.all { it in profile.minGain..profile.maxGain })
        val bands=profile.bands.zip(gains) { band,gain -> band.copy(gain=gain) }
        set(55,customEqValue(bands,preview=false))
        if (eqProfile(configs(listOf(55)).getValue(55)).bands!=bands) throw ProtocolException("Custom EQ readback differs")
    }

    /**
     * Stream an OTA image using the official RCSP sequence:
     * 0xE1 offset → 0xE2 inquire → 0xE3 enter → 0xE5 block loop → 0xE6 status → 0x03 reboot.
     * The header is patched for this device's PID (and forced past a downgrade)
     * by [OtaImage.prepare]. [onFlashed] runs while the session lock is still
     * held, after the device has confirmed the update but before the reboot.
     */
    suspend fun flash(image: ByteArray, onProgress: (Int) -> Unit = {}, onFlashed: () -> Unit = {}) = mutex.withLock {
        ready()
        val device=region ?: throw ProtocolException("Earbuds region is unknown")
        val prepared=OtaImage.prepare(image,device.pid,installedVersion)
        onProgress(0)
        val offsetInfo=request(0xe1,byteArrayOf())
        if (offsetInfo.size!=6) throw OtaException("Unexpected update-file-offset response")
        val flagOffset=offsetInfo.be32(0); val flagLength=offsetInfo.be16(4)
        if (flagOffset<0 || flagLength<=0 || flagOffset+flagLength>prepared.size)
            throw OtaException("Earbuds requested an out-of-range firmware flag")
        val inquire=request(0xe2,prepared.copyOfRange(flagOffset,flagOffset+flagLength))
        // Xiaomi's own DeviceOtaManager treats result 3 (encrypted key mismatch)
        // as a warning and still issues EnterUpdateMode (0xE3). Other nonzero
        // results are fatal (notably 1 = low battery).
        if (inquire.size!=1 || inquire[0].u() !in listOf(0,3))
            throw OtaException("Earbuds rejected the firmware image (${inquire.getOrNull(0)?.u() ?: -1})")
        val enter=request(0xe3,byteArrayOf())
        if (enter.size !in 7..8 || enter[0].u()!=0)
            throw OtaException("Earbuds refused update mode (${enter.getOrNull(0)?.u() ?: -1})")
        val withCrc=enter.size==8 && enter[7].u()!=0
        var offset=enter.be32(1); var length=enter.be16(5); var blocks=0
        while (true) {
            if (offset<0 || length<=0 || offset+length>prepared.size)
                throw OtaException("Earbuds requested an invalid firmware block")
            val block=prepared.copyOfRange(offset,offset+length)
            val reply=request(0xe5,if (withCrc) block+crc32be(block) else block)
            if (reply.size !in 7..9 || reply[0].u()!=0)
                throw OtaException("Firmware block transfer failed (${reply.getOrNull(0)?.u() ?: -1})")
            onProgress(((offset+length)*100L/prepared.size).toInt().coerceAtMost(99))
            val nextOffset=reply.be32(1); val nextLength=reply.be16(5)
            if (nextOffset==0 && nextLength==0) break
            if (reply.size==9) { val wait=reply.be16(7); if (wait>0) delay(wait.toLong()) }
            offset=nextOffset; length=nextLength
            if (++blocks>100_000) throw OtaException("Firmware transfer did not converge")
        }
        val status=request(0xe6,byteArrayOf())
        if (status.size!=1 || status[0].u()!=0)
            throw OtaException("Final update status failed (${status.getOrNull(0)?.u() ?: -1})")
        onProgress(100); onFlashed()
        try { request(0x03,byteArrayOf(0)) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* the earbuds reboot and drop the link */ }
    }
}
