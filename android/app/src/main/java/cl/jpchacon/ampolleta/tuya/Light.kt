package cl.jpchacon.ampolleta.tuya

import kotlin.math.roundToInt

/** Datapoints de las luces Tuya. Los valores por defecto son los de la TR-BB-01 (DP 1–5, rangos v2). */
data class LightProfile(
    val power: Int = 1,
    val mode: Int = 2,
    val bright: Int = 3,
    val temp: Int = 4,
    val colour: Int = 5,
    val brightMin: Int = 10,
    val brightMax: Int = 1000,
    val tempMax: Int = 1000,
)

enum class LightMode { WHITE, COLOUR }

/** Estado de una luz en unidades de la interfaz: brillo y temperatura 0–100, tono 0–360, saturación 0–100. */
data class LightState(
    val on: Boolean = false,
    val mode: LightMode = LightMode.WHITE,
    val brightness: Int = 100,
    val temperature: Int = 50,
    val hue: Int = 30,
    val saturation: Int = 100,
)

object Light {
    fun powerDp(p: LightProfile, s: LightState) = Datapoint(p.power, DpType.BOOL, s.on)

    fun modeDp(p: LightProfile, s: LightState) = Datapoint(p.mode, DpType.ENUM, if (s.mode == LightMode.COLOUR) 1 else 0)

    fun brightDp(p: LightProfile, s: LightState) =
        Datapoint(p.bright, DpType.VALUE, (p.brightMin + (p.brightMax - p.brightMin) * s.brightness.coerceIn(1, 100) / 100.0).roundToInt())

    fun tempDp(p: LightProfile, s: LightState) =
        Datapoint(p.temp, DpType.VALUE, (p.tempMax * s.temperature.coerceIn(0, 100) / 100.0).roundToInt())

    /** colour_data_v2: "HHHHSSSSVVVV" en hex, tono 0–360, saturación y valor 0–1000. */
    fun colourDp(p: LightProfile, s: LightState): Datapoint {
        val v = s.brightness.coerceIn(1, 100) * 10
        val text = "%04x%04x%04x".format(s.hue.coerceIn(0, 360), s.saturation.coerceIn(0, 100) * 10, v)
        return Datapoint(p.colour, DpType.STRING, text)
    }

    /** Los DP que hay que enviar para pasar a este estado completo. */
    fun fullState(p: LightProfile, s: LightState): List<Datapoint> =
        if (!s.on) listOf(powerDp(p, s))
        else if (s.mode == LightMode.COLOUR) listOf(powerDp(p, s), modeDp(p, s), colourDp(p, s))
        else listOf(powerDp(p, s), modeDp(p, s), brightDp(p, s), tempDp(p, s))

    /** Actualiza el estado con lo que reporta la ampolleta. */
    fun apply(p: LightProfile, s: LightState, dps: List<Datapoint>): LightState {
        var st = s
        // El modo primero, porque decide si el brillo viene en el DP de brillo o en el de color.
        dps.firstOrNull { it.id == p.mode }?.let { dp ->
            (dp.value as? Int)?.let { st = st.copy(mode = if (it == 1) LightMode.COLOUR else LightMode.WHITE) }
        }
        for (dp in dps) {
            when (dp.id) {
                p.power -> (dp.value as? Boolean)?.let { st = st.copy(on = it) }
                p.bright -> (dp.value as? Int)?.let {
                    if (st.mode == LightMode.WHITE) {
                        st = st.copy(brightness = (100.0 * (it - p.brightMin) / (p.brightMax - p.brightMin)).roundToInt().coerceIn(1, 100))
                    }
                }
                p.temp -> (dp.value as? Int)?.let { st = st.copy(temperature = (100.0 * it / p.tempMax).roundToInt().coerceIn(0, 100)) }
                p.colour -> (dp.value as? String)?.takeIf { it.length == 12 }?.let { text ->
                    val h = text.substring(0, 4).toIntOrNull(16)
                    val sat = text.substring(4, 8).toIntOrNull(16)
                    val v = text.substring(8, 12).toIntOrNull(16)
                    if (h != null && sat != null && v != null) {
                        st = st.copy(hue = h.coerceIn(0, 360), saturation = (sat / 10).coerceIn(0, 100))
                        if (st.mode == LightMode.COLOUR) st = st.copy(brightness = (v / 10).coerceIn(1, 100))
                    }
                }
            }
        }
        return st
    }
}
