// Aire acondicionado Tuya (categoría "kt"): perfil de datapoints a partir del "mapping" de devices.json,
// y conversión entre el estado de la interfaz y los DP.
(function (global) {
  'use strict';

  const DT = { BOOL: 1, VALUE: 2, ENUM: 4 };

  // Nombres en pantalla de los valores que usa Tuya. Los que no estén aquí se muestran tal cual.
  const LABELS = {
    cold: 'Frío', cool: 'Frío', hot: 'Calor', heat: 'Calor', wet: 'Seco', dehumidification: 'Seco', dry: 'Seco',
    wind: 'Ventilar', fan: 'Ventilar', auto: 'Auto', sleep: 'Dormir', eco: 'Eco',
    low: 'Baja', middle: 'Media', mid: 'Media', medium: 'Media', high: 'Alta', strong: 'Turbo', mute: 'Silencio',
  };
  const label = (v) => LABELS[String(v).toLowerCase()] || String(v);

  // Sin mapping: lo estándar de Tuya para aires (DP 1–5, temperatura en grados enteros).
  const DEFAULT_PROFILE = {
    power: 1, tempSet: 2, tempCurrent: 3, mode: 4, fan: 5,
    tempMin: 16, tempMax: 31, tempStep: 1, tempScale: 0, currentScale: 0,
    modes: ['cold', 'hot', 'wet', 'wind', 'auto'],
    fans: ['low', 'middle', 'high', 'auto'],
  };

  const MODE_CODES = ['mode', 'work_mode'];
  const FAN_CODES = ['fan_speed_enum', 'windspeed', 'wind_speed', 'fan_speed', 'speed'];

  /** ¿Es un aire? Por la categoría de tinytuya o porque tiene una temperatura objetivo. */
  function isAirConditioner(device) {
    if (!device) return false;
    if (['kt', 'ktkzq'].includes(device.category)) return true;
    return Object.values(device.mapping || {}).some((m) => m && m.code === 'temp_set');
  }

  function profileFromMapping(mapping) {
    const p = { ...DEFAULT_PROFILE, modes: [...DEFAULT_PROFILE.modes], fans: [...DEFAULT_PROFILE.fans] };
    if (!mapping) return p;
    for (const [id, m] of Object.entries(mapping)) {
      if (!m) continue;
      const v = m.values || {};
      if (m.code === 'switch') p.power = +id;
      else if (m.code === 'temp_set') {
        p.tempSet = +id;
        p.tempScale = v.scale ?? 0;
        const f = 10 ** p.tempScale;
        if (v.min != null) p.tempMin = v.min / f;
        if (v.max != null) p.tempMax = v.max / f;
        p.tempStep = Math.max(1, v.step ?? 1) / f;
      } else if (m.code === 'temp_current') {
        p.tempCurrent = +id;
        p.currentScale = v.scale ?? 0;
      } else if (MODE_CODES.includes(m.code) && Array.isArray(v.range)) {
        p.mode = +id; p.modes = v.range.map(String);
      } else if (FAN_CODES.includes(m.code) && Array.isArray(v.range)) {
        p.fan = +id; p.fans = v.range.map(String);
      }
    }
    return p;
  }

  const newState = (p = DEFAULT_PROFILE) => ({ on: false, temp: 24, current: null, mode: p.modes[0], fan: p.fans[0] });

  function clampTemp(p, t) {
    const steps = Math.round((t - p.tempMin) / p.tempStep);
    return Math.min(p.tempMax, Math.max(p.tempMin, +(p.tempMin + steps * p.tempStep).toFixed(p.tempScale)));
  }

  /** Los DP para las partes del estado indicadas en keys ('power', 'temp', 'mode', 'fan'). */
  function dpsFor(p, st, keys) {
    const out = [];
    for (const k of keys) {
      if (k === 'power') out.push({ id: p.power, type: DT.BOOL, value: !!st.on });
      if (k === 'temp') out.push({ id: p.tempSet, type: DT.VALUE, value: Math.round(clampTemp(p, st.temp) * 10 ** p.tempScale) });
      // Por Bluetooth los enum viajan como su posición en la lista.
      if (k === 'mode' && p.modes.includes(st.mode)) out.push({ id: p.mode, type: DT.ENUM, value: p.modes.indexOf(st.mode) });
      if (k === 'fan' && p.fans.includes(st.fan)) out.push({ id: p.fan, type: DT.ENUM, value: p.fans.indexOf(st.fan) });
    }
    return out;
  }

  /** Actualiza el estado con lo que reporta el aire. */
  function applyDatapoints(p, st, dps) {
    for (const dp of dps) {
      const n = typeof dp.value === 'number' ? dp.value : null;
      if (dp.id === p.power) st.on = !!dp.value;
      else if (dp.id === p.tempSet && n != null) st.temp = n / 10 ** p.tempScale;
      else if (dp.id === p.tempCurrent && n != null) st.current = n / 10 ** p.currentScale;
      else if (dp.id === p.mode) st.mode = typeof dp.value === 'string' ? dp.value : p.modes[n] ?? st.mode;
      else if (dp.id === p.fan) st.fan = typeof dp.value === 'string' ? dp.value : p.fans[n] ?? st.fan;
    }
    return st;
  }

  const api = { DEFAULT_PROFILE, isAirConditioner, profileFromMapping, newState, clampTemp, dpsFor, applyDatapoints, label };
  global.Aire = api;
  if (typeof module !== 'undefined') module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
