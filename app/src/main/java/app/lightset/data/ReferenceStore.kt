package app.lightset.data

import android.content.Context
import app.lightset.camera.MeterSource
import app.lightset.color.Calibration
import app.lightset.color.CctDuv
import app.lightset.color.Xy
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A saved ambient reading that lights get matched to. [x]/[y] are the uncalibrated
 * sensor-model chromaticity, so a later calibration still applies to it;
 * [cct]/[duv] are what was displayed when it was saved.
 */
data class Reference(
    val id: String,
    val name: String,
    val cct: Double,
    val duv: Double,
    val x: Double,
    val y: Double,
    val source: MeterSource,
    val createdAt: Long,
) {
    val xy: Xy get() = Xy(x, y)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name)
        .put("cct", cct).put("duv", duv)
        .put("x", x).put("y", y)
        .put("source", source.name)
        .put("createdAt", createdAt)

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        fun fromJson(o: JSONObject) = Reference(
            id = o.getString("id"),
            name = o.getString("name"),
            cct = o.getDouble("cct"),
            duv = o.getDouble("duv"),
            x = o.getDouble("x"),
            y = o.getDouble("y"),
            source = MeterSource.valueOf(o.getString("source")),
            createdAt = o.getLong("createdAt"),
        )
    }
}

class ReferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("references", Context.MODE_PRIVATE)

    fun load(): List<Reference> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { Reference.fromJson(arr.getJSONObject(it)) }
    }

    fun save(list: List<Reference>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    var activeId: String?
        get() = prefs.getString(KEY_ACTIVE, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE, value).apply()

    var calibration: Calibration?
        get() = prefs.getString(KEY_CALIBRATION, null)?.let { runCatching { Calibration.fromJson(it) }.getOrNull() }
        set(value) = prefs.edit().putString(KEY_CALIBRATION, value?.toJson()).apply()

    private companion object {
        const val KEY_LIST = "list"
        const val KEY_ACTIVE = "active"
        const val KEY_CALIBRATION = "calibration"
    }
}
