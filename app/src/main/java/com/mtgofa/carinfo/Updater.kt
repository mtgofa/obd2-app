package com.mtgofa.carinfo

import android.content.Context
import android.content.Intent
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
data class AppRelease(val versionCode: Int, val tagName: String, val downloadUrl: String, val sha256: String?)

/**
 * Auto-update via GitHub Releases "latest" (public repo, no token needed).
 *
 * The release must follow this contract:
 *   name      -> "Car Info 1.1.0 (code 2)"
 *   tag_name  -> "v1.1.0"
 *   assets    -> contains one *.apk
 *   body      -> optional line "sha256: <64-hex>" (verified when present)
 */
object Updater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var checking by mutableStateOf(false); private set
    var downloading by mutableStateOf(false); private set
    var release by mutableStateOf<AppRelease?>(null); private set
    var message by mutableStateOf(""); private set
    /** The "new version" popup — shown as soon as a check finds one, so nobody misses an update. */
    var prompt by mutableStateOf(false); private set

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
    }

    private fun get(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "CarInfo-Android")
        }

    private fun fetch(): AppRelease? {
        val c = get("https://api.github.com/repos/${BuildConfig.GITHUB_OWNER}/${BuildConfig.GITHUB_REPO}/releases/latest")
        try {
            if (c.responseCode == 404) throw IllegalStateException("no releases yet")
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            val code = Regex("""code\s*(\d+)""", RegexOption.IGNORE_CASE).find(o.optString("name"))
                ?.groupValues?.get(1)?.toIntOrNull() ?: throw IllegalStateException("no \"code N\" in release name")
            if (code <= BuildConfig.VERSION_CODE) return null
            val assets = o.optJSONArray("assets")
            var url = ""
            if (assets != null) for (i in 0 until assets.length()) {
                val u = assets.getJSONObject(i).optString("browser_download_url")
                if (u.endsWith(".apk")) { url = u; break }
            }
            if (url.isBlank()) throw IllegalStateException("no apk in release")
            val sha = Regex("""sha256[:=]\s*([0-9a-fA-F]{64})""").find(o.optString("body"))?.groupValues?.get(1)?.lowercase()
            return AppRelease(code, o.optString("tag_name"), url, sha)
        } finally {
            c.disconnect()
        }
    }

    /** Download the new APK (SHA-256 verified when published), then hand it to the system installer. */
    fun install(context: Context) {
        val rel = release ?: return
        if (downloading) return
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
            message = "Installing…"
            prompt = false
            val uri = FileProvider.getUriForFile(app, app.packageName + ".fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { app.startActivity(intent) }.onFailure { message = "Could not open the installer" }
        }
    }

    private fun download(rel: AppRelease, dir: File): File? {
        dir.mkdirs()
        // GitHub redirects asset downloads to its CDN; HttpURLConnection follows same-protocol redirects.
        val c = get(rel.downloadUrl).apply { setRequestProperty("Accept", "application/octet-stream") }
        val bytes = try {
            if (c.responseCode !in 200..299) return null
            c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
        rel.sha256?.let { expected ->
            val got = BigInteger(1, MessageDigest.getInstance("SHA-256").digest(bytes)).toString(16).padStart(64, '0')
            if (got != expected) return null
        }
        return File(dir, "update.apk").also { it.writeBytes(bytes) }
    }
}
