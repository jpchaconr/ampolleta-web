package cl.jpchacon.ampolleta.ble

import android.content.Context
import cl.jpchacon.ampolleta.tuya.Credentials
import org.json.JSONArray
import org.json.JSONObject

/** Una ampolleta que la app ya vinculó, con la clave que inventó para ella. */
data class SavedBulb(val mac: String, val name: String, val credentials: Credentials)

/** Guarda las ampolletas vinculadas en las preferencias de la app (solo en este teléfono). */
class BulbStore(context: Context) {
    private val prefs = context.getSharedPreferences("ampolletas", Context.MODE_PRIVATE)

    fun load(): List<SavedBulb> {
        val text = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SavedBulb(
                    mac = o.getString("mac"),
                    name = o.getString("name"),
                    credentials = Credentials(o.getString("uuid"), o.getString("localKey"), o.getString("deviceId")),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(bulbs: List<SavedBulb>) {
        val arr = JSONArray()
        for (b in bulbs) {
            arr.put(
                JSONObject()
                    .put("mac", b.mac)
                    .put("name", b.name)
                    .put("uuid", b.credentials.uuid)
                    .put("localKey", b.credentials.localKey)
                    .put("deviceId", b.credentials.deviceId),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "bulbs"
    }
}
