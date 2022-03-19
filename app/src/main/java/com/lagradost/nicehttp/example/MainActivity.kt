package com.lagradost.nicehttp.example

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.nicehttp.Requests
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val app = Requests()
        thread {
            val doc = app.get("https://github.com/Blatzar/NiceHttp").document
            println(doc.select("p.f4.my-3").text())
        }
    }
}