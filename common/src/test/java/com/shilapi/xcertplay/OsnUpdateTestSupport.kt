package com.shilapi.xcertplay

import java.io.ByteArrayInputStream
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.json.JSONArray
import org.json.JSONObject

internal class OsnUpdateTestExecutor : Executor {
    private val queue = ArrayDeque<Runnable>()
    override fun execute(command: Runnable) { queue += command }
    fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
}
internal class OsnUpdateTestHttp : OsnUpdateHttp {
    val responses = mutableMapOf<String, Pair<Int, ByteArray>>()
    val requests = mutableListOf<String>()
    var reading: ((String) -> Unit)? = null
    override fun open(url: String): OsnUpdateHttp.Response {
        requests += url
        val response = responses[url] ?: (404 to byteArrayOf())
        return object : OsnUpdateHttp.Response {
            override val status = response.first
            override val input = object : ByteArrayInputStream(response.second) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    reading?.invoke(url)
                    return super.read(buffer, offset, length)
                }
            }
            override fun close() { input.close() }
        }
    }
}
internal class OsnUpdateTestFixture(directory: File, main: Executor = Executor { it.run() }) {
    val worker = OsnUpdateTestExecutor()
    val http = OsnUpdateTestHttp()
    val apk = "signed-test-apk".toByteArray()
    val apkUrl = "https://github.com/xxxcrel/OsnPlay/releases/download/v1.5.1/OsnPlay-1.5.1.apk"
    val metadataUrl = "https://github.com/xxxcrel/OsnPlay/releases/download/v1.5.1/update.json"
    var clock = 1000L
    var validationCount = 0
    var archiveError: OsnUpdateError? = null
    val installed = OsnInstalledApp(OsnUpdateProtocol.APPLICATION_ID, 11, 28, setOf("local-signing-certificate"))
    val files = OsnUpdateFiles({ directory }) { _, _ ->
        validationCount++
        archiveError?.let { throw OsnUpdateException(it) }
    }
    val manager = OsnUpdateManager(http, { installed }, files, worker, main, { clock })
    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(apk)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val metadata = JSONObject().put("formatVersion", 1).put("applicationId", installed.packageName)
        .put("versionCode", 12).put("versionName", "1.5.1").put("minSdk", 28)
        .put("apkAsset", "OsnPlay-1.5.1.apk").put("sizeBytes", apk.size).put("sha256", hash)
    fun publish() {
        val bytes = metadata.toString().toByteArray()
        val assets = JSONArray().put(JSONObject().put("name", "update.json").put("size", bytes.size).put("browser_download_url", metadataUrl))
            .put(JSONObject().put("name", "OsnPlay-1.5.1.apk").put("size", apk.size).put("browser_download_url", apkUrl))
        val github = JSONObject().put("draft", false).put("prerelease", false).put("body", "Update fixes").put("assets", assets)
        http.responses[OsnUpdateProtocol.LATEST_API] = 200 to github.toString().toByteArray()
        http.responses[metadataUrl] = 200 to bytes
        http.responses[apkUrl] = 200 to apk
    }
}
