# NiceHttp

[![](https://jitpack.io/v/Blatzar/NiceHttp.svg)](https://jitpack.io/#Blatzar/NiceHttp)

A small and simple Android OkHttp wrapper to ease scraping. Mostly for personal use.

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
implementation 'com.github.Blatzar:NiceHttp:+'
```

### Scraping a document

```kotlin
lifecycleScope.launch {
    val requests = Requests()
    val doc = requests.get("https://github.com/Blatzar/NiceHttp").document
    // Using CSS selectors to get the about text
    println(doc.select("p.f4.my-3").text())
}
```

### Parsing json

```kotlin
data class GithubJson(
    val description: String,
    val html_url: String,
    val stargazers_count: Int,
    val private: Boolean
)

// Implement your own requests parser here with your library of choice, this is with jackson :)

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
lifecycleScope.launch {
    val json = requests.get("https://api.github.com/repos/blatzar/nicehttp").parsed<GithubJson>()
    println(json.description)
}
```

### Using cache

Note: For a request to be cached you need to consume the body! You do this by calling .text or .document

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
lifecycleScope.launch {
    // Cache time refers to how long the response could have been cached for 
    cacheClient.get("...", cacheTime = 1, cacheUnit = TimeUnit.HOURS)
}
```
