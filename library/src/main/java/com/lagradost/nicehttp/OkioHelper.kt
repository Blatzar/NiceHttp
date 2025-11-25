package com.lagradost.nicehttp

import okhttp3.ResponseBody
import okhttp3.internal.closeQuietly
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.decodeHex
import okio.Options
import okio.Source
import okio.use
import java.lang.AssertionError
import java.nio.charset.Charset
import kotlin.text.Charsets.UTF_16BE
import kotlin.text.Charsets.UTF_16LE
import kotlin.text.Charsets.UTF_32BE
import kotlin.text.Charsets.UTF_32LE
import kotlin.text.Charsets.UTF_8

object OkioHelper {
    val UNICODE_BOMS =
        Options.of(
            // UTF-8.
            "efbbbf".decodeHex(),
            // UTF-16BE.
            "feff".decodeHex(),
            // UTF-32LE.
            "fffe0000".decodeHex(),
            // UTF-16LE.
            "fffe".decodeHex(),
            // UTF-32BE.
            "0000feff".decodeHex(),
        )

    fun BufferedSource.readBomAsCharset(default: Charset): Charset =
        when (select(UNICODE_BOMS)) {
            // a mapping from the index of encoding methods in UNICODE_BOMS to its corresponding encoding method
            0 -> UTF_8
            1 -> UTF_16BE
            2 -> UTF_32LE
            3 -> UTF_16LE
            4 -> UTF_32BE
            -1 -> default
            else -> throw AssertionError()
        }

    fun Buffer.writeMax(source: Source, max: Long): Long {
        var totalBytesRead = 0L
        while (totalBytesRead < max) {
            val readCount = source.read(this, 8192L)
            if (readCount == -1L) break
            totalBytesRead += readCount
        }
        return totalBytesRead
    }

    fun readLimited(body: ResponseBody, max: Long): String {
        return body.source().use { source ->
            try {
                val fieldData = source.javaClass.fields.firstOrNull { it.name == "source" }
                val sourceField = fieldData?.get(source) as? Source
                val charset = source.readBomAsCharset(body.contentType()?.charset() ?: UTF_8)
                if (sourceField == null) {
                    println("Exception in NiceHttp: Failed to get .source with reflection")
                    return source.readString(charset)
                }
                val size = source.buffer.writeMax(sourceField, max)
                if (size >= max) {
                    throw IllegalStateException("Called .text on a text file with Content-Length > $max bytes, this throws an exception to prevent OOM. " +
                            "To avoid this use .body/textLarge/documentLarge")
                }
                source.buffer.readString(charset)
            } catch (t: SecurityException) {
                println("Exception in NiceHttp: Failed to get .source with reflection")
                t.printStackTrace()
                val charset = source.readBomAsCharset(body.contentType()?.charset() ?: UTF_8)
                source.readString(charset)
            }
        }.also {
            body.closeQuietly()
        }
    }
}