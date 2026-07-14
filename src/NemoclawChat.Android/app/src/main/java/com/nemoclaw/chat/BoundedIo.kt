package com.nemoclaw.chat

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import okio.BufferedSource

internal const val MAX_JSON_RESPONSE_BYTES = 2L * 1024L * 1024L
internal const val MAX_ARCHIVE_JSON_RESPONSE_BYTES = 64L * 1024L * 1024L
internal const val MAX_TTS_AUDIO_BYTES = 100L * 1024L * 1024L
private const val MIN_WAV_BYTES = 44L

internal class PayloadTooLargeException(message: String) : IOException(message)

internal fun InputStream.copyToBounded(output: OutputStream, maxBytes: Long): Long {
    require(maxBytes >= 0L) { "maxBytes deve essere non negativo" }
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (read == 0) continue
        if (total > maxBytes - read.toLong()) {
            throw PayloadTooLargeException("Risposta superiore al limite di $maxBytes byte")
        }
        output.write(buffer, 0, read)
        total += read
    }
    return total
}

internal fun InputStream.readUtf8Bounded(maxBytes: Long = MAX_JSON_RESPONSE_BYTES): String {
    val initialCapacity = minOf(maxBytes, DEFAULT_BUFFER_SIZE.toLong()).toInt()
    return ByteArrayOutputStream(initialCapacity).use { output ->
        copyToBounded(output, maxBytes)
        output.toString(StandardCharsets.UTF_8.name())
    }
}

internal fun BufferedSource.readUtf8LineBounded(maxBytes: Long): String? {
    require(maxBytes > 0L) { "maxBytes deve essere positivo" }
    val newlineIndex = indexOf('\n'.code.toByte(), 0L, maxBytes + 1L)
    if (newlineIndex >= 0L) {
        return if (newlineIndex > 0L && buffer[newlineIndex - 1L] == '\r'.code.toByte()) {
            readUtf8(newlineIndex - 1L).also { skip(2L) }
        } else {
            readUtf8(newlineIndex).also { skip(1L) }
        }
    }
    if (buffer.size > maxBytes) {
        throw PayloadTooLargeException("Riga stream superiore al limite di $maxBytes byte")
    }
    if (buffer.size == 0L) return null
    return readUtf8(buffer.size).removeSuffix("\r")
}

internal fun streamWavToTempFile(
    directory: File,
    prefix: String,
    input: InputStream,
    contentLength: Long
): File {
    if (contentLength >= 0L && (contentLength < MIN_WAV_BYTES || contentLength > MAX_TTS_AUDIO_BYTES)) {
        throw IOException("Dimensione WAV non valida: $contentLength byte")
    }
    if (!directory.exists() && !directory.mkdirs()) {
        throw IOException("Directory audio non accessibile")
    }

    val partial = File.createTempFile(prefix, ".wav.part", directory)
    val completed = File(partial.parentFile, partial.name.removeSuffix(".part"))
    try {
        val written = FileOutputStream(partial).use { output ->
            input.copyToBounded(output, MAX_TTS_AUDIO_BYTES).also { output.fd.sync() }
        }
        if (written < MIN_WAV_BYTES || !hasWavHeader(partial)) {
            throw IOException("Risposta TTS non e' un WAV valido")
        }
        if (!partial.renameTo(completed)) {
            throw IOException("Impossibile finalizzare il WAV temporaneo")
        }
        return completed
    } catch (ex: Exception) {
        partial.delete()
        completed.delete()
        throw ex
    }
}

private fun hasWavHeader(file: File): Boolean {
    if (file.length() < 12L) return false
    val header = ByteArray(12)
    DataInputStream(file.inputStream()).use { it.readFully(header) }
    return header.copyOfRange(0, 4).contentEquals("RIFF".toByteArray(StandardCharsets.US_ASCII)) &&
        header.copyOfRange(8, 12).contentEquals("WAVE".toByteArray(StandardCharsets.US_ASCII))
}
