package com.shilapi.xcertplay

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/** The package installer owns the installation confirmation; APKs are granted read-only access. */
internal object OsnUpdateInstaller {
    fun permitted(context: Context): Boolean = runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
    fun permissionIntent(context: Context) = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"))
    fun installIntent(context: Context, file: File): Intent {
        require(file.isFile && file.parentFile?.canonicalFile == File(context.cacheDir, "updates").canonicalFile && file.extension == "apk")
        require(Regex("[0-9]+-[a-f0-9]{64}\\.apk").matches(file.name))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newRawUri("OsnPlay update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

class OsnUpdateProvider : FileProvider()
