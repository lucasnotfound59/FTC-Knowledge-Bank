package org.ftckb.model.openai

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

data class HttpExchange(
    val uri:URI,val headers:Map<String,String>,val body:String,val timeoutSeconds:Int
)
data class HttpResult(val status:Int,val body:String)

fun interface HttpTransport {
    fun send(exchange:HttpExchange):HttpResult
}

class JdkHttpTransport(
    private val client:HttpClient=HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
):HttpTransport {
    override fun send(exchange:HttpExchange):HttpResult {
        val request=HttpRequest.newBuilder(exchange.uri)
            .timeout(Duration.ofSeconds(exchange.timeoutSeconds.toLong()))
            .header("Content-Type","application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(exchange.body,StandardCharsets.UTF_8))
            .apply { exchange.headers.forEach { (name,value) -> header(name,value) } }
            .build()
        try {
            val response=client.send(request,HttpResponse.BodyHandlers.ofInputStream())
            val contentLength=response.headers().firstValueAsLong("Content-Length")
            if (contentLength.isPresent && contentLength.asLong>MAX_RESPONSE_BYTES) {
                response.body().close()
                throw IOException("HTTP response exceeds size limit")
            }
            val body=response.body().use { input ->
                val output=ByteArrayOutputStream()
                val buffer=ByteArray(BUFFER_SIZE)
                var total=0
                while (true) {
                    val count=input.read(buffer)
                    if (count<0) break
                    total+=count
                    if (total>MAX_RESPONSE_BYTES) throw IOException("HTTP response exceeds size limit")
                    output.write(buffer,0,count)
                }
                output.toString(StandardCharsets.UTF_8)
            }
            return HttpResult(response.statusCode(),body)
        } catch (error:InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("HTTP request interrupted",error)
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES=4*1024*1024
        const val BUFFER_SIZE=8192
    }
}
