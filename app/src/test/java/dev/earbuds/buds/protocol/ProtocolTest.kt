package dev.earbuds.buds.protocol

import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun matchesCapturedNoiseAndGestureWrites() {
        assertEquals("fedcbac40800040a020401ef",Packet.command(8,10,hex("020401")).encode().hexString())
        assertEquals("fedcbac4f200060e04000b010aef",Packet.command(0xf2,14,configRecord(11,hex("010a"))).encode().hexString())
        assertEquals("fedcbac4f20007330500020104ffef",Packet.command(0xf2,51,configRecord(2,hex("0104ff"))).encode().hexString())
    }
    @Test fun decodesCapturedResponsesAndPushes() {
        val ack=Packet.decode(hex("fedcba04f200020033ef"))
        assertEquals(0,ack.status); assertEquals(51,ack.sequence); assertFalse(ack.isRequest)
        val notification=Packet.decode(hex("fedcba87f400060604000b010aef"))
        assertTrue(notification.isRequest); assertEquals(7,notification.target)
        assertArrayEquals(hex("010a"),configValues(notification.payload).getValue(11))
    }
    @Test fun handlesEveryFragmentBoundaryAndBatchedFrames() {
        val a=hex("fedcba04f30006002703000700ef")
        val b=hex("fedcba04f30006002903000705ef")
        for (boundary in 0..a.size) {
            val decoder=FrameDecoder()
            val packets=decoder.feed(a.copyOfRange(0,boundary))+decoder.feed(a.copyOfRange(boundary,a.size)+b)
            assertEquals(listOf(39,41),packets.map { it.sequence })
        }
        val decoder=FrameDecoder()
        assertTrue(decoder.feed(hex("0102fe")).isEmpty())
        assertEquals(1,decoder.feed(a.drop(1).toByteArray()).size)
        assertEquals(2,decoder.discardedBytes)
    }
    @Test fun rejectsTruncatedOrOversizedInputsAndResynchronizes() {
        assertThrows(ProtocolException::class.java) { Packet.decode(hex("fedcba04f30006002903ef")) }
        assertThrows(ProtocolException::class.java) { FrameDecoder().feed(hex("fedcba04f3ffff")) }
        val good=hex("fedcba04f30006002903000705ef")
        val bad=good.copyOf().also { it[it.lastIndex]=0 }
        assertEquals(1,FrameDecoder().feed(bad+good).size)
        assertThrows(ProtocolException::class.java) { configValues(hex("04000b01")) }
        assertThrows(ProtocolException::class.java) { configValues(hex("ff0001")) }
    }
    @Test fun batteryAndFirmwareMatchConnectedAppTrace() {
        val info=targetInfo(TARGET_PAYLOAD)
        assertEquals("1.3.9.9",info.firmware)
        assertEquals(0x509e,info.pid); assertEquals(0x2717,info.vid)
        assertEquals(Battery(100,false),info.left)
        assertEquals(Battery(100,true),info.right)
        assertEquals(Battery(50,false),info.case)
        val missing=targetInfo(hex("05032717509e0501139913990407ff6432"))
        assertNull(missing.left); assertEquals(100,missing.right?.percent)
    }
    @Test fun customEqMatchesCapturedTenBandReport() {
        val profile=eqProfile(EQ_REPORT)
        assertEquals(listOf(62,125,250,500,1000,2000,4000,8000,12000,16000),profile.bands.map { it.frequency })
        assertEquals(6,profile.maxGain); assertEquals(-6,profile.minGain)
        assertTrue(profile.bands.all { it.gain==0 })
        assertEquals("05010101003e83",customEqValue(listOf(EqBand(62,-3)),true).hexString())
        assertThrows(ProtocolException::class.java) { eqProfile(EQ_REPORT.dropLast(1).toByteArray()) }
    }
    @Test fun gesturesMatchCapturedOriginalReadback() {
        val gestures=gestureValues(hex("040101010303020202030606050b0b"))
        assertEquals(GestureAction.NEXT to GestureAction.NEXT,gestures[Gesture.DOUBLE])
        assertEquals(GestureAction.VOLUME to GestureAction.VOLUME,gestures[Gesture.SWIPE])
        assertThrows(ProtocolException::class.java) { gestureValues(hex("0103")) }
    }
    companion object {
        // Complete payload from the app's parsed trace; its raw byte dump was capped at 50 bytes.
        val TARGET_PAYLOAD=hex("11005265646d69204275647320362050726f05011399139902026405032717509e02040102050003061399020801040764e432020d02020e03020f01")
        val EQ_REPORT=hex("0105060600000a003e00007d0000fa0001f40003e80007d0000fa0001f40002ee0003e8000")
    }
}
