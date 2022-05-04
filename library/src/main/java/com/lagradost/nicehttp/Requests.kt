package com.lagradost.nicehttp

import android.annotation.SuppressLint
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletionHandler
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resumeWithException

class Session(
    client: OkHttpClient
) : Requests() {
    init {
        this.baseClient = client
            .newBuilder()
            .cookieJar(CustomCookieJar())
            .build()
    }

    inner class CustomCookieJar : CookieJar {
        private var cookies = mapOf<String, Cookie>()

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return this.cookies.values.toList()
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            this.cookies += cookies.map { it.name to it }
        }
    }
}


fun Headers.getCookies(cookieKey: String): Map<String, String> {
    val cookieList =
        this.filter { it.first.equals(cookieKey, ignoreCase = true) }
            .getOrNull(0)?.second?.split(";")
    return cookieList?.associate {
        val split = it.split("=")
        (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
    }?.filter { it.key.isNotBlank() && it.value.isNotBlank() } ?: mapOf()
}

private val Response.cookies: Map<String, String>
    get() = this.headers.getCookies("set-cookie")

private val Request.cookies: Map<String, String>
    get() = this.headers.getCookies("Cookie")

class NiceResponse(
    val okhttpResponse: Response
) {
    /** Lazy, initialized on use. Returns empty string on null. */
    val text by lazy { okhttpResponse.body?.string() ?: "" }
    val url by lazy { okhttpResponse.request.url.toString() }
    val cookies by lazy { okhttpResponse.cookies }
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
        return Requests.mapper.readValue(this.text)
    }

    /** Same as using try { mapper.readValue<T>() } */
    inline fun <reified T : Any> parsedSafe(): T? {
        return try {
            Requests.mapper.readValue(this.text)
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

val mustHaveBody = listOf("POST", "PUT")
val cantHaveBody = listOf("GET", "HEAD")
fun getData(data: Any?, method: String): RequestBody? {
    // Can't have a body (errors). Not possible with the normal commands, but is with custom()
    if (cantHaveBody.contains(method.uppercase())) return null
    if (data is RequestBody) return data
    val body = when (data) {
        null -> null
        is Map<*, *> -> {
            val formattedData = data.mapNotNull {
                val key = it.key as? String ?: return@mapNotNull null
                val value = it.value as? String ?: return@mapNotNull null
                key to value
            }
            // Multipart body must have at least one part.
            if (formattedData.isEmpty())
                null
            else {
                val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
                formattedData.forEach {
                    builder.addFormDataPart(it.first, it.second)
                }
                builder.build()
            }
        }
        is String -> data.toRequestBody("text/plain;charset=UTF-8".toMediaTypeOrNull())
        else -> Requests.mapper.writeValueAsString(data)
            .toRequestBody("application/json;charset=utf-8".toMediaTypeOrNull())
    }
    // Post must have a body!
    return body ?: if (mustHaveBody.contains(method.uppercase()))
        FormBody.Builder().build() else null
}

// https://github.com, id=test -> https://github.com?id=test
private fun appendUri(uri: String, appendQuery: String): String {
    val oldUri = URI(uri)
    return URI(
        oldUri.scheme,
        oldUri.authority,
        oldUri.path,
        if (oldUri.query == null) appendQuery else oldUri.query + "&" + appendQuery,
        oldUri.fragment
    ).toString()
}

// Can probably be done recursively
private fun addParamsToUrl(url: String, params: Map<String, String?>): String {
    var appendedUrl = url
    params.forEach {
        it.value?.let { value ->
            appendedUrl = appendUri(appendedUrl, "${it.key}=${value}")
        }
    }
    return appendedUrl
}

private fun getCache(cacheTime: Int, cacheUnit: TimeUnit): CacheControl {
    return CacheControl.Builder().maxStale(cacheTime, cacheUnit).build()
}

/**
 * Referer > Set headers > Set getCookies > Default headers > Default Cookies
 */
fun getHeaders(
    headers: Map<String, String>,
    referer: String?,
    cookie: Map<String, String>
): Headers {
    val refererMap = referer?.let { mapOf("referer" to it) } ?: mapOf()
    val cookieMap =
        if (cookie.isNotEmpty()) mapOf(
            "Cookie" to cookie.entries.joinToString(" ") {
                "${it.key}=${it.value};"
            }) else mapOf()
    val tempHeaders = (headers + cookieMap + refererMap)
    return tempHeaders.toHeaders()
}

fun requestCreator(
    method: String,
    url: String,
    headers: Map<String, String> = emptyMap(),
    referer: String? = null,
    params: Map<String, String> = emptyMap(),
    cookies: Map<String, String> = emptyMap(),
    data: Any? = null,
    cacheTime: Int? = null,
    cacheUnit: TimeUnit? = null
): Request {
    return Request.Builder()
        .url(addParamsToUrl(url, params))
        .apply {
            if (cacheTime != null && cacheUnit != null)
                this.cacheControl(getCache(cacheTime, cacheUnit))
        }
        .headers(getHeaders(headers, referer, cookies))
        .method(method, getData(data, method))
        .build()
}

class CacheInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        return chain.proceed(chain.request()).newBuilder()
            .removeHeader("Cache-Control") // Remove site cache
            .removeHeader("Pragma") // Remove site cache
            .addHeader("Cache-Control", CacheControl.FORCE_CACHE.toString())
            .build()
    }
}

// https://stackoverflow.com/a/59322754
fun OkHttpClient.Builder.ignoreAllSSLErrors(): OkHttpClient.Builder {
    val naiveTrustManager = @SuppressLint("CustomX509TrustManager")
    object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) = Unit
    }

    val insecureSocketFactory = SSLContext.getInstance("TLSv1.2").apply {
        val trustAllCerts = arrayOf<TrustManager>(naiveTrustManager)
        init(null, trustAllCerts, SecureRandom())
    }.socketFactory

    sslSocketFactory(insecureSocketFactory, naiveTrustManager)
    hostnameVerifier { _, _ -> true }
    return this
}

//Provides async-able Calls
class ContinuationCallback(
    private val call: Call,
    private val continuation: CancellableContinuation<Response>
) : Callback, CompletionHandler {

    override fun onResponse(call: Call, response: Response) {
        continuation.resume(response, null)
    }

    override fun onFailure(call: Call, e: IOException) {
        if (!call.isCanceled()) {
            continuation.resumeWithException(e)
        }
    }

    override fun invoke(cause: Throwable?) {
        try {
            call.cancel()
        } catch (_: Throwable) {
        }
    }
}

/**
 * @param baseClient base okhttp client used for all requests. Use this to get cache.
 * @param defaultHeaders base headers present in all requests, will get overwritten by custom headers.
 * Includes the NiceHttp user agent by default.
 * */
open class Requests(
    var baseClient: OkHttpClient = OkHttpClient(),
    var defaultTime: Int = 0,
    var defaultTimeUnit: TimeUnit = TimeUnit.MINUTES,
    var defaultData: Map<String, String> = mapOf(),
    var defaultCookies: Map<String, String> = mapOf(),
    var defaultReferer: String? = null,
    var defaultHeaders: Map<String, String> = mapOf("user-agent" to "NiceHttp"),
) {
    companion object {
        var mapper: ObjectMapper = jacksonObjectMapper().configure(
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            false
        )

        suspend inline fun Call.await(): Response {
            return suspendCancellableCoroutine { continuation ->
                val callback = ContinuationCallback(this, continuation)
                enqueue(callback)
                continuation.invokeOnCancellation(callback)
            }
        }
    }

    // Regretful copy paste function args, but I am unsure how to do it otherwise
    /**
     * @param cacheUnit defaults to minutes
     * @param verify false to ignore SSL errors
     * @param data Map<String?, String?> or String, all keys or values which is null
     * will be skipped. Strings will be interpreted as strings,
     * objects will be sterilized with jackson mapper and sent as json,
     * RequestBody will be used as is.
     * @param timeout timeout in seconds
     * */
    suspend fun custom(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        data: Any? = defaultData,
        allowRedirects: Boolean = true,
        cacheTime: Int = defaultTime,
        cacheUnit: TimeUnit = defaultTimeUnit,
        timeout: Long = 0L,
        interceptor: Interceptor? = null,
        verify: Boolean = true
    ): NiceResponse {
        val client = baseClient
            .newBuilder()
            .followRedirects(allowRedirects)
            .followSslRedirects(allowRedirects)
            .addNetworkInterceptor(CacheInterceptor())
            .callTimeout(timeout, TimeUnit.SECONDS)
        if (timeout > 0)
            client
                .connectTimeout(timeout, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.SECONDS)
        if (!verify) client.ignoreAllSSLErrors()

        if (interceptor != null) client.addInterceptor(interceptor)
        val request =
            requestCreator(
                method, url, defaultHeaders + headers, referer ?: defaultReferer, params,
                defaultCookies + cookies, data, cacheTime, cacheUnit
            )
        val response = client.build().newCall(request).await()
        return NiceResponse(response)
    }

    /**
     * @param cacheUnit defaults to minutes
     * @param verify false to ignore SSL errors
     * @param timeout timeout in seconds
     * */
    suspend fun get(
        url: String,
        headers: Map<String, String> = mapOf(),
        referer: String? = null,
        params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
        allowRedirects: Boolean = true,
        cacheTime: Int = defaultTime,
        cacheUnit: TimeUnit = defaultTimeUnit,
        timeout: Long = 0L,
        interceptor: Interceptor? = null,
        verify: Boolean = true
    ): NiceResponse {
        return custom(
            "GET", url, headers, referer, params, cookies, null,
            allowRedirects, cacheTime, cacheUnit, timeout, interceptor, verify
        )
    }

    /**
     * @param cacheUnit defaults to minutes
     * @param verify false to ignore SSL errors
     * @param data Map<String?, String?> or String, all keys or values which is null
     * will be skipped. Strings will be interpreted as strings,
     * objects will be sterilized with jackson mapper and sent as json,
     * RequestBody will be used as is.
     * @param timeout timeout in seconds
     * */
    suspend fun post(
        url: String,
        headers: Map<String, String> = mapOf(),
        referer: String? = null,
        params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
        data: Any? = defaultData,
        allowRedirects: Boolean = true,
        cacheTime: Int = defaultTime,
        cacheUnit: TimeUnit = defaultTimeUnit,
        timeout: Long = 0L,
        interceptor: Interceptor? = null,
        verify: Boolean = true
    ): NiceResponse {
        return custom(
            "POST", url, headers, referer, params, cookies, data,
            allowRedirects, cacheTime, cacheUnit, timeout, interceptor, verify
        )
    }

    /**
     * @param cacheUnit defaults to minutes
     * @param verify false to ignore SSL errors
     * @param data Map<String?, String?> or String, all keys or values which is null
     * will be skipped. Strings will be interpreted as strings,
     * objects will be sterilized with jackson mapper and sent as json,
     * RequestBody will be used as is.
     * @param timeout timeout in seconds
     * */
    suspend fun put(
        url: String,
        headers: Map<String, String> = mapOf(),
        referer: String? = null,
        params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
        data: Any? = defaultData,
        allowRedirects: Boolean = true,
        cacheTime: Int = defaultTime,
        cacheUnit: TimeUnit = defaultTimeUnit,
        timeout: Long = 0L,
        interceptor: Interceptor? = null,
        verify: Boolean = true
    ): NiceResponse {
        return custom(
            "PUT", url, headers, referer, params, cookies, data,
            allowRedirects, cacheTime, cacheUnit, timeout, interceptor, verify
        )
    }

    /**
     * @param cacheUnit defaults to minutes
     * @param verify false to ignore SSL errors
     * @param data Map<String?, String?> or String, all keys or values which is null
     * will be skipped. Strings will be interpreted as strings,
     * objects will be sterilized with jackson mapper and sent as json,
     * RequestBody will be used as is.
     * @param timeout timeout in seconds
     * */
    suspend fun delete(
        url: String,
        headers: Map<String, String> = mapOf(),
        referer: String? = null,
        params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
        data: Any? = defaultData,
        allowRedirects: Boolean = true,
        cacheTime: Int = defaultTime,
        cacheUnit: TimeUnit = defaultTimeUnit,
        timeout: Long = 0L,
        interceptor: Interceptor? = null,
        verify: Boolean = true
    ): NiceResponse {
        return custom(
            "DELETE", url, headers, referer, params, cookies, data,
            allowRedirects, cacheTime, cacheUnit, timeout, interceptor, verify
        )
    }

    /**
     * @param cacheUnit defaults to minutes
     * @param verify false to ignore SSL errors
     * @param timeout timeout in seconds
     * */
    suspend fun head(
        url: String,
        headers: Map<String, String> = mapOf(),
        referer: String? = null,
        params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
        allowRedirects: Boolean = true,
        cacheTime: Int = defaultTime,
        cacheUnit: TimeUnit = defaultTimeUnit,
        timeout: Long = 0L,
        interceptor: Interceptor? = null,
        verify: Boolean = true
    ): NiceResponse {
        return custom(
            "HEAD", url, headers, referer, params, cookies, null,
            allowRedirects, cacheTime, cacheUnit, timeout, interceptor, verify
        )
    }

    /**
     * @param cacheUnit defaults to minutes
     * @param verify false to ignore SSL errors
     * @param data Map<String?, String?> or String, all keys or values which is null
     * will be skipped. Strings will be interpreted as strings,
     * objects will be sterilized with jackson mapper and sent as json,
     * RequestBody will be used as is.
     * @param timeout timeout in seconds
     * */
    suspend fun patch(
        url: String,
        headers: Map<String, String> = mapOf(),
        referer: String? = null,
        params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
        data: Any? = defaultData,
        allowRedirects: Boolean = true,
        cacheTime: Int = defaultTime,
        cacheUnit: TimeUnit = defaultTimeUnit,
        timeout: Long = 0L,
        interceptor: Interceptor? = null,
        verify: Boolean = true
    ): NiceResponse {
        return custom(
            "PATCH", url, headers, referer, params, cookies, data,
            allowRedirects, cacheTime, cacheUnit, timeout, interceptor, verify
        )
    }

    /**
     * @param cacheUnit defaults to minutes
     * @param verify false to ignore SSL errors
     * @param data Map<String?, String?> or String, all keys or values which is null
     * will be skipped. Strings will be interpreted as strings,
     * objects will be sterilized with jackson mapper and sent as json,
     * RequestBody will be used as is.
     * @param timeout timeout in seconds
     * */
    suspend fun options(
        url: String,
        headers: Map<String, String> = mapOf(),
        referer: String? = null,
        params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
        data: Any? = defaultData,
        allowRedirects: Boolean = true,
        cacheTime: Int = defaultTime,
        cacheUnit: TimeUnit = defaultTimeUnit,
        timeout: Long = 0L,
        interceptor: Interceptor? = null,
        verify: Boolean = true
    ): NiceResponse {
        return custom(
            "OPTIONS", url, headers, referer, params, cookies, data,
            allowRedirects, cacheTime, cacheUnit, timeout, interceptor, verify
        )
    }

}
