package app.lightset.mesh

import android.content.Context
import app.lightset.ble.hex
import app.lightset.color.LightLimits
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/** What a Nanlite model can do; picked from the light's Bluetooth name. */
enum class LightModel(val label: String, val limits: LightLimits, val rgb: Boolean) {
    FORZA_60C("Forza 60C", LightLimits(1800, 20000, -100, 100), rgb = true),
    PAVOSLIM_120C("PavoSlim 120C", LightLimits(2700, 7500, -150, 150), rgb = true),
    PAVOTUBE_II("PavoTube II", LightLimits(2700, 7500, -100, 100), rgb = true),
    FORZA_300B_II("Forza 300B II", LightLimits(2700, 6500, -80, 80), rgb = false),
    FC_500B("FC-500B", LightLimits(2700, 6500, 0, 0), rgb = false),
    BICOLOUR("Bi-colour light", LightLimits(2700, 6500, -100, 100), rgb = false),
    RGB("RGB light", LightLimits(2700, 7500, -100, 100), rgb = true);

    companion object {
        fun fromName(name: String?): LightModel {
            val n = name?.lowercase()?.replace(" ", "")?.replace("-", "") ?: return BICOLOUR
            return when {
                "60c" in n -> FORZA_60C
                "pavoslim" in n || "120c" in n -> PAVOSLIM_120C
                "pavotube" in n || "tube" in n -> PAVOTUBE_II
                "300b" in n -> FORZA_300B_II
                "fc500b" in n || "500b" in n -> FC_500B
                n.endsWith("c") -> RGB
                else -> BICOLOUR
            }
        }
    }
}

/** A light in our network. [address] is its Bluetooth address, for connecting to it as proxy. */
data class MeshNode(
    val unicast: Int,
    val name: String,
    val model: LightModel,
    val address: String,
    val deviceKey: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("unicast", unicast).put("name", name).put("model", model.name)
        .put("address", address).put("deviceKey", deviceKey ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = MeshNode(
            unicast = o.getInt("unicast"),
            name = o.getString("name"),
            model = runCatching { LightModel.valueOf(o.getString("model")) }.getOrDefault(LightModel.BICOLOUR),
            address = o.getString("address"),
            deviceKey = if (o.isNull("deviceKey")) null else o.getString("deviceKey"),
        )
    }
}

/**
 * Our Bluetooth Mesh network: its key, IV index, our own address and the lights in it.
 * Each install makes its own random network key the first time it runs.
 */
class MeshStore(context: Context) {
    private val prefs = context.getSharedPreferences("mesh_net", Context.MODE_PRIVATE)

    val netKey: ByteArray = prefs.getString(KEY_NET, null)?.let(::unhex) ?: ByteArray(16).also {
        SecureRandom().nextBytes(it)
        prefs.edit().putString(KEY_NET, it.hex()).apply()
    }
    val ivIndex: Int get() = prefs.getInt(KEY_IV, 0)

    /** Our own unicast address as the controlling app. */
    val ourAddress: Int = 0x0010

    fun nodes(): List<MeshNode> {
        val raw = prefs.getString(KEY_NODES, null)
            ?: return emptyList()
        val a = JSONArray(raw)
        return (0 until a.length()).map { MeshNode.fromJson(a.getJSONObject(it)) }
    }

    fun save(nodes: List<MeshNode>) {
        val a = JSONArray()
        nodes.forEach { a.put(it.toJson()) }
        prefs.edit().putString(KEY_NODES, a.toString()).apply()
    }

    /** Next free unicast address for a light with [elements] elements. */
    fun nextUnicast(elements: Int = 1): Int {
        val used = nodes().maxOfOrNull { it.unicast } ?: 0x0002
        val next = maxOf(used + 1, 0x0003)
        // Don't overlap our own address.
        return if (next <= ourAddress && next + elements - 1 >= ourAddress) ourAddress + 1 else next
    }

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        const val KEY_NET = "net_key"
        const val KEY_IV = "iv_index"
        const val KEY_NODES = "nodes"
    }
}
