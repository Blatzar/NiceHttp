package niceHttp

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
private val DEFAULT_HEADERS = mapOf("user-agent" to DEFAULT_USER_AGENT)
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
    /** Lazy, initialized on use. */
    val text by lazy { okhttpResponse.body?.string() ?: "" }
    val url by lazy { okhttpResponse.request.url.toString() }
    val cookies by lazy { okhttpResponse.cookies }
    val body by lazy { okhttpResponse.body }
    val code = okhttpResponse.code
    val headers = okhttpResponse.headers
    val document: Document by lazy { Jsoup.parse(text) }

    /** Same as using mapper.readValue<T>() */
    inline fun <reified T : Any> parsed(): T {
        return Requests.mapper.readValue(this.text)
    }

    override fun toString(): String {
        return text
    }
}

private fun getData(data: Any?): RequestBody {
    return when (data) {
        null -> FormBody.Builder().build()
        is Map<*, *> -> {
            val builder = FormBody.Builder()
            data.forEach {
                if (it.key is String && it.value is String)
                    builder.add(it.key as String, it.value as String)
            }
            builder.build()
        }
        else ->
            data.toString().toRequestBody("text/plain;charset=UTF-8".toMediaTypeOrNull())
    }
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
    val refererMap = (referer ?: DEFAULT_REFERER)?.let { mapOf("referer" to it) } ?: mapOf()
    val cookieHeaders = (DEFAULT_COOKIES + cookie)
    val cookieMap =
        if (cookieHeaders.isNotEmpty()) mapOf(
            "Cookie" to cookieHeaders.entries.joinToString(" ") {
                "${it.key}=${it.value};"
            }) else mapOf()
    val tempHeaders = (DEFAULT_HEADERS + headers + cookieMap + refererMap)
    return tempHeaders.toHeaders()
}

private fun postRequestCreator(
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
        .post(getData(data))
        .build()
}

private fun getRequestCreator(
    url: String,
    headers: Map<String, String> = emptyMap(),
    referer: String? = null,
    params: Map<String, String> = emptyMap(),
    cookies: Map<String, String> = emptyMap(),
    cacheTime: Int = DEFAULT_TIME,
    cacheUnit: TimeUnit = DEFAULT_TIME_UNIT
): Request {
    return Request.Builder()
        .url(addParamsToUrl(url, params))
        .cacheControl(getCache(cacheTime, cacheUnit))
        .headers(getHeaders(headers, referer, cookies))
        .build()
}

private fun putRequestCreator(
    url: String,
    headers: Map<String, String>,
    referer: String?,
    params: Map<String, String?>,
    cookies: Map<String, String>,
    data: Map<String, String?>,
    cacheTime: Int,
    cacheUnit: TimeUnit
): Request {
    return Request.Builder()
        .url(addParamsToUrl(url, params))
        .cacheControl(getCache(cacheTime, cacheUnit))
        .headers(getHeaders(headers, referer, cookies))
        .put(getData(data))
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
    val naiveTrustManager = object : X509TrustManager {
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

open class Requests(var baseClient: OkHttpClient = OkHttpClient()) {
    companion object {
        val mapper: JsonMapper = JsonMapper.builder().addModule(KotlinModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build()
    }

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
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
            getRequestCreator(url, headers, referer, params, cookies, cacheTime, cacheUnit)
        val response = client.build().newCall(request).execute()
        return NiceResponse(response)
    }

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
        verify: Boolean = true
    ): NiceResponse {
        val client = baseClient
            .newBuilder()
            .followRedirects(allowRedirects)
            .followSslRedirects(allowRedirects)
            .addNetworkInterceptor(CacheInterceptor())
            .callTimeout(timeout, TimeUnit.SECONDS)

        if (!verify) client.ignoreAllSSLErrors()

        val request =
            postRequestCreator(url, headers, referer, params, cookies, data, cacheTime, cacheUnit)
        val response = client.build().newCall(request).execute()
        return NiceResponse(response)
    }

    fun put(
        url: String,
        headers: Map<String, String> = mapOf(),
        referer: String? = null,
        params: Map<String, String> = mapOf(),
        cookies: Map<String, String> = mapOf(),
        data: Map<String, String?> = DEFAULT_DATA,
        allowRedirects: Boolean = true,
        cacheTime: Int = DEFAULT_TIME,
        cacheUnit: TimeUnit = DEFAULT_TIME_UNIT,
        timeout: Long = 0L,
        verify: Boolean = true
    ): NiceResponse {
        val client = baseClient
            .newBuilder()
            .followRedirects(allowRedirects)
            .followSslRedirects(allowRedirects)
            .addNetworkInterceptor(CacheInterceptor())
            .callTimeout(timeout, TimeUnit.SECONDS)

        if (!verify) client.ignoreAllSSLErrors()
        val request =
            putRequestCreator(url, headers, referer, params, cookies, data, cacheTime, cacheUnit)
        val response = client.build().newCall(request).execute()
        return NiceResponse(response)
    }
}
