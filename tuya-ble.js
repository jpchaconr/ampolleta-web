// Cliente Tuya BLE (protocolo v3) para Web Bluetooth.
// Basado en la implementación abierta de ha_tuya_ble (PlusPlus-ua/ha_tuya_ble).
(function (global) {
  'use strict';

  const SERVICE_UUID = 0x1910;
  const CHAR_NOTIFY = '00002b10-0000-1000-8000-00805f9b34fb';
  const CHAR_WRITE = '00002b11-0000-1000-8000-00805f9b34fb';
  const ADV_SERVICE_UUID = 0xa201;
  const GATT_MTU = 20;
  const RESPONSE_TIMEOUT_MS = 6000;
  const INFO_TIMEOUT_MS = 3000;
  const LOGIN_KEY_LEN = 6;

  const CODE = {
    DEVICE_INFO: 0x0000,
    PAIR: 0x0001,
    DPS: 0x0002,
    DEVICE_STATUS: 0x0003,
    UNBIND: 0x0005,
    RECEIVE_DP: 0x8001,
    RECEIVE_TIME_DP: 0x8003,
    RECEIVE_SIGN_DP: 0x8004,
    RECEIVE_SIGN_TIME_DP: 0x8005,
    TIME1_REQ: 0x8011,
    TIME2_REQ: 0x8012,
  };

  const DT = { RAW: 0, BOOL: 1, VALUE: 2, STRING: 3, ENUM: 4, BITMAP: 5 };
  const DT_NAMES = ['raw', 'bool', 'value', 'string', 'enum', 'bitmap'];

  // ---------- utilidades de bytes ----------

  const enc = new TextEncoder();
  const dec = new TextDecoder();

  function concat(...parts) {
    const len = parts.reduce((n, p) => n + p.length, 0);
    const out = new Uint8Array(len);
    let pos = 0;
    for (const p of parts) {
      out.set(p, pos);
      pos += p.length;
    }
    return out;
  }

  function toHex(bytes) {
    return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  }

  function u16be(v) {
    return new Uint8Array([(v >> 8) & 0xff, v & 0xff]);
  }

  function i32be(v) {
    const b = new Uint8Array(4);
    new DataView(b.buffer).setInt32(0, v);
    return b;
  }

  function packInt(value) {
    const out = [];
    do {
      let b = value & 0x7f;
      value >>>= 7;
      if (value !== 0) b |= 0x80;
      out.push(b);
    } while (value !== 0);
    return new Uint8Array(out);
  }

  function unpackInt(data, pos) {
    let result = 0;
    for (let offset = 0; offset < 4; offset++) {
      if (pos + offset >= data.length) throw new Error('varint truncado');
      const b = data[pos + offset];
      result |= (b & 0x7f) << (offset * 7);
      if ((b & 0x80) === 0) return [result, pos + offset + 1];
    }
    throw new Error('varint inválido');
  }

  function crc16(data) {
    let crc = 0xffff;
    for (const byte of data) {
      crc ^= byte;
      for (let i = 0; i < 8; i++) {
        const tmp = crc & 1;
        crc >>= 1;
        if (tmp) crc ^= 0xa001;
      }
    }
    return crc;
  }

  // ---------- MD5 (WebCrypto no lo trae) ----------

  function md5(bytes) {
    const K = new Int32Array(64);
    for (let i = 0; i < 64; i++) K[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 2 ** 32) | 0;
    const S = [7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21];

    const len = bytes.length;
    const padLen = (((len + 8) >> 6) + 1) << 6;
    const buf = new Uint8Array(padLen);
    buf.set(bytes);
    buf[len] = 0x80;
    const dv = new DataView(buf.buffer);
    dv.setUint32(padLen - 8, (len * 8) >>> 0, true);
    dv.setUint32(padLen - 4, Math.floor(len / 0x20000000), true);

    let a0 = 0x67452301, b0 = 0xefcdab89 | 0, c0 = 0x98badcfe | 0, d0 = 0x10325476;
    const M = new Int32Array(16);
    for (let off = 0; off < padLen; off += 64) {
      for (let i = 0; i < 16; i++) M[i] = dv.getInt32(off + i * 4, true);
      let A = a0, B = b0, C = c0, D = d0;
      for (let i = 0; i < 64; i++) {
        let F, g;
        if (i < 16) { F = (B & C) | (~B & D); g = i; }
        else if (i < 32) { F = (D & B) | (~D & C); g = (5 * i + 1) % 16; }
        else if (i < 48) { F = B ^ C ^ D; g = (3 * i + 5) % 16; }
        else { F = C ^ (B | ~D); g = (7 * i) % 16; }
        const s = S[(i >> 4) * 4 + (i % 4)];
        const sum = (A + F + K[i] + M[g]) | 0;
        A = D; D = C; C = B;
        B = (B + ((sum << s) | (sum >>> (32 - s)))) | 0;
      }
      a0 = (a0 + A) | 0; b0 = (b0 + B) | 0; c0 = (c0 + C) | 0; d0 = (d0 + D) | 0;
    }
    const out = new Uint8Array(16);
    const odv = new DataView(out.buffer);
    odv.setInt32(0, a0, true); odv.setInt32(4, b0, true);
    odv.setInt32(8, c0, true); odv.setInt32(12, d0, true);
    return out;
  }

  // ---------- AES-128-CBC sin padding sobre WebCrypto ----------
  // WebCrypto siempre usa PKCS#7, así que se recorta o se agrega el bloque de relleno a mano.

  function importKey(raw) {
    return crypto.subtle.importKey('raw', raw, { name: 'AES-CBC' }, false, ['encrypt', 'decrypt']);
  }

  async function aesEncryptNoPad(keyRaw, iv, data) {
    const key = await importKey(keyRaw);
    const out = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-CBC', iv }, key, data));
    return out.slice(0, data.length);
  }

  async function aesDecryptNoPad(keyRaw, iv, data) {
    const key = await importKey(keyRaw);
    const prev = data.length ? data.slice(data.length - 16) : iv;
    const padBlock = new Uint8Array(16).fill(16);
    const extra = new Uint8Array(
      await crypto.subtle.encrypt({ name: 'AES-CBC', iv: prev }, key, padBlock)
    ).slice(0, 16);
    return new Uint8Array(
      await crypto.subtle.decrypt({ name: 'AES-CBC', iv }, key, concat(data, extra))
    );
  }

  // ---------- datapoints ----------

  function encodeDpValue(type, value) {
    switch (type) {
      case DT.BOOL: return new Uint8Array([value ? 1 : 0]);
      case DT.VALUE: return i32be(value);
      case DT.ENUM:
        if (value > 0xffff) return i32be(value);
        if (value > 0xff) return u16be(value);
        return new Uint8Array([value]);
      case DT.STRING: return enc.encode(value);
      default: return value instanceof Uint8Array ? value : new Uint8Array(value);
    }
  }

  function decodeDpValue(type, raw) {
    switch (type) {
      case DT.BOOL: return raw.some((b) => b !== 0);
      case DT.VALUE:
      case DT.ENUM: {
        let v = 0;
        for (const b of raw) v = v * 256 + b;
        if (raw.length && raw[0] & 0x80) v -= 2 ** (8 * raw.length);
        return v;
      }
      case DT.STRING: return dec.decode(raw);
      default: return raw;
    }
  }

  function parseDatapoints(data, pos) {
    const dps = [];
    while (data.length - pos >= 4) {
      const id = data[pos];
      const type = data[pos + 1];
      const len = data[pos + 2];
      pos += 3;
      if (type > DT.BITMAP || pos + len > data.length) throw new Error('datapoint inválido');
      dps.push({ id, type, value: decodeDpValue(type, data.slice(pos, pos + len)) });
      pos += len;
    }
    return dps;
  }

  function parseTimestamp(data, pos) {
    const kind = data[pos++];
    if (kind === 0) return [parseInt(dec.decode(data.slice(pos, pos + 13)), 10) / 1000, pos + 13];
    if (kind === 1) return [new DataView(data.buffer, data.byteOffset).getUint32(pos), pos + 4];
    throw new Error('timestamp inválido');
  }

  // Zona horaria en el formato de Tuya: centésimas de hora, con signo.
  function tuyaTimezone() {
    return Math.trunc((-new Date().getTimezoneOffset() * 100) / 60);
  }

  // ---------- dispositivo ----------

  class TuyaBLEDevice {
    constructor({ uuid, localKey, deviceId, protocolVersion = 3, log = () => {} }) {
      if (!uuid || !localKey || !deviceId) throw new Error('Faltan uuid, local_key o device_id');
      this.uuid = uuid.trim();
      this.deviceId = deviceId.trim();
      this.localKey = enc.encode(localKey.trim().slice(0, 6));
      this.loginKey = md5(this.localKey);
      this.sessionKey = null;
      this.protocolVersion = protocolVersion;
      this.log = log;
      this.onDatapoints = () => {};
      this.onDisconnect = () => {};
      this.datapoints = new Map();
      this._reset();
    }

    _reset() {
      this.seq = 1;
      this.pending = new Map();
      this.writeQueue = Promise.resolve();
      this.inBuf = null;
      this.inExpectedPacket = 0;
      this.inExpectedLength = 0;
      this.paired = false;
    }

    get connected() {
      return !!(this.gatt && this.gatt.connected && this.paired);
    }

    static requestDevice() {
      return navigator.bluetooth.requestDevice({
        filters: [{ services: [ADV_SERVICE_UUID] }, { namePrefix: 'TY' }],
        optionalServices: [SERVICE_UUID],
      });
    }

    async connectTo(device) {
      this.device = device;
      this._onGattDisconnect = () => {
        this.log('Desconectado');
        this._failPending(new Error('desconectado'));
        const wasPaired = this.paired;
        this.paired = false;
        if (wasPaired) this.onDisconnect();
      };
      device.addEventListener('gattserverdisconnected', this._onGattDisconnect);
      try {
        await this.connect();
      } catch (e) {
        this.release();
        throw e;
      }
    }

    // Deja de escuchar el dispositivo, para poder probar otras credenciales con él.
    release() {
      if (this.device && this._onGattDisconnect) {
        this.device.removeEventListener('gattserverdisconnected', this._onGattDisconnect);
      }
      if (this.notifyChar && this._onValue) {
        this.notifyChar.removeEventListener('characteristicvaluechanged', this._onValue);
      }
      this._failPending(new Error('cancelado'));
    }

    // Recibe la característica de escritura y notificación ya resueltas (sirve para pruebas).
    async attach(writeChar, notifyChar) {
      this._reset();
      if (this.notifyChar && this._onValue) {
        this.notifyChar.removeEventListener('characteristicvaluechanged', this._onValue);
      }
      this.writeChar = writeChar;
      this.notifyChar = notifyChar;
      this._onValue = (ev) => {
        const v = ev.target.value;
        this._onNotify(new Uint8Array(v.buffer, v.byteOffset, v.byteLength));
      };
      notifyChar.addEventListener('characteristicvaluechanged', this._onValue);
      await notifyChar.startNotifications();

      // Una ampolleta vinculada responde con la clave de login (md5 de la local_key).
      // Una sin vincular tiene la clave de login en ceros y solo responde con esa.
      this.log('Pidiendo información del dispositivo…');
      this.sessionSeed = this.localKey;
      this.loginKey = md5(this.localKey);
      try {
        await this._send(CODE.DEVICE_INFO, new Uint8Array(0), 0, INFO_TIMEOUT_MS);
      } catch (e) {
        if (this.device && !this.device.gatt.connected) throw e;
        this.log('Sin respuesta con la clave; probando como ampolleta sin vincular…');
        this.sessionSeed = new Uint8Array(LOGIN_KEY_LEN);
        this.loginKey = md5(this.sessionSeed);
        await this._send(CODE.DEVICE_INFO, new Uint8Array(0), 0, INFO_TIMEOUT_MS);
      }

      if (!this.bound) {
        // El PAIR guarda nuestra local_key en la ampolleta y la deja vinculada. Desde ese
        // momento la clave de sesión pasa a derivarse de la local_key.
        this.log('La ampolleta no está vinculada: vinculándola con tu local_key…');
        this.nextSessionKey = md5(concat(this.localKey, this.srand));
      } else {
        this.log('Emparejando…');
      }
      await this._send(CODE.PAIR, this._pairingRequest());
      if (this.nextSessionKey) {
        this.sessionKey = this.nextSessionKey;
        this.nextSessionKey = null;
        this.loginKey = md5(this.localKey);
        this.log('Ampolleta vinculada');
      }
      this.paired = true;
      this.log('Conectado y emparejado');
      await this._send(CODE.DEVICE_STATUS, new Uint8Array(0));
    }

    // En Android la conexión BLE a veces se cae apenas se establece; se reintenta
    // mientras el error sea de enlace (desconexión) y no de protocolo o de clave.
    async connect(attempts = 5) {
      for (let i = 1; ; i++) {
        try {
          this.log(`Conectando a ${this.device.name || 'ampolleta'} (intento ${i} de ${attempts})…`);
          await this._connectOnce();
          return;
        } catch (e) {
          const linkLost = !this.device.gatt.connected;
          if (!linkLost || i >= attempts) throw e;
          this.log(`Se cortó la conexión (${e.message}), reintentando…`);
          await new Promise((r) => setTimeout(r, 600 * i));
        }
      }
    }

    async _connectOnce() {
      this.gatt = this.device.gatt.connected ? this.device.gatt : await this.device.gatt.connect();
      const service = await this.gatt.getPrimaryService(SERVICE_UUID);
      const notify = await service.getCharacteristic(CHAR_NOTIFY);
      const write = await service.getCharacteristic(CHAR_WRITE);
      const pr = write.properties || {};
      this.log(`Característica de escritura: ${pr.write ? 'write ' : ''}${pr.writeWithoutResponse ? 'writeWithoutResponse' : ''}`);
      await this.attach(write, notify);
    }

    // Borra la vinculación guardada en la ampolleta y la desconecta. Queda lista para
    // agregarla de nuevo (aquí, en la app Android o en Smart Life).
    async unbind() {
      await this._send(CODE.UNBIND, new Uint8Array(0));
      this.paired = false;
      this.log('Ampolleta desvinculada');
      this.disconnect();
    }

    disconnect() {
      if (this.gatt && this.gatt.connected) this.gatt.disconnect();
    }

    async setDatapoints(dps) {
      const parts = [];
      for (const { id, type, value } of dps) {
        const v = encodeDpValue(type, value);
        parts.push(new Uint8Array([id, type, v.length]), v);
      }
      await this._send(CODE.DPS, concat(...parts));
    }

    _pairingRequest() {
      const out = new Uint8Array(44);
      out.set(concat(enc.encode(this.uuid), this.localKey, enc.encode(this.deviceId)).slice(0, 44));
      return out;
    }

    async _buildPackets(seq, code, data, responseTo) {
      const isInfo = code === CODE.DEVICE_INFO;
      const key = isInfo ? this.loginKey : this.sessionKey;
      const flag = isInfo ? 4 : 5;

      const header = new Uint8Array(12);
      const hv = new DataView(header.buffer);
      hv.setUint32(0, seq);
      hv.setUint32(4, responseTo);
      hv.setUint16(8, code);
      hv.setUint16(10, data.length);
      let raw = concat(header, data);
      raw = concat(raw, u16be(crc16(raw)));
      const padded = new Uint8Array(Math.ceil(raw.length / 16) * 16);
      padded.set(raw);

      const iv = crypto.getRandomValues(new Uint8Array(16));
      const encrypted = concat(new Uint8Array([flag]), iv, await aesEncryptNoPad(key, iv, padded));

      const packets = [];
      let pos = 0;
      for (let n = 0; pos < encrypted.length; n++) {
        let head = packInt(n);
        if (n === 0) head = concat(head, packInt(encrypted.length), new Uint8Array([this.protocolVersion << 4]));
        const part = encrypted.slice(pos, pos + GATT_MTU - head.length);
        packets.push(concat(head, part));
        pos += part.length;
      }
      return packets;
    }

    _send(code, data, responseTo = 0, timeoutMs = RESPONSE_TIMEOUT_MS) {
      const seq = this.seq++;
      const waitForResponse = responseTo === 0;
      let result = null;
      if (waitForResponse) {
        result = new Promise((resolve, reject) => {
          const timer = setTimeout(() => {
            this.pending.delete(seq);
            reject(new Error(`Sin respuesta al comando 0x${code.toString(16)}`));
          }, timeoutMs);
          this.pending.set(seq, { resolve, reject, timer, code });
        });
      }
      const write = this.writeQueue.then(async () => {
        const packets = await this._buildPackets(seq, code, data, responseTo);
        for (const p of packets) {
          if (!this.paired) this.log(`TX ${toHex(p)}`);
          // Algunas ampolletas solo aceptan escritura con respuesta.
          const props = this.writeChar.properties;
          if (props && !props.writeWithoutResponse && props.write) await this.writeChar.writeValueWithResponse(p);
          else await this.writeChar.writeValueWithoutResponse(p);
        }
      });
      this.writeQueue = write.catch(() => {});
      return waitForResponse ? write.then(() => result) : write;
    }

    _failPending(err) {
      for (const { reject, timer } of this.pending.values()) {
        clearTimeout(timer);
        reject(err);
      }
      this.pending.clear();
    }

    _keyFor(flag) {
      if (flag === 4) return this.loginKey;
      if (flag === 5) return this.sessionKey;
      throw new Error(`Bandera de seguridad desconocida: ${flag}`);
    }

    _onNotify(data) {
      if (!this.paired) this.log(`RX ${toHex(data)}`);
      try {
        let [packetNum, pos] = unpackInt(data, 0);
        if (packetNum !== this.inExpectedPacket) {
          this.log(`Paquete fuera de orden (${packetNum}), se descarta el mensaje`);
          this.inBuf = null;
          this.inExpectedPacket = 0;
          if (packetNum !== 0) return;
        }
        if (packetNum === 0) {
          [this.inExpectedLength, pos] = unpackInt(data, pos);
          pos += 1; // versión de protocolo
          this.inBuf = new Uint8Array(0);
        }
        this.inBuf = concat(this.inBuf, data.slice(pos));
        this.inExpectedPacket++;
        if (this.inBuf.length >= this.inExpectedLength) {
          const buf = this.inBuf.slice(0, this.inExpectedLength);
          this.inBuf = null;
          this.inExpectedPacket = 0;
          this._parseInput(buf).catch((e) => {
            this.log(`Error al leer respuesta: ${e.message}`);
            // Durante el handshake un error de descifrado casi siempre significa credenciales equivocadas.
            if (!this.paired) this._failPending(e);
          });
        }
      } catch (e) {
        this.log(`Error en notificación: ${e.message}`);
        this.inBuf = null;
        this.inExpectedPacket = 0;
      }
    }

    async _parseInput(buf) {
      // Mientras se vincula, la respuesta al PAIR puede venir con la clave de sesión nueva.
      const keys = [this._keyFor(buf[0])];
      if (buf[0] === 5 && this.nextSessionKey) keys.push(this.nextSessionKey);
      let lastError;
      for (const key of keys) {
        try {
          const raw = await aesDecryptNoPad(key, buf.slice(1, 17), buf.slice(17));
          const dv = new DataView(raw.buffer);
          const length = dv.getUint16(10);
          const end = 12 + length;
          if (raw.length < end) throw new Error('longitud inválida');
          if (raw.length >= end + 2 && crc16(raw.slice(0, end)) !== dv.getUint16(end)) {
            throw new Error('CRC inválido');
          }
          await this._handle(dv.getUint32(0), dv.getUint32(4), dv.getUint16(8), raw.slice(12, end));
          return;
        } catch (e) {
          lastError = e;
        }
      }
      throw lastError;
    }

    async _handle(seq, responseTo, code, data) {
      let result = 0;
      switch (code) {
        case CODE.DEVICE_INFO: {
          if (data.length < 46) throw new Error('DEVICE_INFO muy corto');
          this.protocolVersion = data[2];
          this.deviceVersion = `${data[0]}.${data[1]}`;
          this.log(`Firmware ${this.deviceVersion}, protocolo ${data[2]}.${data[3]}`);
          this.bound = data[5] !== 0;
          this.srand = data.slice(6, 12);
          this.sessionKey = md5(concat(this.sessionSeed || this.localKey, this.srand));
          break;
        }
        case CODE.PAIR:
          result = data[0] === 2 ? 0 : data[0];
          break;
        case CODE.DEVICE_STATUS:
        case CODE.UNBIND:
        case CODE.DPS:
          result = data.length ? data[0] : 0;
          break;
        case CODE.RECEIVE_DP:
          this._gotDatapoints(parseDatapoints(data, 0));
          await this._send(code, new Uint8Array(0), seq);
          break;
        case CODE.RECEIVE_TIME_DP: {
          const [, pos] = parseTimestamp(data, 0);
          this._gotDatapoints(parseDatapoints(data, pos));
          await this._send(code, new Uint8Array(0), seq);
          break;
        }
        case CODE.RECEIVE_SIGN_DP:
        case CODE.RECEIVE_SIGN_TIME_DP: {
          let pos = 3;
          if (code === CODE.RECEIVE_SIGN_TIME_DP) [, pos] = parseTimestamp(data, 3);
          this._gotDatapoints(parseDatapoints(data, pos));
          await this._send(code, concat(data.slice(0, 3), new Uint8Array([0])), seq);
          break;
        }
        case CODE.TIME1_REQ: {
          const tz = new Uint8Array(2);
          new DataView(tz.buffer).setInt16(0, tuyaTimezone());
          await this._send(code, concat(enc.encode(String(Date.now())), tz), seq);
          break;
        }
        case CODE.TIME2_REQ: {
          const d = new Date();
          const out = new Uint8Array(9);
          out.set([d.getFullYear() % 100, d.getMonth() + 1, d.getDate(), d.getHours(),
            d.getMinutes(), d.getSeconds(), (d.getDay() + 6) % 7]);
          new DataView(out.buffer).setInt16(7, tuyaTimezone());
          await this._send(code, out, seq);
          break;
        }
        default:
          this.log(`Mensaje desconocido 0x${code.toString(16)}: ${toHex(data)}`);
      }

      if (responseTo !== 0) {
        const p = this.pending.get(responseTo);
        if (p) {
          this.pending.delete(responseTo);
          clearTimeout(p.timer);
          if (result === 0) p.resolve(data);
          else p.reject(new Error(`La ampolleta respondió con error ${result} al comando 0x${p.code.toString(16)}`));
        }
      }
    }

    _gotDatapoints(dps) {
      for (const dp of dps) this.datapoints.set(dp.id, dp);
      this.onDatapoints(dps);
    }
  }

  const api = { TuyaBLEDevice, DT, DT_NAMES, CODE, md5, crc16, toHex, aesEncryptNoPad, aesDecryptNoPad, concat, packInt, unpackInt };
  global.TuyaBLE = api;
  if (typeof module !== 'undefined') module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
