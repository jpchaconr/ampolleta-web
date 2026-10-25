// Prueba con una ampolleta simulada que usa la criptografía de Node (independiente de la del cliente).
const assert = require('assert');
const nodeCrypto = require('crypto');
const T = require(process.argv[2]);

const md5n = (b) => new Uint8Array(nodeCrypto.createHash('md5').update(b).digest());
function aes(dir, key, iv, data) {
  const c = (dir === 'e' ? nodeCrypto.createCipheriv : nodeCrypto.createDecipheriv)('aes-128-cbc', key, iv);
  c.setAutoPadding(false);
  return new Uint8Array(Buffer.concat([c.update(data), c.final()]));
}

(async () => {
  // MD5
  for (const s of ['', 'a', 'azsnpwzx', 'x'.repeat(55), 'y'.repeat(56), 'z'.repeat(64), 'w'.repeat(200)]) {
    assert.deepStrictEqual(T.md5(Buffer.from(s)), md5n(Buffer.from(s)), 'md5 ' + s.length);
  }
  // AES sin padding
  const key = nodeCrypto.randomBytes(16), iv = nodeCrypto.randomBytes(16);
  for (const n of [16, 32, 64]) {
    const pt = new Uint8Array(nodeCrypto.randomBytes(n));
    const ct = await T.aesEncryptNoPad(key, iv, pt);
    assert.deepStrictEqual(ct, aes('e', key, iv, pt));
    assert.deepStrictEqual(await T.aesDecryptNoPad(key, iv, ct), pt);
  }
  // UUID del anuncio real de la ampolleta
  const k = md5n(Buffer.from('azsnpwzx'));
  assert.strictEqual(Buffer.from(aes('d', k, k, Buffer.from('5FA9EEC94B37EEAA5475E883EFAA0684', 'hex'))).toString(), 'tuyaac3c9b3d9b2e');

  // Ampolleta simulada
  const LOCAL_KEY = 'abcdef123456', UUID = 'tuyaac3c9b3d9b2e', DEV_ID = 'ebf0123456789abcdxyz';
  const lk6 = Buffer.from(LOCAL_KEY.slice(0, 6));
  const loginKey = md5n(lk6);
  const srand = Buffer.from('R4nd0m');
  const sessionKey = md5n(Buffer.concat([lk6, srand]));
  const received = [];
  let notifyListener, devSeq = 100, inBuf = null, expLen = 0;

  function devSend(code, data, responseTo, flag = 5) {
    const h = Buffer.alloc(12);
    h.writeUInt32BE(devSeq++, 0); h.writeUInt32BE(responseTo, 4); h.writeUInt16BE(code, 8); h.writeUInt16BE(data.length, 10);
    let raw = Buffer.concat([h, data]);
    const crc = Buffer.alloc(2); crc.writeUInt16BE(T.crc16(raw)); raw = Buffer.concat([raw, crc]);
    raw = Buffer.concat([raw, Buffer.alloc((16 - (raw.length % 16)) % 16)]);
    const ivd = nodeCrypto.randomBytes(16);
    const encd = Buffer.concat([Buffer.from([flag]), ivd, aes('e', flag === 4 ? loginKey : sessionKey, ivd, raw)]);
    // Fragmenta igual que el cliente
    let pos = 0;
    for (let n = 0; pos < encd.length; n++) {
      let head = Buffer.from(T.packInt(n));
      if (n === 0) head = Buffer.concat([head, Buffer.from(T.packInt(encd.length)), Buffer.from([0x30])]);
      const part = encd.subarray(pos, pos + 20 - head.length);
      const pkt = new Uint8Array(Buffer.concat([head, part]));
      setTimeout(() => notifyListener({ target: { value: new DataView(pkt.buffer) } }), 1);
      pos += part.length;
    }
  }

  function devReceive(pkt) {
    assert(pkt.length <= 20);
    let [n, pos] = T.unpackInt(pkt, 0);
    if (n === 0) { [expLen, pos] = T.unpackInt(pkt, pos); assert.strictEqual(pkt[pos], 0x30); pos++; inBuf = Buffer.alloc(0); }
    inBuf = Buffer.concat([inBuf, pkt.subarray(pos)]);
    if (inBuf.length < expLen) return;
    const flag = inBuf[0];
    const raw = Buffer.from(aes('d', flag === 4 ? loginKey : sessionKey, inBuf.subarray(1, 17), inBuf.subarray(17)));
    const seq = raw.readUInt32BE(0), code = raw.readUInt16BE(8), len = raw.readUInt16BE(10);
    assert.strictEqual(T.crc16(raw.subarray(0, 12 + len)), raw.readUInt16BE(12 + len), 'crc');
    const data = raw.subarray(12, 12 + len);
    received.push({ code, flag, data });
    if (code === 0) {
      assert.strictEqual(flag, 4);
      const info = Buffer.alloc(46); info[0] = 1; info[1] = 2; info[2] = 3; info[3] = 0; srand.copy(info, 6);
      devSend(0, info, seq, 4);
    } else if (code === 1) {
      assert.strictEqual(data.length, 44);
      assert.strictEqual(data.subarray(0, 16).toString(), UUID);
      assert.strictEqual(data.subarray(16, 22).toString(), 'abcdef');
      assert.strictEqual(data.subarray(22, 42).toString(), DEV_ID);
      devSend(1, Buffer.from([2]), seq); // "ya emparejado" también es éxito
    } else if (code === 3) {
      devSend(3, Buffer.from([0]), seq);
      const s = Buffer.from('00f003e803e8');
      devSend(0x8001, Buffer.concat([Buffer.from([20, 1, 1, 1, 22, 2, 4, 0, 0, 1, 0xf4, 24, 3, s.length]), s]), 0);
      devSend(0x8011, Buffer.alloc(0), 0);
    } else if (code === 2) {
      devSend(2, Buffer.from([0]), seq);
    }
  }

  globalThis.crypto ??= nodeCrypto.webcrypto;
  const logs = [];
  const dev = new T.TuyaBLEDevice({ uuid: UUID, localKey: LOCAL_KEY, deviceId: DEV_ID, log: (m) => logs.push(m) });
  const gotDps = new Promise((r) => (dev.onDatapoints = r));
  await dev.attach(
    { writeValueWithoutResponse: async (p) => devReceive(Buffer.from(p)) },
    { addEventListener: (_, cb) => (notifyListener = cb), startNotifications: async () => {} }
  );
  const dps = await gotDps;
  assert.deepStrictEqual(dps, [
    { id: 20, type: 1, value: true }, { id: 22, type: 2, value: 500 }, { id: 24, type: 3, value: '00f003e803e8' },
  ]);
  await dev.setDatapoints([{ id: 20, type: T.DT.BOOL, value: false }, { id: 22, type: T.DT.VALUE, value: 1000 }]);
  await new Promise((r) => setTimeout(r, 50));
  const dpsMsg = received.find((m) => m.code === 2);
  assert.deepStrictEqual([...dpsMsg.data], [20, 1, 1, 0, 22, 2, 4, 0, 0, 3, 0xe8]);
  assert(received.some((m) => m.code === 0x8001), 'respuesta a RECEIVE_DP');
  const t = received.find((m) => m.code === 0x8011);
  assert(t && t.data.length === 15, 'respuesta a TIME1');
  console.log(logs.join('\n'));
  console.log('OK: todas las pruebas pasaron');
})().catch((e) => { console.error(e); process.exit(1); });
