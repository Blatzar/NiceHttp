package com.lagradost.nicehttp

import android.annotation.SuppressLint
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletionHandler
import okhttp3.*
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resumeWithException

private val mustHaveBody = listOf("POST", "PUT")
private val cantHaveBody = listOf("GET", "HEAD")

/**
 * Prioritizes:
 * 0. requestBody
 * 1. data (Map)
 * 2. json (Any or String)
 * 3. files (List which can include files or normal data, but encoded differently)
 *
 * @return null if method cannot have a body or if the parameters did not give a body.
 * */
fun getData(
    method: String,
    data: Map<String, String>?,
    files: List<NiceFile>?,
    json: Any?,
    requestBody: RequestBody?,
    responseParser: ResponseParser?
): RequestBody? {
    // Can't have a body (errors). Not possible with the normal commands, but is with custom()
    if (cantHaveBody.contains(method.uppercase())) return null
    if (requestBody != null) return requestBody

    val body = if (data != null) {

        val builder = FormBody.Builder()
        data.forEach {
            builder.add(it.key, it.value)
        }
        builder.build()

    } else if (json != null) {

        val jsonString = when (json) {
            is JSONObject -> json.toString()
            is JSONArray -> json.toString()
            is String -> json
            is JsonAsString -> json.string
            responseParser != null -> responseParser!!.writeValueAsString(json)
            else -> json.toString()
        }

        val type = if (json is String) RequestBodyTypes.TEXT else RequestBodyTypes.JSON

        jsonString.toRequestBody(type.toMediaTypeOrNull())

    } else if (!files.isNullOrEmpty()) {

        val builder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
        files.forEach {
            if (it.file != null)
                builder.addFormDataPart(
                    it.name,
                    it.fileName,
                    it.file.asRequestBody(it.fileType?.toMediaTypeOrNull())
                )
            else
                builder.addFormDataPart(it.name, it.fileName)
        }
        builder.build()

    } else {
        null
    }

    // Post must have a body!
    return body ?: if (mustHaveBody.contains(method.uppercase()))
        FormBody.Builder().build() else null
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


fun Headers.getCookies(cookieKey: String): Map<String, String> {
    val cookieList =
        this.filter { it.first.equals(cookieKey, ignoreCase = true) }
            .getOrNull(0)?.second?.split(";")
    return cookieList?.associate {
        val split = it.split("=")
        (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
    }?.filter { it.key.isNotBlank() && it.value.isNotBlank() } ?: mapOf()
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
        } else
            // Must be able to throw errors, for example timeouts
            throw e
    }

    override fun invoke(cause: Throwable?) {
        try {
            call.cancel()
        } catch (_: Throwable) {
        }
    }
}


// https://github.com, id=test -> https://github.com?id=test
internal fun appendUri(uri: String, appendQuery: String): String {
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
internal fun addParamsToUrl(url: String, params: Map<String, String?>): String {
    var appendedUrl = url
    params.forEach {
        it.value?.let { value ->
            appendedUrl = appendUri(appendedUrl, "${it.key}=${value}")
        }
    }
    return appendedUrl
}

internal fun getCache(cacheTime: Int, cacheUnit: TimeUnit): CacheControl {
    return CacheControl.Builder().maxStale(cacheTime, cacheUnit).build()
}
