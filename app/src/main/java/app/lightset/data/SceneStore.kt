package app.lightset.data

import android.content.Context
import app.lightset.LightRig
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Every light's settings at one moment, by unicast address, to restore later. */
data class Scene(
    val id: String,
    val name: String,
    val createdAt: Long,
    val lights: Map<Int, LightRig.LightState>,
) {
    fun toJson(): JSONObject {
        val arr = JSONArray()
        lights.forEach { (u, s) ->
            arr.put(
                JSONObject()
                    .put("unicast", u)
                    .put("brightness", s.brightness ?: -1)
                    .put("cct", s.cct).put("gm", s.gm)
                    .put("mode", s.mode.name)
                    .put("hue", s.hue).put("sat", s.sat)
                    .put("on", s.on)
            )
        }
        return JSONObject().put("id", id).put("name", name).put("createdAt", createdAt).put("lights", arr)
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        fun fromJson(o: JSONObject): Scene {
            val arr = o.getJSONArray("lights")
            val lights = (0 until arr.length()).associate { i ->
                val l = arr.getJSONObject(i)
                l.getInt("unicast") to LightRig.LightState(
                    brightness = l.getInt("brightness").takeIf { it >= 0 },
                    cct = l.getInt("cct"),
                    gm = l.getInt("gm"),
                    mode = runCatching { LightRig.Mode.valueOf(l.getString("mode")) }.getOrDefault(LightRig.Mode.CCT),
                    hue = l.getInt("hue"),
                    sat = l.getInt("sat"),
                    on = l.getBoolean("on"),
                )
            }
            return Scene(o.getString("id"), o.getString("name"), o.getLong("createdAt"), lights)
        }
    }
}

class SceneStore(context: Context) {
    private val prefs = context.getSharedPreferences("scenes", Context.MODE_PRIVATE)

    fun load(): List<Scene> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).mapNotNull { runCatching { Scene.fromJson(arr.getJSONObject(it)) }.getOrNull() }
    }

    fun save(list: List<Scene>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private companion object {
        const val KEY_LIST = "list"
    }
}
