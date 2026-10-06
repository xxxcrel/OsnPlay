package com.shilapi.xcertplay

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

internal interface OsnUpdateHttp {
    fun open(url: String): Response
    interface Response : Closeable {
        val status: Int
        val input: InputStream
    }
}

internal class OsnGitHubHttp : OsnUpdateHttp {
    override fun open(url: String): OsnUpdateHttp.Response {
        var address = url
        repeat(6) {
            if (!OsnUpdateProtocol.transportUrl(address)) throw OsnUpdateException(OsnUpdateError.NETWORK)
            val connection = URL(address).openConnection() as HttpURLConnection
            connection.apply {
                connectTimeout = 10_000; readTimeout = 20_000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "OsnPlay-Updater")
                setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
            }
            val code = try { connection.responseCode } catch (error: Exception) { connection.disconnect(); throw error }
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location == null) throw OsnUpdateException(OsnUpdateError.NETWORK)
                address = URI(address).resolve(location).toString()
            } else {
                val stream = try {
                    if (code == 200) connection.inputStream else ByteArrayInputStream(byteArrayOf())
                } catch (error: Exception) { connection.disconnect(); throw error }
                return object : OsnUpdateHttp.Response {
                    override val status = code
                    override val input = stream
                    override fun close() { try { input.close() } finally { connection.disconnect() } }
                }
            }
        }
        throw OsnUpdateException(OsnUpdateError.NETWORK)
    }
}

internal fun OsnUpdateHttp.json(url: String, limit: Int, missingAllowed: Boolean = false): String? =
    open(url).use { response ->
        if (missingAllowed && response.status == 404) return@use null
        response.requireSuccess()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = response.input.read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw OsnUpdateException(OsnUpdateError.INVALID_METADATA)
            output.write(buffer, 0, count)
        }
        output.toString("UTF-8")
    }

internal fun OsnUpdateHttp.Response.requireSuccess() {
    if (status == 403 || status == 429) throw OsnUpdateException(OsnUpdateError.RATE_LIMIT)
    if (status != 200) throw OsnUpdateException(OsnUpdateError.NETWORK)
}
