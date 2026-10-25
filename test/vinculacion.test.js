// Prueba la vinculación contra una ampolleta simulada que sigue la lógica del SDK BLE de Tuya:
// sin vincular tiene la clave de login en ceros, y el PAIR guarda la local_key y la deja vinculada.
// Uso: node test/vinculacion.test.js "$PWD/tuya-ble.js"
const assert = require('assert');
const nodeCrypto = require('crypto');
const T = require(process.argv[2]);
globalThis.crypto ??= nodeCrypto.webcrypto;

const md5 = (b) => nodeCrypto.createHash('md5').update(b).digest();
function aes(dir, key, iv, data) {
  const c = (dir === 'e' ? nodeCrypto.createCipheriv : nodeCrypto.createDecipheriv)('aes-128-cbc', key, iv);
  c.setAutoPadding(false);
  return Buffer.concat([c.update(data), c.final()]);
}

function fakeBulb({ uuid, loginKey, bound }) {
  const state = { loginKey: Buffer.from(loginKey), bound, srand: null, notify: null, inBuf: null, expLen: 0, seq: 100, writes: [] };
  const keyFor = (flag) => (flag === 4 ? md5(state.loginKey) : md5(Buffer.concat([state.loginKey, state.srand])));

  function send(code, data, responseTo, flag) {
    const h = Buffer.alloc(12);
    h.writeUInt32BE(state.seq++, 0); h.writeUInt32BE(responseTo, 4); h.writeUInt16BE(code, 8); h.writeUInt16BE(data.length, 10);
    let raw = Buffer.concat([h, data]);
    const crc = Buffer.alloc(2); crc.writeUInt16BE(T.crc16(raw)); raw = Buffer.concat([raw, crc]);
    raw = Buffer.concat([raw, Buffer.alloc((16 - (raw.length % 16)) % 16)]);
    const iv = nodeCrypto.randomBytes(16);
    const enc = Buffer.concat([Buffer.from([flag]), iv, aes('e', keyFor(flag), iv, raw)]);
    let pos = 0;
    for (let n = 0; pos < enc.length; n++) {
      let head = Buffer.from(T.packInt(n));
      if (n === 0) head = Buffer.concat([head, Buffer.from(T.packInt(enc.length)), Buffer.from([0x30])]);
      const part = enc.subarray(pos, pos + 20 - head.length);
      const pkt = new Uint8Array(Buffer.concat([head, part]));
      setTimeout(() => state.notify({ target: { value: new DataView(pkt.buffer) } }), 1);
      pos += part.length;
    }
  }

  function receive(pkt) {
    let [n, pos] = T.unpackInt(pkt, 0);
    if (n === 0) { [state.expLen, pos] = T.unpackInt(pkt, pos); pos++; state.inBuf = Buffer.alloc(0); }
    state.inBuf = Buffer.concat([state.inBuf, pkt.subarray(pos)]);
    if (state.inBuf.length < state.expLen) return;
    const flag = state.inBuf[0];
    if (flag === 5 && !state.srand) return;
    const raw = aes('d', keyFor(flag), state.inBuf.subarray(1, 17), state.inBuf.subarray(17));
    const seq = raw.readUInt32BE(0), code = raw.readUInt16BE(8), len = raw.readUInt16BE(10);
    // Clave equivocada: el SDK descarta el mensaje en silencio.
    if (12 + len + 2 > raw.length || T.crc16(raw.subarray(0, 12 + len)) !== raw.readUInt16BE(12 + len)) return;
    const data = raw.subarray(12, 12 + len);
    state.writes.push({ code, flag, data });
    if (code === 0) {
      state.srand = nodeCrypto.randomBytes(6);
      const info = Buffer.alloc(46); info[0] = 1; info[2] = 3; info[3] = 2; info[5] = state.bound ? 1 : 0; state.srand.copy(info, 6);
      send(0, info, seq, flag);
    } else if (code === 1) {
      let result;
      if (data.subarray(0, 16).toString() !== uuid) result = 1;
      else if (state.bound) result = 2;
      else { state.loginKey = Buffer.from(data.subarray(16, 22)); state.bound = true; result = 0; }
      send(1, Buffer.from([result]), seq, 5); // ya con la clave nueva, como el SDK
    } else if (code === 3) {
      send(3, Buffer.from([0]), seq, 5);
      send(0x8001, Buffer.from([1, 1, 1, 1]), 0, 5);
    } else if (code === 2) {
      send(2, Buffer.from([0]), seq, 5);
    }
  }

  const chars = {
    write: { writeValueWithoutResponse: async (p) => receive(Buffer.from(p)) },
    notify: { addEventListener: (_, cb) => (state.notify = cb), removeEventListener() {}, startNotifications: async () => {} },
  };
  return { state, chars };
}

async function connect(bulb, cred) {
  const logs = [];
  const dev = new T.TuyaBLEDevice({ ...cred, log: (m) => logs.push(m) });
  const gotDps = new Promise((r) => (dev.onDatapoints = r));
  await dev.attach(bulb.chars.write, bulb.chars.notify);
  await gotDps;
  await dev.setDatapoints([{ id: 1, type: T.DT.BOOL, value: false }]);
  await new Promise((r) => setTimeout(r, 30));
  return { dev, logs };
}

(async () => {
  const cred = { uuid: 'tuyaac3c9b3d9b2e', localKey: '+isNwj=H{jRPMS3s', deviceId: 'ebbeeagbrzm656fp' };
  const key6 = Buffer.from(cred.localKey.slice(0, 6));

  // 1. Ya vinculada (por Smart Life): conecta directo.
  let bulb = fakeBulb({ uuid: cred.uuid, loginKey: key6, bound: true });
  let r = await connect(bulb, cred);
  assert(!r.logs.some((l) => l.includes('sin vincular')), 'no debería probar el modo sin vincular');
  assert(bulb.state.writes.some((w) => w.code === 2), 'debería recibir el comando');
  console.log('vinculada: OK');

  // 2. Sin vincular: la app la vincula con la local_key y luego la controla.
  bulb = fakeBulb({ uuid: cred.uuid, loginKey: Buffer.alloc(6), bound: false });
  r = await connect(bulb, cred);
  assert(bulb.state.bound, 'debería quedar vinculada');
  assert.deepStrictEqual(bulb.state.loginKey, key6, 'debería guardar la local_key');
  assert(bulb.state.writes.some((w) => w.code === 2), 'debería recibir el comando después de vincular');
  console.log('sin vincular: OK');

  // 3. Después de vincularla nosotros, una reconexión normal funciona a la primera.
  r = await connect(bulb, cred);
  assert(!r.logs.some((l) => l.includes('sin vincular')), 'la reconexión no debería necesitar el modo sin vincular');
  console.log('reconexión tras vincular: OK');

  // 4. Sin vincular pero con las credenciales de otra ampolleta: el PAIR falla y no la vincula.
  bulb = fakeBulb({ uuid: 'tuya9b9e6e1dcf9c', loginKey: Buffer.alloc(6), bound: false });
  await assert.rejects(connect(bulb, cred));
  assert(!bulb.state.bound, 'no debería vincularse con otra uuid');
  console.log('credenciales de otra ampolleta: OK');

  console.log('OK: todas las pruebas de vinculación pasaron');
  process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
