package com.lagradost.nicehttp

import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.closeQuietly
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.StringWriter

val Response.cookies: Map<String, String>
    get() = this.headers.getCookies("set-cookie")

val Request.cookies: Map<String, String>
    get() = this.headers.getCookies("Cookie")

class NiceResponse(
    val okhttpResponse: Response,
    val parser: ResponseParser?
) {
    companion object {
        const val MAX_TEXT_SIZE: Long = 1_000_000
    }

    /** Lazy, initialized on use. Returns empty string on null. Automatically closes the body! */
    val text by lazy {
        val stream = body.charStream()

        try {
            val textSize = size
            if (textSize != null && textSize > MAX_TEXT_SIZE) {
                throw IllegalStateException("Called .text on a text file with Content-Length > $MAX_TEXT_SIZE bytes, this throws an exception to prevent OOM. To avoid this use .body")
            }

            val out = StringWriter()

            var charsCopied: Long = 0
            val buffer = CharArray(DEFAULT_BUFFER_SIZE)
            var chars = stream.read(buffer)

            while (chars >= 0 && charsCopied < MAX_TEXT_SIZE) {
                out.write(buffer, 0, chars)
                charsCopied += chars
                chars = stream.read(buffer)
            }

            if (charsCopied >= MAX_TEXT_SIZE) {
                throw IllegalStateException("Called .text on a text file above $MAX_TEXT_SIZE bytes, this throws an exception to prevent OOM. To avoid this use .body")
            }

            out.toString()
        } finally {
            stream.closeQuietly()
            body.closeQuietly()
        }
    }

    val url by lazy { okhttpResponse.request.url.toString() }
    val cookies by lazy { okhttpResponse.cookies }

    /** Remember to close the body! */
    val body by lazy { okhttpResponse.body }

    /** Return code */
    val code = okhttpResponse.code
    val headers = okhttpResponse.headers

    /** Size, as reported by Content-Length */
    val size by lazy {
        (okhttpResponse.headers["Content-Length"]
            ?: okhttpResponse.headers["content-length"])?.toLongOrNull()
    }

    val isSuccessful = okhttpResponse.isSuccessful

    /** As parsed by Jsoup.parse(text) */
    val document: Document by lazy { Jsoup.parse(text) }

    /** Same as using mapper.readValue<T>() */
    inline fun <reified T : Any> parsed(): T {
        return parser!!.parse(this.text, T::class)
    }

    /** Same as using try { mapper.readValue<T>() } */
    inline fun <reified T : Any> parsedSafe(): T? {
        return try {
            return parser!!.parseSafe(this.text, T::class)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Only prints the return body */
    override fun toString(): String {
        return text
    }
}
