package dev.earbuds.buds.protocol

import java.security.MessageDigest
import java.util.zip.CRC32

class OtaException(message: String) : Exception(message)

data class FirmwareImage(
    val region: Region, val version: String, val url: String, val md5: String, val size: Long,
)

/** Official firmware binaries mirrored by the BurgerZ/MiBudsM8 archive. */
object FirmwareCatalog {
    val global = FirmwareImage(
        Region.GLOBAL, "1.3.9.9",
        "https://raw.githubusercontent.com/BurgerZ/MiBudsM8/master/firmwares/global/o76gl/o76gl_1.3.9.9.bin",
        "2c6500c085157d687084a17b939ad9c8", 2_306_336,
    )
    val china = FirmwareImage(
        Region.CHINA, "1.1.9.9",
        "https://raw.githubusercontent.com/BurgerZ/MiBudsM8/master/firmwares/china/o76cn/o76cn_1.1.9.9.bin",
        "de7f7659d563c63c4eba8f40d6c23f6d", 2_557_136,
    )
    fun forRegion(region: Region) = when (region) { Region.GLOBAL -> global; Region.CHINA -> china }
}

fun md5Of(data: ByteArray): String =
    MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }

fun ByteArray.be16(at: Int): Int = this[at].u() * 256 + this[at + 1].u()
fun ByteArray.be32(at: Int): Int =
    (this[at].u() shl 24) or (this[at + 1].u() shl 16) or (this[at + 2].u() shl 8) or this[at + 3].u()
fun be16Bytes(value: Int): ByteArray = byteArrayOf((value shr 8).toByte(), value.toByte())
fun be32Bytes(value: Int): ByteArray = byteArrayOf(
    (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte(),
)
fun crc32be(data: ByteArray): ByteArray {
    val crc = CRC32(); crc.update(data)
    val value = crc.value.toInt()
    return be32Bytes(value)
}

/**
 * BES OTA image: 256-byte plaintext header followed by the encrypted payload.
 * The SHA-256 at header offset 0 covers only the payload, so the header can be
 * patched (PID, version) without re-signing anything.
 */
object OtaImage {
    const val HEADER_SIZE = 256
    private const val VERSION_PLACEHOLDER = "9.9.9.9"

    data class Header(val vid: Int, val pid: Int, val version: String, val bodyLength: Int)

    fun verify(image: ByteArray) {
        if (image.size <= HEADER_SIZE) throw OtaException("Firmware image is truncated")
        val sha = MessageDigest.getInstance("SHA-256")
        sha.update(image, HEADER_SIZE, image.size - HEADER_SIZE)
        if (!MessageDigest.isEqual(sha.digest(), image.copyOfRange(0, 32)))
            throw OtaException("Firmware checksum does not match its header")
    }

    fun header(image: ByteArray): Header {
        if (image.size < HEADER_SIZE) throw OtaException("Firmware image is truncated")
        val bodyLength = (0x56 until 0x5a).fold(0) { value, at -> value or (image[at].u() shl ((at - 0x56) * 8)) }
        return Header(
            image[0x50].u() * 256 + image[0x51].u(),
            image[0x52].u() * 256 + image[0x53].u(),
            bcdVersion(image) ?: throw OtaException("Firmware header has a malformed version"),
            bodyLength,
        )
    }

    /**
     * Retarget a stock image at the connected earbuds: rewrite the header PID so
     * the update-inquiry check accepts it, and force the header version past the
     * installed one when the image would otherwise be treated as a downgrade.
     */
    fun prepare(image: ByteArray, targetPid: Int, installedVersion: String?): ByteArray {
        verify(image)
        val patched = image.copyOf()
        patched[0x52] = (targetPid shr 8).toByte()
        patched[0x53] = targetPid.toByte()
        val imageVersion = bcdDigits(patched.copyOfRange(0x54, 0x56))
        val installed = installedVersion?.let(::versionDigits)
        if (installed != null && (imageVersion == null || compare(imageVersion, installed) < 0))
            writeBcd(patched, versionDigits(VERSION_PLACEHOLDER)!!)
        verify(patched)
        return patched
    }

    private fun bcdVersion(image: ByteArray): String? =
        bcdDigits(image.copyOfRange(0x54, 0x56))?.joinToString(".")

    private fun versionDigits(version: String): List<Int>? {
        val parts = version.split(".")
        if (parts.size != 4) return null
        return parts.map { part -> if (part.length != 1 || part[0] !in '0'..'9') return null else part[0] - '0' }
    }

    private fun bcdDigits(bytes: ByteArray): List<Int>? {
        val digits = bytes.flatMap { byte ->
            val high = byte.u() shr 4; val low = byte.u() and 15
            if (high > 9 || low > 9) return null
            listOf(high, low)
        }
        return digits
    }

    private fun writeBcd(image: ByteArray, digits: List<Int>) {
        image[0x54] = ((digits[0] shl 4) or digits[1]).toByte()
        image[0x55] = ((digits[2] shl 4) or digits[3]).toByte()
    }

    private fun compare(left: List<Int>, right: List<Int>): Int {
        for (index in left.indices) {
            val delta = left[index] - right.getOrElse(index) { 0 }
            if (delta != 0) return delta
        }
        return 0
    }
}
