package com.mtgofa.carinfo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A new version announced on GitHub Releases. */
data class AppRelease(
    val versionCode: Int,
    val tagName: String,
    val downloadUrl: String,
    val sha256: String?,
    val notes: String = ""
)

/**
 * Auto-update via GitHub Releases "latest" (public repo, no token needed).
 *
 * Supported release formats:
 *   name      -> "Car Info 1.1.1 (code 4)" or "v1.1.1" or "1.1.1"
 *   tag_name  -> "v1.1.1"
 *   assets    -> contains an APK file (*.apk)
 *   body      -> optional changelog notes, optional line "sha256: <64-hex>"
 */
object Updater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var checking by mutableStateOf(false); private set
    var downloading by mutableStateOf(false); private set
    var release by mutableStateOf<AppRelease?>(null); private set
    var downloadedFile by mutableStateOf<File?>(null); private set
    var message by mutableStateOf(""); private set
    /** The "new version" popup — shown as soon as a check finds one, so nobody misses an update. */
    var prompt by mutableStateOf(false); private set
    var pendingInstallPermission by mutableStateOf(false); private set

    private val configured get() = BuildConfig.GITHUB_OWNER.isNotBlank() && BuildConfig.GITHUB_REPO.isNotBlank()

    /** [auto] keeps failures silent; a real update always shows the popup. */
    fun check(auto: Boolean = false) {
        if (checking || !configured) return
        checking = true
        message = if (auto) "" else "Checking for updates…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { fetch() } }
            r.onSuccess { rel ->
                if (rel == null) {
                    if (!auto) message = "You're on the latest version"
                } else {
                    release = rel
                    message = "Update ${rel.tagName} available"
                    prompt = true
                }
            }.onFailure { if (!auto) message = "Update check failed: ${it.message}" }
            checking = false
        }
    }

    fun later() {
        prompt = false
        pendingInstallPermission = false
    }

    private fun get(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "CarInfo-Android")
        }

    private fun isNewerVersion(remoteTag: String, localVersion: String): Boolean {
        val r = remoteTag.trimStart('v', 'V').split(".").mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
        val l = localVersion.trimStart('v', 'V').split(".").mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv > lv) return true
            if (rv < lv) return false
        }
        return false
    }

    private fun fetch(): AppRelease? {
        val c = get("https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest")
        try {
            if (c.responseCode == 404) throw IllegalStateException("no releases yet")
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            val name = o.optString("name")
            val tagName = o.optString("tag_name")
            val body = o.optString("body")

            val codeInName = Regex("""code\s*(\d+)""", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)?.toIntOrNull()
            val codeInBody = Regex("""code\s*(\d+)""", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.toIntOrNull()
            val code = codeInName ?: codeInBody

            val isUpdateAvailable = if (code != null) {
                code > BuildConfig.VERSION_CODE
            } else {
                isNewerVersion(tagName, BuildConfig.VERSION_NAME)
            }

            if (!isUpdateAvailable) return null

            val assets = o.optJSONArray("assets")
            var url = ""
            if (assets != null) for (i in 0 until assets.length()) {
                val u = assets.getJSONObject(i).optString("browser_download_url")
                if (u.contains(".apk", ignoreCase = true)) { url = u; break }
            }
            if (url.isBlank()) throw IllegalStateException("no apk found in release assets")

            val sha = Regex("""sha256[:=]\s*([0-9a-fA-F]{64})""").find(body)?.groupValues?.get(1)?.lowercase()
            val cleanNotes = body.lines()
                .filterNot { it.trim().startsWith("sha256", ignoreCase = true) }
                .joinToString("\n")
                .trim()

            return AppRelease(code ?: (BuildConfig.VERSION_CODE + 1), tagName.ifBlank { name }, url, sha, cleanNotes)
        } finally {
            c.disconnect()
        }
    }

    /** Download the new APK (SHA-256 verified when published), then hand it to the system installer. */
    fun install(context: Context) {
        val rel = release ?: return
        if (downloading) return

        val cached = downloadedFile ?: File(context.applicationContext.cacheDir, "updater/update.apk")
        if (cached.exists() && cached.length() > 0 && isFileValid(cached, rel)) {
            downloadedFile = cached
            triggerInstall(context, cached)
            return
        }

        downloading = true
        message = "Downloading update…"
        val app = context.applicationContext
        scope.launch {
            val file = withContext(Dispatchers.IO) { runCatching { download(rel, File(app.cacheDir, "updater")) }.getOrNull() }
            downloading = false
            if (file == null) {
                message = "Download or checksum check failed"
                return@launch
            }
            downloadedFile = file
            triggerInstall(context, file)
        }
    }

    private fun isFileValid(file: File, rel: AppRelease): Boolean {
        if (rel.sha256 == null) return true
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return false
        val got = BigInteger(1, MessageDigest.getInstance("SHA-256").digest(bytes)).toString(16).padStart(64, '0')
        return got.equals(rel.sha256, ignoreCase = true)
    }

    fun triggerInstall(context: Context, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                pendingInstallPermission = true
                prompt = true
                message = "Please allow \"Install unknown apps\", then return here."
                val manageIntent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(manageIntent) }
                return
            }
        }
        pendingInstallPermission = false
        message = "Installing…"
        launchInstaller(context, file)
    }

    fun onResume(context: Context) {
        val file = downloadedFile ?: run {
            val cached = File(context.applicationContext.cacheDir, "updater/update.apk")
            val rel = release
            if (rel != null && cached.exists() && isFileValid(cached, rel)) cached else null
        } ?: return

        downloadedFile = file

        if (pendingInstallPermission) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()) {
                pendingInstallPermission = false
                prompt = true
                message = "Ready to install"
                launchInstaller(context, file)
            }
        }
    }

    private fun launchInstaller(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            message = "Could not open installer: ${e.message}"
        }
    }

    private fun download(rel: AppRelease, dir: File): File? {
        dir.mkdirs()
        var currentUrl = rel.downloadUrl
        var conn: HttpURLConnection? = null
        var bytes: ByteArray? = null
        try {
            var redirects = 0
            while (redirects < 6) {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/octet-stream")
                    setRequestProperty("User-Agent", "CarInfo-Android")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: break
                    conn.disconnect()
                    currentUrl = if (loc.startsWith("http")) loc else URL(URL(currentUrl), loc).toString()
                    redirects++
                } else if (code in 200..299) {
                    bytes = conn.inputStream.use { it.readBytes() }
                    break
                } else {
                    return null
                }
            }
        } finally {
            conn?.disconnect()
        }

        if (bytes == null) return null
        rel.sha256?.let { expected ->
            val got = BigInteger(1, MessageDigest.getInstance("SHA-256").digest(bytes)).toString(16).padStart(64, '0')
            if (got != expected) return null
        }
        val file = File(dir, "update.apk")
        file.writeBytes(bytes)
        return file
    }
}
