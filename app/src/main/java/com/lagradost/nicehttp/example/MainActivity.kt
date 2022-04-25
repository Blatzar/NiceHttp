package com.lagradost.nicehttp.example

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lagradost.nicehttp.Requests
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

data class GithubJson(
    val description: String,
    val html_url: String,
    val stargazers_count: Int,
    val private: Boolean
)

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        lifecycleScope.launch {
            val requests = Requests()

            //Example for query selector
            val doc = requests.get("https://github.com/Blatzar/NiceHttp").document
            println(doc.select("p.f4.my-3").text())

            //Example for json Parser
            val json =
                requests.get("https://api.github.com/repos/blatzar/nicehttp").parsed<GithubJson>()
            println(json.description)

            //Example for Async-ed Requests
            (0..5).toList().asyncEach {
                println("Entered Async")
                println("Response ::: " + requests.get("https://github.com/").code)
                println("Exit Async")
            }
        }
    }
}

