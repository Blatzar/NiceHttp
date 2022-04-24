package com.lagradost.nicehttp

import android.annotation.SuppressLint
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.*
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

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

private const val DEFAULT_TIME = 0
private val DEFAULT_TIME_UNIT = TimeUnit.MINUTES
private const val DEFAULT_USER_AGENT = "NiceHttp"
private val DEFAULT_DATA: Map<String, String> = mapOf()
private val DEFAULT_COOKIES: Map<String, String> = mapOf()
private val DEFAULT_REFERER: String? = null

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

    /** Only prints the return body */
    override fun toString(): String {
        return text
    }
}

val mustHaveBody = listOf("POST", "PUT")
val cantHaveBody = listOf("GET", "HEAD")
private fun getData(data: Any?, method: String): RequestBody? {
    // Can't have a body (errors). Not possible with the normal commands, but is with custom()
    if (cantHaveBody.contains(method.uppercase())) return null
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
                val builder = MultipartBody.Builder()
                formattedData.forEach {
                    builder.addFormDataPart(it.first, it.second)
                }
                builder.build()
            }
        }
        else ->
            data.toString().toRequestBody("text/plain;charset=UTF-8".toMediaTypeOrNull())
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
private fun getHeaders(
    headers: Map<String, String>,
    referer: String?,
    cookie: Map<String, String>
): Headers {
    val refererMap = (referer ?: DEFAULT_REFERER)?.let { mapOf("referer" to it) } ?: mapOf()
    val cookieHeaders = (DEFAULT_COOKIES + cookie)
    val cookieMap =
        if (cookieHeaders.isNotEmpty()) mapOf(
            "Cookie" to cookieHeaders.entries.joinToString(" ") {
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
    data: Any? = DEFAULT_DATA,
    cacheTime: Int = DEFAULT_TIME,
    cacheUnit: TimeUnit = DEFAULT_TIME_UNIT
): Request {
    return Request.Builder()
        .url(addParamsToUrl(url, params))
        .cacheControl(getCache(cacheTime, cacheUnit))
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

/**
 * @param baseClient base okhttp client used for all requests. Use this to get cache.
 * @param defaultHeaders base headers present in all requests, will get overwritten by custom headers.
 * Includes the NiceHttp user agent by default.
 * */
open class Requests(
    var baseClient: OkHttpClient = OkHttpClient(),
    var defaultHeaders: Map<String, String> = mapOf("user-agent" to DEFAULT_USER_AGENT),
) {
    companion object {
        val mapper: JsonMapper = JsonMapper.builder().addModule(KotlinModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build()
    }

    // Regretful copy paste function args, but I am unsure how to do it otherwise
    /**
     * @param cacheUnit defaults to minutes
     * @param verify false to ignore SSL errors
     * @param data Map<String?, String?> or String, all keys or values which is null
     * will be skipped. All other objects will be interpreted as strings using .toString()
     * @param timeout timeout in seconds
     * */
    fun custom(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        data: Any? = DEFAULT_DATA,
        allowRedirects: Boolean = true,
        cacheTime: Int = DEFAULT_TIME,
        cacheUnit: TimeUnit = DEFAULT_TIME_UNIT,
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
                method, url, defaultHeaders + headers, referer, params,
                cookies, data, cacheTime, cacheUnit
            )
        val response = client.build().newCall(request).execute()
        return NiceResponse(response)
    }

    /**
     * @param cacheUnit defaults to minutes
     * @param verify false to ignore SSL errors
     * @param timeout timeout in seconds
     * */
    fun get(
        url: String,
        headers: Map<String, String> = mapOf(),
        referer: String? = null,
        params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
        allowRedirects: Boolean = true,
        cacheTime: Int = DEFAULT_TIME,
        cacheUnit: TimeUnit = DEFAULT_TIME_UNIT,
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
     * will be skipped. All other objects will be interpreted as strings using .toString()
     * @param timeout timeout in seconds
     * */
    fun post(
        url: String,
        headers: Map<String, String> = mapOf(),
        referer: String? = null,
        params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
        data: Any? = DEFAULT_DATA,
        allowRedirects: Boolean = true,
        cacheTime: Int = DEFAULT_TIME,
        cacheUnit: TimeUnit = DEFAULT_TIME_UNIT,
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
     * will be skipped. All other objects will be interpreted as strings using .toString()
     * @param timeout timeout in seconds
     * */
    fun put(
        url: String,
        headers: Map<String, String> = mapOf(),
        referer: String? = null,
        params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
        data: Any? = DEFAULT_DATA,
        allowRedirects: Boolean = true,
        cacheTime: Int = DEFAULT_TIME,
        cacheUnit: TimeUnit = DEFAULT_TIME_UNIT,
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
     * will be skipped. All other objects will be interpreted as strings using .toString()
     * @param timeout timeout in seconds
     * */
    fun delete(
        url: String,
        headers: Map<String, String> = mapOf(),
        referer: String? = null,
        params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
        data: Any? = DEFAULT_DATA,
        allowRedirects: Boolean = true,
        cacheTime: Int = DEFAULT_TIME,
        cacheUnit: TimeUnit = DEFAULT_TIME_UNIT,
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
    fun head(
        url: String,
        headers: Map<String, String> = mapOf(),
        referer: String? = null,
        params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
        allowRedirects: Boolean = true,
        cacheTime: Int = DEFAULT_TIME,
        cacheUnit: TimeUnit = DEFAULT_TIME_UNIT,
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
     * will be skipped. All other objects will be interpreted as strings using .toString()
     * @param timeout timeout in seconds
     * */
    fun patch(
        url: String,
        headers: Map<String, String> = mapOf(),
        referer: String? = null,
        params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
        data: Any? = DEFAULT_DATA,
        allowRedirects: Boolean = true,
        cacheTime: Int = DEFAULT_TIME,
        cacheUnit: TimeUnit = DEFAULT_TIME_UNIT,
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
     * will be skipped. All other objects will be interpreted as strings using .toString()
     * @param timeout timeout in seconds
     * */
    fun options(
        url: String,
        headers: Map<String, String> = mapOf(),
        referer: String? = null,
        params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
        data: Any? = DEFAULT_DATA,
        allowRedirects: Boolean = true,
        cacheTime: Int = DEFAULT_TIME,
        cacheUnit: TimeUnit = DEFAULT_TIME_UNIT,
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
