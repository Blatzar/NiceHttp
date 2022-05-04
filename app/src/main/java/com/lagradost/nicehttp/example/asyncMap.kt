package com.lagradost.nicehttp.example

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

fun <A, B> List<A>.asyncMap(f: suspend (A) -> B): List<B> = runBlocking {
    map { async { f(it) } }.map { it.await() }
}