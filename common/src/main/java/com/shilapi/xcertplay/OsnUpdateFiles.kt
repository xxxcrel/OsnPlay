package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.security.MessageDigest

internal fun File.updateSha256(): String = inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

internal class OsnUpdateFiles(private val directory: () -> File,
    private val validateArchive: (File, OsnUpdateRelease) -> Unit,
) {
    fun download(release: OsnUpdateRelease, http: OsnUpdateHttp, cancelled: () -> Boolean,
        progress: (Long) -> Unit, verifying: () -> Unit): File {
        val folder = directory()
        if (!folder.isDirectory && !folder.mkdirs()) throw OsnUpdateException(OsnUpdateError.STORAGE)
        val target = File(folder, "${release.versionCode}-${release.sha256}.apk")
        if (target.isFile) {
            try { verifying(); verify(target, release); return target }
            catch (_: OsnUpdateException) { target.delete() }
        }
        if (cancelled()) throw InterruptedIOException()
        val partial = File.createTempFile("download-", ".part.apk", folder)
        try {
            http.open(release.apk.url).use { response ->
                response.requireSuccess()
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytes = 0L
                    while (true) {
                        if (cancelled()) throw InterruptedIOException()
                        val count = response.input.read(buffer)
                        if (count < 0) break
                        bytes += count
                        if (bytes > release.apk.size || bytes > OsnUpdateProtocol.MAX_APK_BYTES)
                            throw OsnUpdateException(OsnUpdateError.SIZE)
                        output.write(buffer, 0, count); progress(bytes)
                    }
                    if (bytes != release.apk.size) throw OsnUpdateException(OsnUpdateError.SIZE)
                    output.fd.sync()
                }
            }
            if (cancelled()) throw InterruptedIOException()
            verifying(); verify(partial, release)
            if (cancelled()) throw InterruptedIOException()
            if (!partial.renameTo(target)) throw OsnUpdateException(OsnUpdateError.STORAGE)
            // Keep recent completed APKs so a system installer holding an earlier URI is not disrupted.
            folder.listFiles()?.filter { it != target && it.isFile && Regex("[0-9]+-[a-f0-9]{64}\\.apk").matches(it.name) }
                ?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.delete() }
            folder.listFiles()?.filter { it.isFile && it.name.startsWith("download-") }?.forEach { it.delete() }
            return target
        } finally { partial.delete() }
    }

    fun verify(file: File, release: OsnUpdateRelease) {
        if (!file.isFile) throw OsnUpdateException(OsnUpdateError.CACHE_MISSING)
        if (file.length() != release.apk.size) throw OsnUpdateException(OsnUpdateError.SIZE)
        if (file.updateSha256() != release.sha256) throw OsnUpdateException(OsnUpdateError.HASH)
        validateArchive(file, release)
    }
}

internal class OsnUpdatePackages(private val app: Context) {
    fun installed(): OsnInstalledApp {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        return OsnInstalledApp(app.packageName, info.longVersionCode, Build.VERSION.SDK_INT, certificates(info))
    }
    fun verifyArchive(file: File, release: OsnUpdateRelease) {
        val info = app.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw OsnUpdateException(OsnUpdateError.PACKAGE)
        val installed = installed()
        if (info.packageName != installed.packageName) throw OsnUpdateException(OsnUpdateError.PACKAGE)
        if (info.longVersionCode != release.versionCode || info.versionName != release.versionName ||
            info.longVersionCode <= installed.versionCode) throw OsnUpdateException(OsnUpdateError.VERSION)
        if (installed.sdk < release.minSdk || installed.sdk < (info.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE))
            throw OsnUpdateException(OsnUpdateError.ANDROID_VERSION)
        if (installed.certificates.isEmpty() || installed.certificates != certificates(info))
            throw OsnUpdateException(OsnUpdateError.SIGNATURE)
    }
    private fun certificates(info: PackageInfo): Set<String> = info.signingInfo?.apkContentsSigners.orEmpty().map {
        MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }.toSet()
}
