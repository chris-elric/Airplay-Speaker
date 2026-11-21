package io.github.jqssun.airplay.lyrics

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Result of one HTTP request. [code] is -1 when the connection itself failed. */
internal class LyricsHttpResponse(val code: Int, val body: String?) {
    val ok: Boolean get() = code in 200..299 && body != null
}

/**
 * Small blocking HTTP client shared by every lyric source. It deliberately uses
 * HttpURLConnection so the app does not carry an extra networking dependency.
 */
internal class LyricsHttp(
    private val log: (String) -> Unit,
    private val userAgent: String,
) {

    /**
     * One GET (or form POST when [postBody] is set). Never throws for network
     * failures; they are logged and reported as code -1.
     */
    fun get(
        url: String,
        maxBytes: Int,
        accept: String = "application/json",
        connectTimeoutMs: Int = 3_500,
        readTimeoutMs: Int = 6_500,
        headers: Map<String, String> = emptyMap(),
        postBody: String? = null,
    ): LyricsHttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = if (postBody != null) "POST" else "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("Accept-Language", "th-TH,th;q=0.9,en;q=0.8")
            setRequestProperty("User-Agent", userAgent)
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (postBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            }
        }
        val where = url.substringBefore('?')
        return try {
            if (postBody != null) {
                connection.outputStream.use { it.write(postBody.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                log("Lyrics: HTTP $code from $where")
                LyricsHttpResponse(code, null)
            } else if (connection.contentLengthLong > maxBytes) {
                log("Lyrics: response from $where is larger than $maxBytes bytes")
                LyricsHttpResponse(code, null)
            } else {
                val body = connection.inputStream.use { it.readBoundedUtf8(maxBytes) }
                if (body == null) log("Lyrics: response from $where exceeded $maxBytes bytes")
                LyricsHttpResponse(code, body)
            }
        } catch (error: IOException) {
            log("Lyrics: ${error.javaClass.simpleName} from $where (${error.message.orEmpty()})")
            LyricsHttpResponse(-1, null)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Body of a successful request, or null. [attempts] > 1 retries only on a
     * transient status (429 or 5xx); a 404 or a timeout is never repeated.
     */
    fun requestText(
        url: String,
        maxBytes: Int,
        accept: String = "application/json",
        connectTimeoutMs: Int = 3_500,
        readTimeoutMs: Int = 6_500,
        attempts: Int = 1,
        headers: Map<String, String> = emptyMap(),
        postBody: String? = null,
    ): String? {
        for (attempt in 1..attempts) {
            val response = get(url, maxBytes, accept, connectTimeoutMs, readTimeoutMs, headers, postBody)
            if (response.body != null) return response.body
            val retryable = response.code == 429 || response.code in 500..599
            if (!retryable || attempt == attempts) return null
            Thread.sleep(RETRY_DELAY_MS)
        }
        return null
    }

    private fun InputStream.readBoundedUtf8(maxBytes: Int): String? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1_024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) return null
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val RETRY_DELAY_MS = 700L
    }
}

/** "?a=b&c=d" with blank values skipped and everything URL-encoded. */
internal fun lyricsQuery(vararg pairs: Pair<String, String>): String = pairs
    .filter { it.second.isNotBlank() }
    .joinToString(prefix = "?", separator = "&") { (key, value) ->
        "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
    }
