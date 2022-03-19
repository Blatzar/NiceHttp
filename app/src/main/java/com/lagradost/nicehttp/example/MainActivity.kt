package com.lagradost.nicehttp.example

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.nicehttp.Requests
import kotlin.concurrent.thread

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
        thread {
            val requests = Requests()

            val doc = requests.get("https://github.com/Blatzar/NiceHttp").document
            println(doc.select("p.f4.my-3").text())

            val json =
                requests.get("https://api.github.com/repos/blatzar/nicehttp").parsed<GithubJson>()
            println(json.description)
        }
    }
}