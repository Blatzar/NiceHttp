package com.lagradost.nicehttp.example

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

data class GithubJson(
    @JsonProperty("description") val description: String,
    @JsonProperty("html_url") val htmlUrl: String,
    @JsonProperty("stargazers_count") val stargazersCount: Int,
    @JsonProperty("private") val private: Boolean
)

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        CoroutineScope(Dispatchers.IO).launch {
            /**
             * Implement your own json parsing to then do request.parsed<T>()
             * */
            val parser = object : ResponseParser {
                val mapper: ObjectMapper = jacksonObjectMapper().configure(
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    false
                )

                override fun <T : Any> parse(text: String, kClass: KClass<T>): T {
                    return mapper.readValue(text, kClass.java)
                }

                override fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T? {
                    return try {
                        mapper.readValue(text, kClass.java)
                    } catch (e: Exception) {
                        null
                    }
                }

                override fun writeValueAsString(obj: Any): String {
                    return mapper.writeValueAsString(obj)
                }
            }

            val requests = Requests(responseParser = parser)

            // Example for query selector
            val doc = requests.get("https://github.com/Blatzar/NiceHttp").document
            println("Selector description: ${doc.select("p.f4.my-3").text()}")

            // Example for json Parser
            val json =
                requests.get("https://api.github.com/repos/blatzar/nicehttp")
                    .parsed<GithubJson>()
            println("JSON description: ${json.description}")

            // Example for Async-ed Requests
            (0..3).toList().asyncMap {
                println("Entered Async")
                println("Response ::: " + requests.get("https://github.com/").code)
                println("Exit Async")
            }
        }
    }
}

