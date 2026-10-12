package dev.earbuds.buds.protocol

import org.junit.Assert.*
import org.junit.Test

class AuthTest {
    @Test fun matchesAllOriginalArm64Vectors() {
        val stream=javaClass.getResourceAsStream("/native-auth-vectors.tsv")!!
        var count=0
        stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
                val fields=line.split('\t')
                assertArrayEquals("factor ${fields[0]}",hex(fields[2]),Auth.response(hex(fields[0]),hex(fields[1])))
                count++
            }
        }
        assertEquals(516,count)
    }
    @Test fun matchesBothLiveHandshakeDirections() {
        assertEquals("f8955578093e0cdf56e7c74436605f08",Auth.response(hex("f81be8e78d765a2e63339fc99a66320d")).hexString())
        assertEquals("c05c35726a1303df4fa415aa5c2dbd8f",Auth.response(hex("490b58fa1d22344b3413011ee49d4ff5")).hexString())
    }
    @Test fun rejectsInvalidFactorsAndSeeds() {
        for (length in listOf(0,1,15,17,32)) assertThrows(IllegalArgumentException::class.java) { Auth.response(ByteArray(length)) }
        assertThrows(IllegalArgumentException::class.java) { Auth.response(ByteArray(16),ByteArray(5)) }
    }
}
