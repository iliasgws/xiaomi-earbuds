package dev.earbuds.buds.connection

import dev.earbuds.buds.protocol.FirmwareImage
import dev.earbuds.buds.protocol.OtaException
import dev.earbuds.buds.protocol.md5Of
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Downloads official firmware binaries once, verifies them, and caches them on disk. */
object FirmwareFetcher {
    fun fetch(cacheDir: File, image: FirmwareImage, onProgress: (Int) -> Unit): ByteArray {
        val directory = File(cacheDir, "firmware").apply { mkdirs() }
        val cached = File(directory, "${image.region}-${image.version}.bin")
        if (cached.length() == image.size && md5Of(cached.readBytes()) == image.md5) {
            onProgress(100); return cached.readBytes()
        }
        val temporary = File(directory, "download.bin")
        download(image, temporary) { onProgress(it.coerceIn(0, 99)) }
        if (md5Of(temporary.readBytes()) != image.md5) {
            temporary.delete(); throw OtaException("Firmware checksum mismatch")
        }
        if (!temporary.renameTo(cached)) {
            cached.delete()
            if (!temporary.renameTo(cached)) {
                temporary.delete(); throw OtaException("Cannot cache the firmware image")
            }
        }
        onProgress(100)
        return cached.readBytes()
    }

    private fun download(image: FirmwareImage, target: File, onProgress: (Int) -> Unit) {
        val connection = URL(image.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode != 200)
                throw OtaException("Firmware download failed (HTTP ${connection.responseCode})")
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L; var last = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read); total += read
                        if (image.size > 0) {
                            val percent = (total * 100 / image.size).toInt()
                            if (percent != last) { last = percent; onProgress(percent) }
                        }
                    }
                    if (total != image.size) throw OtaException("Firmware download is incomplete")
                }
            }
        } finally { connection.disconnect() }
    }
}
