package org.ftckb.model.openai

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

internal const val MAX_RESPONSE_BYTES=4*1024*1024
internal class ResponseTooLargeException:IOException("HTTP response exceeds size limit")

data class HttpExchange(
    val uri:URI,val headers:Map<String,String>,val body:String,val timeoutSeconds:Int
)
data class HttpResult(val status:Int,val body:String,val headers:Map<String,String> =emptyMap())

fun interface HttpTransport {
    fun send(exchange:HttpExchange):HttpResult
}

class JdkHttpTransport(
    private val client:HttpClient=HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
):HttpTransport {
    override fun send(exchange:HttpExchange):HttpResult {
        try {
            val request=HttpRequest.newBuilder(exchange.uri)
                .timeout(Duration.ofSeconds(exchange.timeoutSeconds.toLong()))
                .header("Content-Type","application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(exchange.body,StandardCharsets.UTF_8))
                .apply { exchange.headers.forEach { (name,value) -> header(name,value) } }
                .build()
            val response=client.send(request,HttpResponse.BodyHandlers.ofInputStream())
            val contentLength=response.headers().firstValueAsLong("Content-Length")
            if (contentLength.isPresent && contentLength.asLong>MAX_RESPONSE_BYTES) {
                response.body().close()
                throw ResponseTooLargeException()
            }
            val body=response.body().use { input ->
                val output=ByteArrayOutputStream()
                val buffer=ByteArray(BUFFER_SIZE)
                var total=0
                while (true) {
                    val count=input.read(buffer)
                    if (count<0) break
                    total+=count
                    if (total>MAX_RESPONSE_BYTES) throw ResponseTooLargeException()
                    output.write(buffer,0,count)
                }
                output.toString(StandardCharsets.UTF_8)
            }
            val headers=response.headers().map().mapValues { (_,values) -> values.joinToString(",") }
            return HttpResult(response.statusCode(),body,headers)
        } catch (error:InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("HTTP request interrupted",error)
        } catch (_:IllegalArgumentException) {
            throw IOException("HTTP request construction failed")
        }
    }

    private companion object {
        const val BUFFER_SIZE=8192
    }
}
