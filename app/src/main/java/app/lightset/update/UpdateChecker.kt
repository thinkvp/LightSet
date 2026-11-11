package app.lightset.update

import android.content.Context
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A release on GitHub newer than the installed app. [url] is its APK, or the release page. */
data class Update(val version: String, val url: String)

/**
 * Checks GitHub for a newer release than the one installed. Each push to main publishes
 * one, tagged `v<versionName>`. "Later" hides the prompt until [snoozeUntil].
 */
class UpdateChecker(private val context: Context) {
    private val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)

    val installed: String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    var snoozeUntil: Long
        get() = prefs.getLong(KEY_SNOOZE, 0)
        set(value) = prefs.edit().putLong(KEY_SNOOZE, value).apply()

    fun snooze(days: Int) {
        snoozeUntil = System.currentTimeMillis() + days * DAY_MS
    }

    /** The newer release, or null if there isn't one, it's snoozed, or GitHub can't be reached. */
    suspend fun check(): Update? {
        // Debug builds are versioned "0.1", which every release would look newer than.
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return null
        if (System.currentTimeMillis() < snoozeUntil) return null
        val latest = withContext(Dispatchers.IO) { runCatching { fetchLatest() }.getOrNull() } ?: return null
        return latest.takeIf { isNewer(it.version, installed) }
    }

    private fun fetchLatest(): Update {
        val conn = URL(LATEST_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") }
            return Update(
                version = json.getString("tag_name").removePrefix("v"),
                url = apk?.getString("browser_download_url") ?: json.getString("html_url"),
            )
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val LATEST_URL = "https://api.github.com/repos/thinkvp/LightSet/releases/latest"
        private const val KEY_SNOOZE = "snooze_until"
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** Compares dotted versions number by number, so 0.1.10 is newer than 0.1.9. */
        fun isNewer(candidate: String, current: String): Boolean {
            val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
            val b = current.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
                if (d != 0) return d > 0
            }
            return false
        }
    }
}
