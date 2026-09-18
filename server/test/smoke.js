'use strict';

/**
 * Smoke test end-to-end MikuRemote VPS (Phase 1 + 2):
 * register -> login -> pairing code -> device connect (WS) ->
 * controller connect (WS) -> device list -> torch command relay ->
 * device_info -> heartbeat -> screenshot request -> revoke device.
 *
 * Jalankan server dulu di port lain dengan DB memory:
 *   JWT_SECRET=test DB_FILE=:memory: PORT=8791 node src/index.js
 * Lalu:
 *   PORT=8791 node test/smoke.js
 */

const WebSocket = require('ws');

const BASE = `http://127.0.0.1:${process.env.PORT || 8791}`;
const WS_URL = `ws://127.0.0.1:${process.env.PORT || 8791}/ws`;

let failures = 0;

function assert(cond, label) {
  if (cond) {
    console.log(`  ok   ${label}`);
  } else {
    failures += 1;
    console.error(`  FAIL ${label}`);
  }
}

async function api(method, path, { token, body } = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: {
      'content-type': 'application/json',
      ...(token ? { authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, json: await res.json().catch(() => ({})) };
}

// Buffer pesan per-socket: pesan yang datang di antara dua pemanggilan
// wsNext() tidak hilang (dulu: race -- device_status yang datang di chunk
// yang sama dengan auth_ok terlewat karena listener baru terpasang setelahnya).
const wsBuffers = new WeakMap();
function bufferOf(ws) {
  let buf = wsBuffers.get(ws);
  if (!buf) {
    buf = [];
    ws.on('message', (data, isBinary) => {
      if (isBinary) { buf.push({ binary: true, data }); return; }
      try { buf.push({ msg: JSON.parse(data.toString('utf8')) }); } catch { /* abaikan */ }
    });
    wsBuffers.set(ws, buf);
  }
  return buf;
}

function wsNext(ws, timeoutMs = 4000) {
  const buf = bufferOf(ws);
  return new Promise((resolve, reject) => {
    const item = buf.shift();
    if (item) {
      return item.binary ? resolve(item) : resolve(item.msg);
    }
    const timer = setTimeout(() => reject(new Error('timeout menunggu pesan WS')), timeoutMs);
    const onMsg = () => {
      const it = buf.shift();
      if (it) {
        clearTimeout(timer);
        ws.off('message', onMsg);
        it.binary ? resolve(it) : resolve(it.msg);
      }
    };
    ws.on('message', onMsg);
  });
}

async function main() {
  const rnd = Math.random().toString(36).slice(2, 8);
  const username = 'tester_' + rnd;
  const password = 'password123';

  console.log('== Auth ==');
  const reg = await api('POST', '/api/auth/register', { body: { username, password } });
  assert(reg.status === 201 && reg.json.token, `register (${username})`);

  const dup = await api('POST', '/api/auth/register', { body: { username, password } });
  assert(dup.status === 409, 'register duplikat ditolak (409)');

  const bad = await api('POST', '/api/auth/login', { body: { username, password: 'wrongpass99' } });
  assert(bad.status === 401, 'login password salah ditolak (401)');

  const login = await api('POST', '/api/auth/login', { body: { username, password } });
  assert(login.status === 200 && login.json.token, 'login ok');
  const userToken = login.json.token;

  console.log('== Pairing ==');
  const pc = await api('POST', '/api/pairing-code', {
    token: userToken, body: { deviceName: 'Server Rumah' },
  });
  assert(pc.status === 201 && /^\d{3} \d{3}$/.test(pc.json.code), `pairing code dibuat: ${pc.json.code}`);

  const badPair = await api('POST', '/api/pairing-code', {
    token: userToken, body: { deviceName: '' },
  });
  assert(badPair.status === 400, 'pairing tanpa nama device ditolak (400)');

  console.log('== WebSocket: device ==');
  const devWs = new WebSocket(WS_URL);
  await new Promise((r) => devWs.once('open', r));
  devWs.send(JSON.stringify({ type: 'auth_device', token: 'mrt_invalid' }));
  const devErr = await wsNext(devWs);
  assert(devErr.type === 'auth_error', 'device token invalid ditolak');

  const devWs2 = new WebSocket(WS_URL);
  await new Promise((r) => devWs2.once('open', r));
  // Tukar pairing code dengan device token lewat REST "exchange" (diuji di bawah).
  // Untuk smoke ini, pakai endpoint exchange:
  const ex = await api('POST', '/api/pairing-exchange', { body: { code: pc.json.code } });
  assert(ex.status === 200 && ex.json.deviceId && ex.json.deviceToken, 'pairing exchange ok');
  const deviceId = ex.json.deviceId;
  const deviceToken = ex.json.deviceToken;

  devWs2.send(JSON.stringify({ type: 'auth_device', token: deviceToken }));
  const devOk = await wsNext(devWs2);
  assert(devOk.type === 'auth_ok' && devOk.role === 'device', 'device auth ok');

  console.log('== WebSocket: controller ==');
  const ctlWs = new WebSocket(WS_URL);
  await new Promise((r) => ctlWs.once('open', r));
  ctlWs.send(JSON.stringify({ type: 'auth_controller', token: userToken }));
  const ctlOk = await wsNext(ctlWs);
  assert(ctlOk.type === 'auth_ok' && ctlOk.role === 'controller', 'controller auth ok');

  const statusMsg = await wsNext(ctlWs);
  assert(statusMsg.type === 'device_status' && statusMsg.online === true, 'controller terima device_status online');

  console.log('== Device list ==');
  const list = await api('GET', '/api/devices', { token: userToken });
  assert(list.status === 200 && list.json.devices.length === 1 && list.json.devices[0].online === true,
    'GET /api/devices: 1 device online');

  console.log('== Command relay (torch) ==');
  ctlWs.send(JSON.stringify({ type: 'device_command', deviceId, command: 'TORCH_ON' }));
  const cmdAtDevice = await wsNext(devWs2);
  assert(cmdAtDevice.type === 'command' && cmdAtDevice.command === 'TORCH_ON', 'device terima command TORCH_ON');

  devWs2.send(JSON.stringify({
    type: 'command_result', command: 'TORCH_ON', success: true, state: { torch: true },
  }));
  const resultAtCtl = await wsNext(ctlWs);
  assert(resultAtCtl.type === 'command_result' && resultAtCmdOk(resultAtCtl), 'controller terima command_result');

  ctlWs.send(JSON.stringify({ type: 'device_command', deviceId, command: 'EXEC_SOMETHING' }));
  const err1 = await wsNext(ctlWs);
  assert(err1.type === 'error', 'command tidak dikenal ditolak');

  console.log('== device_info & log relay ==');
  devWs2.send(JSON.stringify({ type: 'device_info', info: { batteryPct: 87, charging: false } }));
  const infoAtCtl = await wsNext(ctlWs);
  assert(infoAtCtl.type === 'device_info' && infoAtCtl.info.batteryPct === 87, 'device_info direlay ke controller');

  devWs2.send(JSON.stringify({ type: 'log_entry', entry: { ts: Date.now(), level: 'INFO', message: 'Torch enabled' } }));
  const logAtCtl = await wsNext(ctlWs);
  assert(logAtCtl.type === 'log_entry' && logAtCtl.entry.message === 'Torch enabled', 'log direlay ke controller');

  console.log('== Heartbeat ==');
  devWs2.send(JSON.stringify({ type: 'heartbeat' }));
  const hb = await wsNext(devWs2);
  assert(hb.type === 'heartbeat_ack', 'heartbeat_ack diterima device');

  console.log('== Screen (control channel) ==');
  ctlWs.send(JSON.stringify({ type: 'screen_start', deviceId, width: 9999, fps: 999 }));
  const ss = await wsNext(devWs2);
  assert(ss.type === 'screen_start' && ss.width === 1920 && ss.fps === 30,
    `screen_start dikirim ke device (clamp: ${ss.width}x${ss.height}@${ss.fps})`);

  // Binary relay device -> controller: device kirim screen_meta, controller terima
  devWs2.send(JSON.stringify({ type: 'screen_meta', deviceId, width: 1280, height: 720, fps: 12 }));
  const metaAtCtl = await wsNext(ctlWs);
  assert(metaAtCtl.type === 'screen_meta' && metaAtCtl.width === 1280, 'screen_meta direlay ke controller');

  devWs2.send(Buffer.from([0x00, 0x00, 0x00, 0x01, 0x67]), true);
  const bin = await wsNext(ctlWs);
  assert(bin.binary === true && bin.data.length === 5, 'frame binary direlay device -> controller');

  ctlWs.send(JSON.stringify({ type: 'screen_stop', deviceId }));
  const stopAtDev = await wsNext(devWs2);
  assert(stopAtDev.type === 'screen_stop', 'screen_stop diterima device');

  console.log('== Revoke device ==');
  const del = await api('DELETE', `/api/devices/${deviceId}`, { token: userToken });
  assert(del.status === 200, 'DELETE device ok');
  const closed = await Promise.race([
    new Promise((r) => devWs2.once('close', r)),
    new Promise((r) => setTimeout(() => r('timeout'), 3000)),
  ]);
  assert(closed !== 'timeout', 'socket device ditutup setelah revoke');

  devWs.close(); devWs2.close(); ctlWs.close();

  console.log(failures === 0 ? '\nSEMUA TES LULUS ✔' : `\n${failures} TES GAGAL ✘`);
  process.exit(failures === 0 ? 0 : 1);
}

function resultAtCmdOk(msg) {
  return msg.success === true && msg.state && msg.state.torch === true;
}

main().catch((e) => {
  console.error('Smoke test error:', e.message);
  process.exit(1);
});
