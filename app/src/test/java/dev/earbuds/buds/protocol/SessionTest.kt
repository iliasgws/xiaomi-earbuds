package dev.earbuds.buds.protocol

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SessionTest {
    private class ScriptWire(initial: List<ByteArray>) : Wire {
        val sends=mutableListOf<ByteArray>()
        var closed=false
        val data=Channel<ByteArray>(Channel.UNLIMITED).apply { initial.forEach { trySend(it) } }
        override suspend fun send(data: ByteArray) { sends+=data }
        override suspend fun receive() = data.receive()
        override fun close() { closed=true; data.close() }
    }
    private val factor=hex("f81be8e78d765a2e63339fc99a66320d")
    private fun handshake() = listOf(
        hex("fedcba04500013000001f8955578093e0cdf56e7c74436605f08ef"),
        hex("fedcba04510003000101ef"),
        hex("fedcbac05000120001490b58fa1d22344b3413011ee49d4ff5ef"),
        hex("fedcbac0510003010100ef"),
        Packet(4,2,2,ProtocolTest.TARGET_PAYLOAD).encode(),
    )
    @Test fun completesCapturedMutualAuthAndRejectsUnrelatedResponses() = runTest {
        val unrelated=Packet(4,0x50,99,byteArrayOf(1)+ByteArray(16)).encode()
        val wire=ScriptWire(listOf(unrelated)+handshake())
        val client=EarbudsSession(wire,{factor})
        client.authenticate()
        assertEquals("fedcbac45000120001f81be8e78d765a2e63339fc99a66320def",wire.sends[0].hexString())
        assertEquals("fedcba04500013000001c05c35726a1303df4fa415aa5c2dbd8fef",wire.sends[2].hexString())
        assertEquals("fedcba04510003000101ef",wire.sends[3].hexString())
        assertFalse(wire.closed)
    }
    @Test fun wrongAuthResponseClosesConnection() = runTest {
        val response=handshake()[0].copyOf().also { it[15]=(it[15].u() xor 1).toByte() }
        val wire=ScriptWire(listOf(response))
        try { EarbudsSession(wire,{factor}).authenticate(); fail("Expected authentication failure") }
        catch (_: AuthenticationException) { assertTrue(wire.closed) }
    }
    @Test fun timeoutClosesConnection() = runTest {
        val wire=ScriptWire(emptyList())
        try { EarbudsSession(wire,{factor},10).authenticate(); fail("Expected timeout") }
        catch (_: kotlinx.coroutines.TimeoutCancellationException) { assertTrue(wire.closed) }
    }
    @Test fun unsupportedDeviceCannotBeControlled() = runTest {
        val wrong=ProtocolTest.TARGET_PAYLOAD.copyOf().also {
            // Replace identity attribute PID while preserving every framing byte.
            val identity=hex("2717509e")
            val offset=(0..it.size-4).first { p -> it.copyOfRange(p,p+4).contentEquals(identity) }
            it[offset+3]=0x9d.toByte()
        }
        val records=handshake().dropLast(1)+Packet(4,2,2,wrong).encode()
        val wire=ScriptWire(records)
        try { EarbudsSession(wire,{factor}).authenticate(); fail("Expected identity rejection") }
        catch (_: ProtocolException) { assertTrue(wire.closed) }
    }
}
