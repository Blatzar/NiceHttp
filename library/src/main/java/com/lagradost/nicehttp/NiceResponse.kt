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
        const val MAX_TEXT_SIZE: Long = 5_000_000 // 5 mb
    }

    private var consumedBody = false

    /** Lazy, initialized on use. Returns empty string on null. Automatically closes the body! Will return textLarge if textLarge has been called before text. */
    val text: String by lazy {
        // Prevent race conditions when calling text and textLarge
        synchronized(this) {
            if (consumedBody.also { consumedBody = true }) {
                // Warning is not needed if the user already chose to use the large body
                // println("Warning: Using text after body is already consumed. Defaulting to textLarge.")
                return@lazy textLarge
            }
            OkioHelper.readLimited(body, MAX_TEXT_SIZE)
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

    /** Same as .text, but without the MAX_TEXT_SIZE limit. Will return text if text is called before textLarge  */
    val textLarge: String by lazy {
        // Prevent race conditions when calling text and textLarge
        synchronized(this) {
            if (consumedBody.also { consumedBody = true }) {
                println("Warning: Using textLarge after body is already consumed. Defaulting to text.")
                text
            } else {
                body.string().also { body.closeQuietly() }
            }
        }
    }

    /** Same as .document, but without the MAX_TEXT_SIZE limit */
    val documentLarge: Document by lazy { Jsoup.parse(textLarge) }

    /** Same as using parsed<T>, but without the MAX_TEXT_SIZE limit  */
    inline fun <reified T : Any> parsedLarge(): T {
        return parser!!.parse(this.textLarge, T::class)
    }

    /** Same as using parsedSafe, but without the MAX_TEXT_SIZE limit */
    inline fun <reified T : Any> parsedSafeLarge(): T? {
        return try {
            return parser!!.parseSafe(this.textLarge, T::class)
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
