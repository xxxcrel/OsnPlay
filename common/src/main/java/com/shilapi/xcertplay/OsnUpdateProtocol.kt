package com.shilapi.xcertplay

import java.io.IOException
import java.net.URI
import org.json.JSONObject

internal enum class OsnUpdateError {
    NETWORK, RATE_LIMIT, INVALID_RELEASE, INVALID_METADATA, PACKAGE, VERSION, SIGNATURE,
    HASH, SIZE, ANDROID_VERSION, STORAGE, CACHE_MISSING,
}
internal class OsnUpdateException(val reason: OsnUpdateError) : IOException(reason.name)

internal data class OsnUpdateAsset(val name: String, val url: String, val size: Long)
internal data class OsnGitHubRelease(val notes: String, val assets: List<OsnUpdateAsset>)
internal data class OsnUpdateRelease(val versionCode: Long, val versionName: String, val minSdk: Int,
    val sha256: String, val apk: OsnUpdateAsset, val notes: String)
internal data class OsnInstalledApp(val packageName: String, val versionCode: Long, val sdk: Int,
    val certificates: Set<String>)

internal object OsnUpdateProtocol {
    const val REPOSITORY = "xxxcrel/OsnPlay"
    const val RELEASES_URL = "https://github.com/$REPOSITORY/releases"
    const val LATEST_API = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val APPLICATION_ID = "com.sinyee.babybus.story"
    const val MAX_APK_BYTES = 200L * 1024 * 1024
    const val MAX_JSON_BYTES = 2 * 1024 * 1024
    const val MAX_METADATA_BYTES = 64 * 1024

    fun release(json: String): OsnGitHubRelease? = parse(OsnUpdateError.INVALID_RELEASE) {
        val value = JSONObject(json)
        if (value.optBoolean("draft") || value.optBoolean("prerelease")) return@parse null
        val array = value.getJSONArray("assets")
        require(array.length() <= 100)
        val assets = (0 until array.length()).map { index ->
            val asset = array.getJSONObject(index)
            OsnUpdateAsset(asset.getString("name"), asset.getString("browser_download_url"), integer(asset, "size"))
        }
        require(assets.map { it.name }.distinct().size == assets.size)
        OsnGitHubRelease(if (value.isNull("body")) "" else value.optString("body").take(12_000), assets)
    }

    fun metadata(json: String, release: OsnGitHubRelease, installed: OsnInstalledApp): OsnUpdateRelease =
        parse(OsnUpdateError.INVALID_METADATA) {
            val value = JSONObject(json)
            if (value.has("formatVersion")) require(integer(value, "formatVersion") == 1L)
            if (value.getString("applicationId") != installed.packageName || installed.packageName != APPLICATION_ID)
                throw OsnUpdateException(OsnUpdateError.PACKAGE)
            val code = integer(value, "versionCode")
            val name = value.getString("versionName")
            val minSdk = integer(value, "minSdk")
            val hash = value.getString("sha256").lowercase(java.util.Locale.ROOT)
            val assetName = value.getString("apkAsset")
            require(code in 1..Int.MAX_VALUE.toLong() && minSdk in 1..Int.MAX_VALUE.toLong())
            require(name.isNotBlank() && name.length <= 80 && Regex("[a-f0-9]{64}").matches(hash))
            require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,150}\\.apk").matches(assetName))
            val apk = release.assets.single { it.name == assetName }
            require(repositoryAsset(apk.url) && apk.size in 1..MAX_APK_BYTES)
            if (value.has("sizeBytes")) require(integer(value, "sizeBytes") == apk.size)
            OsnUpdateRelease(code, name, minSdk.toInt(), hash, apk, release.notes)
        }

    /** Initial asset URLs must belong to this fork; GitHub's subsequent HTTPS CDN redirects are allowed. */
    fun repositoryAsset(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host.equals("github.com", true) && uri.userInfo == null &&
            uri.port in listOf(-1, 443) && uri.fragment == null &&
            uri.normalize().path.startsWith("/$REPOSITORY/releases/download/")
    }.getOrDefault(false)

    fun transportUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase(java.util.Locale.ROOT).orEmpty()
        uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            (host == "github.com" || host == "api.github.com" || host.endsWith(".githubusercontent.com"))
    }.getOrDefault(false)

    private fun integer(value: JSONObject, key: String): Long {
        val number = value.get(key) as? Number ?: error("Expected integer")
        val result = number.toLong()
        require(number.toDouble() == result.toDouble())
        return result
    }
    private inline fun <T> parse(reason: OsnUpdateError, action: () -> T): T = try { action() }
        catch (error: OsnUpdateException) { throw error }
        catch (_: Exception) { throw OsnUpdateException(reason) }
}
