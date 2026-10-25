// Prueba el perfil y los datapoints del aire acondicionado (node test/aire.test.js "$PWD/aire.js").
const assert = require('assert');
const A = require(process.argv[2]);

// Mapping como el que genera tinytuya para un aire Tuya (temperatura en décimas).
const mapping = {
  1: { code: 'switch', type: 'Boolean', values: {} },
  2: { code: 'temp_set', type: 'Integer', values: { unit: '℃', min: 160, max: 310, scale: 1, step: 5 } },
  3: { code: 'temp_current', type: 'Integer', values: { unit: '℃', min: -200, max: 1000, scale: 1, step: 1 } },
  4: { code: 'mode', type: 'Enum', values: { range: ['auto', 'cold', 'wet', 'wind', 'hot'] } },
  5: { code: 'fan_speed_enum', type: 'Enum', values: { range: ['auto', 'low', 'middle', 'high'] } },
};

assert(A.isAirConditioner({ category: 'kt' }));
assert(A.isAirConditioner({ mapping }));
assert(!A.isAirConditioner({ category: 'dj', mapping: { 20: { code: 'switch_led' } } }));
assert(!A.isAirConditioner(null));

const p = A.profileFromMapping(mapping);
assert.strictEqual(p.tempMin, 16);
assert.strictEqual(p.tempMax, 31);
assert.strictEqual(p.tempStep, 0.5);
assert.deepStrictEqual(p.modes, ['auto', 'cold', 'wet', 'wind', 'hot']);
assert.deepStrictEqual(p.fans, ['auto', 'low', 'middle', 'high']);

// Temperatura con escala y paso.
assert.strictEqual(A.clampTemp(p, 23.3), 23.5);
assert.strictEqual(A.clampTemp(p, 40), 31);
assert.strictEqual(A.clampTemp(p, 10), 16);

const st = { on: true, temp: 22.5, mode: 'hot', fan: 'high' };
assert.deepStrictEqual(A.dpsFor(p, st, ['power', 'temp', 'mode', 'fan']), [
  { id: 1, type: 1, value: true },
  { id: 2, type: 2, value: 225 },
  { id: 4, type: 4, value: 4 },
  { id: 5, type: 4, value: 3 },
]);

// Lo que reporta el aire: enum como posición y temperaturas en décimas.
const s2 = A.applyDatapoints(p, A.newState(p), [
  { id: 1, type: 1, value: true },
  { id: 2, type: 2, value: 240 },
  { id: 3, type: 2, value: 268 },
  { id: 4, type: 4, value: 1 },
  { id: 5, type: 4, value: 2 },
]);
assert.deepStrictEqual(s2, { on: true, temp: 24, current: 26.8, mode: 'cold', fan: 'middle' });

// Sin mapping: DP 1–5 estándar y grados enteros.
const d = A.profileFromMapping(null);
assert.deepStrictEqual(A.dpsFor(d, { on: true, temp: 21, mode: 'cold', fan: 'auto' }, ['temp', 'mode', 'fan']), [
  { id: 2, type: 2, value: 21 },
  { id: 4, type: 4, value: 0 },
  { id: 5, type: 4, value: 3 },
]);
assert.strictEqual(A.label('wet'), 'Seco');
assert.strictEqual(A.label('turbo'), 'turbo');

console.log('aire: OK');
