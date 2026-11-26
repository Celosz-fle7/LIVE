package com.liveStream.app.core.updater

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.liveStream.app.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val changelog: String,
    val forceUpdate: Boolean = false
)

object AppUpdater {
    private val MANIFEST_URLS = listOf(
        "https://raw.githubusercontent.com/Celosz-fle7/LIVE-release/main/version.json",
        "https://cdn.jsdelivr.net/gh/Celosz-fle7/LIVE-release@main/version.json"
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun checkForUpdate(onResult: (UpdateInfo?) -> Unit) {
        executor.execute {
            val currentVersionCode = BuildConfig.VERSION_CODE
            for (rawUrl in MANIFEST_URLS) {
                try {
                    val conn = (URL("$rawUrl?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 7000
                        readTimeout = 7000
                        requestMethod = "GET"
                        useCaches = false
                        setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile)")
                    }
                    if (conn.responseCode == 200) {
                        val content = conn.inputStream.bufferedReader().use { it.readText() }
                        conn.disconnect()
                        val json = JSONObject(content)
                        val remoteCode = json.optInt("versionCode", 0)
                        val remoteName = json.optString("versionName", "")
                        val apkUrl = json.optString("apkUrl", "")
                        val changelog = json.optString("changelog", "Pembaruan tersedia.")
                        val force = json.optBoolean("forceUpdate", false)

                        if (remoteCode > currentVersionCode && apkUrl.isNotEmpty()) {
                            mainHandler.post {
                                onResult(UpdateInfo(remoteCode, remoteName, apkUrl, changelog, force))
                            }
                            return@execute
                        } else if (remoteCode > 0) {
                            mainHandler.post { onResult(null) }
                            return@execute
                        }
                    }
                    conn.disconnect()
                } catch (_: Exception) {
                }
            }
            mainHandler.post { onResult(null) }
        }
    }

    fun downloadAndInstall(
        activity: Activity,
        info: UpdateInfo,
        onProgress: (Int) -> Unit,
        onError: (String) -> Unit
    ) {
        executor.execute {
            try {
                var currentUrl = info.apkUrl
                var conn: HttpURLConnection? = null
                var redirects = 0
                while (redirects < 6) {
                    val url = URL(currentUrl)
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 30000
                        requestMethod = "GET"
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile)")
                    }
                    if (conn.responseCode in listOf(301, 302, 303, 307, 308)) {
                        val loc = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (!loc.isNullOrEmpty()) {
                            currentUrl = if (loc.startsWith("http")) loc else URL(url, loc).toString()
                            redirects++
                            continue
                        }
                    }
                    break
                }

                if (conn == null || conn.responseCode !in 200..299) {
                    val code = conn?.responseCode ?: -1
                    mainHandler.post { onError("Gagal mengunduh: HTTP $code") }
                    return@execute
                }

                val total = conn.contentLength
                val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
                val target = File(dir, "LIVE-${info.versionName}.apk")

                conn.inputStream.use { input ->
                    FileOutputStream(target).use { output ->
                        val buf = ByteArray(8192)
                        var read: Int
                        var sum = 0L
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            sum += read
                            if (total > 0) {
                                val pct = ((sum * 100) / total).toInt()
                                mainHandler.post { onProgress(pct) }
                            }
                        }
                        output.flush()
                    }
                }
                conn.disconnect()
                mainHandler.post { installApk(activity, target) }
            } catch (e: Exception) {
                mainHandler.post { onError("Error update: ${e.message}") }
            }
        }
    }

    private fun installApk(activity: Activity, apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                )
            } catch (_: Exception) {
                Toast.makeText(activity, "Izinkan instalasi dari sumber tidak dikenal.", Toast.LENGTH_LONG).show()
            }
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apkFile)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
