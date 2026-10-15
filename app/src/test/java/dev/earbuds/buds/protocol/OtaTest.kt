package dev.earbuds.buds.protocol

import java.security.MessageDigest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OtaImageTest {
    private fun synthetic(pid: Int, version: String, bodySize: Int = 500): ByteArray {
        val header = ByteArray(OtaImage.HEADER_SIZE)
        header[0x50]=0x27; header[0x51]=0x17
        header[0x52]=(pid shr 8).toByte(); header[0x53]=pid.toByte()
        val digits=version.split(".").map(String::toInt)
        header[0x54]=((digits[0] shl 4) or digits[1]).toByte()
        header[0x55]=((digits[2] shl 4) or digits[3]).toByte()
        header[0x56]=bodySize.toByte(); header[0x57]=(bodySize shr 8).toByte()
        val body=ByteArray(bodySize) { (it*31+7).toByte() }
        MessageDigest.getInstance("SHA-256").digest(body).copyInto(header,0)
        return header+body
    }

    @Test fun verifiesPayloadAgainstHeaderHash() {
        val image=synthetic(0x509d,"1.1.9.9")
        OtaImage.verify(image)
        assertThrows(OtaException::class.java) {
            OtaImage.verify(image.copyOf().also { it[OtaImage.HEADER_SIZE+10]=(it[OtaImage.HEADER_SIZE+10].u() xor 1).toByte() })
        }
        assertThrows(OtaException::class.java) { OtaImage.verify(ByteArray(100)) }
    }

    @Test fun parsesHeaderFields() {
        val header=OtaImage.header(synthetic(0x509d,"1.1.9.9",0x1234))
        assertEquals(0x2717,header.vid); assertEquals(0x509d,header.pid)
        assertEquals("1.1.9.9",header.version); assertEquals(0x1234,header.bodyLength)
    }

    @Test fun retargetsPidAndKeepsPayloadHashValid() {
        val image=synthetic(0x509d,"1.1.9.9")
        val prepared=OtaImage.prepare(image,0x509e,"1.1.9.9")
        OtaImage.verify(prepared)
        assertEquals(0x509e,OtaImage.header(prepared).pid)
        assertArrayEquals(image.copyOfRange(OtaImage.HEADER_SIZE,image.size),
            prepared.copyOfRange(OtaImage.HEADER_SIZE,prepared.size))
    }

    @Test fun forcesVersionPastADowngradeButKeepsUpgrades() {
        val downgraded=OtaImage.prepare(synthetic(0x509d,"1.1.9.9"),0x509e,"1.3.9.9")
        assertEquals("9.9.9.9",OtaImage.header(downgraded).version)
        val upgrade=OtaImage.prepare(synthetic(0x509e,"1.3.9.9"),0x509e,"1.1.9.9")
        assertEquals("1.3.9.9",OtaImage.header(upgrade).version)
        val unchanged=OtaImage.prepare(synthetic(0x509e,"1.3.9.9"),0x509e,"1.3.9.9")
        assertEquals("1.3.9.9",OtaImage.header(unchanged).version)
    }

    @Test fun catalogCarriesVerifiedOfficialBinaries() {
        for (image in listOf(FirmwareCatalog.global,FirmwareCatalog.china)) {
            assertTrue(image.url.startsWith("https://"))
            assertTrue(image.size>1_000_000)
            assertTrue(image.md5.matches(Regex("[0-9a-f]{32}")))
        }
        assertEquals(0x509e,FirmwareCatalog.global.region.pid)
        assertEquals(0x509d,FirmwareCatalog.china.region.pid)
        assertEquals("900150983cd24fb0d6963f7d28e17f72",md5Of("abc".toByteArray()))
    }
}

class OtaFlashTest {
    private class ScriptWire(initial: List<ByteArray>) : Wire {
        val sends=mutableListOf<Packet>()
        var closed=false
        val data=Channel<ByteArray>(Channel.UNLIMITED).apply { initial.forEach { trySend(it) } }
        override suspend fun send(data: ByteArray) { sends+=Packet.decode(data) }
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
    private fun synthetic(pid: Int, version: String, bodySize: Int): ByteArray {
        val header=ByteArray(OtaImage.HEADER_SIZE)
        header[0x50]=0x27; header[0x51]=0x17
        header[0x52]=(pid shr 8).toByte(); header[0x53]=pid.toByte()
        val digits=version.split(".").map(String::toInt)
        header[0x54]=((digits[0] shl 4) or digits[1]).toByte()
        header[0x55]=((digits[2] shl 4) or digits[3]).toByte()
        header[0x56]=bodySize.toByte(); header[0x57]=(bodySize shr 8).toByte()
        val body=ByteArray(bodySize) { (it*31+7).toByte() }
        MessageDigest.getInstance("SHA-256").digest(body).copyInto(header,0)
        return header+body
    }
    private fun response(opcode: Int, sequence: Int, payload: ByteArray) =
        Packet(4,opcode,sequence,payload).encode()

    @Test fun streamsPatchedChinaImageThroughOfficialSequence() = runTest {
        // 756-byte image: header 256 + body 500; device asks for blocks 0..300 then 300..756.
        val image=synthetic(0x509d,"1.1.9.9",500)
        val records=handshake()+listOf(
            response(0xe1,3,be32Bytes(0)+be16Bytes(0x60)),           // flag = header[0,96)
            response(0xe2,4,byteArrayOf(3)),                          // official client continues on encrypted-key warning
            response(0xe3,5,byteArrayOf(0)+be32Bytes(0)+be16Bytes(300)+byteArrayOf(1)), // CRC on
            response(0xe5,6,byteArrayOf(0)+be32Bytes(300)+be16Bytes(456)+be16Bytes(0)),
            response(0xe5,7,byteArrayOf(0)+be32Bytes(0)+be16Bytes(0)),
            response(0xe6,8,byteArrayOf(0)),
            response(0x03,9,byteArrayOf(0)),
        )
        val wire=ScriptWire(records)
        val client=EarbudsSession(wire,{factor})
        client.authenticate()
        assertEquals(Region.GLOBAL,client.region)
        val progress=mutableListOf<Int>(); var flashed=false
        client.flash(image,onProgress={progress+=it},onFlashed={flashed=true})
        assertTrue(flashed)
        assertEquals(listOf(0,39,99,100),progress)

        assertEquals(listOf(0x50,0x51,0x50,0x51,0x02,0xe1,0xe2,0xe3,0xe5,0xe5,0xe6,0x03),
            wire.sends.map { it.opcode })
        val prepared=OtaImage.prepare(image,0x509e,"1.3.9.9")

        // 0xE2 flag carries the retargeted header: PID 509E and a version past the downgrade.
        val flag=wire.sends[6].payload
        assertEquals(0x60,flag.size)
        assertArrayEquals(hex("2717"),flag.copyOfRange(0x50,0x52))
        assertArrayEquals(hex("509e"),flag.copyOfRange(0x52,0x54))
        assertArrayEquals(hex("9999"),flag.copyOfRange(0x54,0x56))

        // 0xE5 blocks reassemble the prepared image, with the CRC32 trailer the device requested.
        val first=wire.sends[8].payload
        assertEquals(304,first.size)
        assertArrayEquals(prepared.copyOfRange(0,300),first.copyOfRange(0,300))
        assertArrayEquals(crc32be(prepared.copyOfRange(0,300)),first.copyOfRange(300,304))
        val second=wire.sends[9].payload
        assertEquals(460,second.size)
        assertArrayEquals(prepared.copyOfRange(300,756),second.copyOfRange(0,456))
        assertArrayEquals(crc32be(prepared.copyOfRange(300,756)),second.copyOfRange(456,460))

        // The reboot command is the very last frame on the wire.
        assertArrayEquals(byteArrayOf(0),wire.sends[11].payload)
        assertFalse(wire.closed)
    }

    @Test fun stopsBeforeUpdateModeWhenInquiryReportsLowBattery() = runTest {
        val image=synthetic(0x509d,"1.1.9.9",500)
        val records=handshake()+listOf(
            response(0xe1,3,be32Bytes(0)+be16Bytes(0x60)),
            response(0xe2,4,byteArrayOf(1)),
        )
        val wire=ScriptWire(records)
        val client=EarbudsSession(wire,{factor})
        client.authenticate()
        try { client.flash(image); fail("Expected inquiry refusal") }
        catch (_: OtaException) {}
        assertEquals(listOf(0xe1,0xe2),wire.sends.drop(5).map { it.opcode })
    }

    @Test fun rejectsAnImageThatFailsItsOwnChecksum() = runTest {
        val image=synthetic(0x509d,"1.1.9.9",500)
        image[OtaImage.HEADER_SIZE+5]=(image[OtaImage.HEADER_SIZE+5].u() xor 1).toByte()
        val wire=ScriptWire(handshake())
        val client=EarbudsSession(wire,{factor})
        client.authenticate()
        try { client.flash(image); fail("Expected checksum rejection") }
        catch (_: OtaException) {}
        assertEquals(5,wire.sends.size) // no OTA command ever left the phone
    }
}
