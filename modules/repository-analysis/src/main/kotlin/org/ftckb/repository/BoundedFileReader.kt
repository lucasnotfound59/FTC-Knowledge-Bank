package org.ftckb.repository

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.LinkOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

internal class BoundedTextFile(val text:String,val sha256:String,val byteCount:Long)

internal fun readBoundedTextNoFollow(path:Path,maxBytes:Long):BoundedTextFile?=try {
    Files.newByteChannel(path,setOf(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)).use { channel ->
        val output=ByteArrayOutputStream()
        val digest=MessageDigest.getInstance("SHA-256")
        val buffer=ByteBuffer.allocate(8_192)
        var total=0L
        while (true) {
            buffer.clear()
            val count=channel.read(buffer)
            if (count<0) break
            if (count==0) continue
            total+=count
            if (total>maxBytes) return@use null
            if ((0 until count).any { index -> buffer.array()[index]==0.toByte() }) return@use null
            digest.update(buffer.array(),0,count)
            output.write(buffer.array(),0,count)
        }
        val bytes=output.toByteArray()
        val text=decodeUtf8(bytes) ?: return@use null
        BoundedTextFile(text,digest.digest().joinToString("") { "%02x".format(it) },total)
    }
} catch (_:Exception) {
    null
}

private fun decodeUtf8(bytes:ByteArray):String?=try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_:CharacterCodingException) {
    null
}
