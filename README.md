# NiceHttp

[![](https://jitpack.io/v/Blatzar/NiceHttp.svg)](https://jitpack.io/#Blatzar/NiceHttp)

A small and simple OkHttp wrapper to ease scraping. Mostly for personal use.

Featuring:

- Document scraping using jsoup
- Json parsing using jackson
- Easy functions akin to python requests

## Getting started

### Setup

In build.gradle repositories:
```groovy
maven { url 'https://jitpack.io' }
```
In app/build.gradle dependencies:
```groovy
implementation 'com.github.Blatzar:NiceHttp:0.1.3'
```

### Scraping a document

```kotlin
val requests = Requests()
val doc = requests.get("https://github.com/Blatzar/NiceHttp").document
// Using CSS selectors to get the about text
println(doc.select("p.f4.my-3").text())
```

### Parsing json

```kotlin
data class GithubJson(
    val description: String,
    val html_url: String,
    val stargazers_count: Int,
    val private: Boolean
)

val requests = Requests()
val json = requests.get("https://api.github.com/repos/blatzar/nicehttp").parsed<GithubJson>()
println(json.description)
```

### Using cache

(This should work, but I have had issues getting cache hits when testing)

```kotlin
// Just pass in a 
val cache = Cache(
    File(cacheDir, "http_cache"),
    50L * 1024L * 1024L // 50 MiB
)

val okHttpClient = OkHttpClient.Builder()
    .cache(cache)
    .build()

val cacheClient = Requests(okHttpClient)
cacheClient.get("...", cacheTime = 1, cacheUnit = TimeUnit.HOURS)
```
