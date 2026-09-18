'use strict';

/**
 * WebSocket relay MikuRemote.
 *
 * Semua klien (device server & controller) masuk lewat satu endpoint: /ws
 * Setiap koneksi wajib mengirim pesan auth pertama:
 *   - device:     { type: "auth_device", token: "<device token>" }
 *   - controller: { type: "auth_controller", token: "<JWT user>" }
 *
 * VPS HANYA me-relay: tidak menyentuh isi video, tidak menyimpan frame.
 * Control message = JSON (TEXT frame). Video/screenshot = BINARY frame.
 */

const { WebSocketServer } = require('ws');
const config = require('../config');
const jwt = require('../auth/jwt');
const store = require('../devices/store');
const { getDb } = require('../database/db');
const { hit } = require('../auth/rate-limit');

const wss = new WebSocketServer({ noServer: true, maxPayload: config.maxWsMessageBytes });

/** @type {Map<string, Set<WebSocket>>} deviceId -> device sockets */
const deviceSockets = new Map();
/** @type {Map<number, Set<WebSocket>>} userId -> controller sockets */
const controllerSockets = new Map();
/** @type {Map<WebSocket, Set<string>>} controller ws -> deviceId yang di-stream */
const controllerStreams = new Map();
/** @type {Map<WebSocket, Set<string>>} controller ws -> deviceId yang menunggu screenshot */
const controllerShots = new Map();
/** @type {Map<WebSocket, Set<string>>} controller ws -> deviceId yang menunggu frame kamera */
const controllerCameras = new Map();
/** @type {Map<string, Map<WebSocket, NodeJS.Timeout>>} deviceId -> (controller ws -> timeout no-frame) */
const deviceCameraWatchdogs = new Map();

let httpServer = null;

function safeSend(ws, obj) {
  if (ws && ws.readyState === 1) {
    try { ws.send(JSON.stringify(obj)); } catch { /* koneksi sedang mati */ }
  }
}

function controllersOfUser(userId) {
  return controllerSockets.get(userId) || new Set();
}

function notifyControllers(userId, payload) {
  for (const ws of controllersOfUser(userId)) safeSend(ws, payload);
}

// ---------------------------------------------------------------------------
// Attachment ke HTTP server
// ---------------------------------------------------------------------------

function attach(server) {
  httpServer = server;
  server.on('upgrade', (req, socket, head) => {
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname !== '/ws') {
      socket.write('HTTP/1.1 404 Not Found\r\n\r\n');
      socket.destroy();
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => {
      ws.remoteIp = req.socket.remoteAddress || 'unknown';
      wss.emit('connection', ws, req);
    });
  });
}

// ---------------------------------------------------------------------------
// Lifecycle koneksi
// ---------------------------------------------------------------------------

wss.on('connection', (ws) => {
  ws.isAlive = true;
  ws.authenticated = false;
  ws.role = null;              // 'device' | 'controller'
  ws.deviceId = null;
  ws.userId = null;
  ws.sessionRowId = null;
  ws.msgBudget = { windowStart: Date.now(), count: 0 };
  ws.lastTraffic = Date.now();

  // Ping/pong protocol-level (mis. dari OkHttp pingInterval) dihitung sebagai
  // aktivitas: koneksi sehat tidak boleh dibunuh oleh idle timeout.
  ws.on('pong', () => {
    ws.isAlive = true;
    ws.lastTraffic = Date.now();
  });

  ws.on('message', (data, isBinary) => {
    ws.lastTraffic = Date.now();

    // Batas pesan per 5 detik per koneksi (anti flood).
    const now = Date.now();
    if (now - ws.msgBudget.windowStart > 5000) {
      ws.msgBudget.windowStart = now;
      ws.msgBudget.count = 0;
    }
    ws.msgBudget.count += 1;
    if (ws.msgBudget.count > 300) {
      ws.close(1008, 'rate_limited');
      return;
    }

    // Frame binary hanya berarti apa-apa setelah device authenticated.
    if (isBinary) {
      if (!ws.authenticated || ws.role !== 'device') return;
      if (data.length > config.maxBinaryFrameBytes) return; // buang frame kebesaran
      relayBinaryFromDevice(ws, data);
      return;
    }

    let msg;
    try { msg = JSON.parse(data.toString('utf8')); } catch { return; }
    if (!msg || typeof msg.type !== 'string' || msg.type.length > 64) return;

    if (!ws.authenticated) return handleAuth(ws, msg);
    if (ws.role === 'device') return handleDeviceMessage(ws, msg);
    return handleControllerMessage(ws, msg);
  });

  ws.on('close', () => teardown(ws));
  ws.on('error', () => { try { ws.terminate(); } catch { /* noop */ } });
});

// ---------------------------------------------------------------------------
// Auth
// ---------------------------------------------------------------------------

function handleAuth(ws, msg) {
  if (msg.type === 'auth_device') {
    const device = store.getDeviceByToken(msg.token);
    if (!device) {
      safeSend(ws, { type: 'auth_error', error: 'Device token tidak valid' });
      return ws.close(4001, 'invalid_device_token');
    }
    ws.authenticated = true;
    ws.role = 'device';
    ws.deviceId = device.id;

    if (!deviceSockets.has(device.id)) deviceSockets.set(device.id, new Set());
    deviceSockets.get(device.id).add(ws);

    // Session row + status online.
    const db = getDb();
    const info = db.prepare("INSERT INTO sessions (device_id, type, last_heartbeat) VALUES (?, 'device', ?)")
      .run(device.id, new Date().toISOString());
    ws.sessionRowId = Number(info.lastInsertRowid);
    store.touchSeen(device.id, 'online');

    safeSend(ws, { type: 'auth_ok', role: 'device', deviceId: device.id, deviceName: device.device_name });
    notifyControllers(device.user_id, { type: 'device_status', deviceId: device.id, online: true });
    logLine('info', `Device online: ${device.device_name} (${device.id})`);
    return;
  }

  if (msg.type === 'auth_controller') {
    const payload = jwt.verify(msg.token);
    if (!payload || typeof payload.sub !== 'string' || !payload.sub.startsWith('user:')) {
      safeSend(ws, { type: 'auth_error', error: 'Token tidak valid' });
      return ws.close(4001, 'invalid_token');
    }
    ws.authenticated = true;
    ws.role = 'controller';
    ws.userId = Number(payload.sub.slice(5));

    if (!controllerSockets.has(ws.userId)) controllerSockets.set(ws.userId, new Set());
    controllerSockets.get(ws.userId).add(ws);
    controllerStreams.set(ws, new Set());
    controllerCameras.set(ws, new Set());

    safeSend(ws, { type: 'auth_ok', role: 'controller', username: payload.username || null });
    // Snapshot status device milik user ini supaya UI langsung tahu mana yang online
    // (penting juga saat controller reconnect).
    for (const d of store.listDevices(ws.userId)) {
      if (isDeviceOnline(d.id)) {
        safeSend(ws, { type: 'device_status', deviceId: d.id, online: true });
      }
    }
    return;
  }

  ws.close(4002, 'auth_required');
}

// ---------------------------------------------------------------------------
// Pesan dari DEVICE (server APK)
// ---------------------------------------------------------------------------

function handleDeviceMessage(ws, msg) {
  const db = getDb();
  const device = store.getDevice(ws.deviceId);
  if (!device) return ws.close(4003, 'device_removed');

  switch (msg.type) {
    case 'heartbeat': {
      db.prepare('UPDATE sessions SET last_heartbeat = ? WHERE id = ?')
        .run(new Date().toISOString(), ws.sessionRowId);
      store.touchSeen(ws.deviceId, 'online');
      safeSend(ws, { type: 'heartbeat_ack', ts: Date.now() });
      return;
    }

    case 'device_info': {
      // Simpan cache terakhir di memori dan teruskan ke controller milik owner.
      lastDeviceInfo.set(ws.deviceId, { info: msg.info || {}, ts: Date.now() });
      notifyControllers(device.user_id, {
        type: 'device_info', deviceId: ws.deviceId, info: msg.info || {},
      });
      // Warning threshold guard dievaluasi di VPS -> event RESOURCE_WARNING
      // ke controller (bisa direlay ke log). Tidak menyimpan apa pun selain
      // cache info terakhir yang sudah ada.
      return;
    }

    case 'guard_config_get': {
      const cfg = store.getGuardConfig(ws.deviceId);
      safeSend(ws, { type: 'guard_config', config: cfg });
      return;
    }

    case 'guard_config_synced': {
      notifyControllers(device.user_id, { type: 'guard_config_synced', deviceId: ws.deviceId });
      return;
    }

    case 'device_state': {
      // Status lifecycle device: starting | online | reconnecting.
      // starting = boot selesai, service jalan, belum authenticated WS.
      // online   = authenticated di WS. reconnecting = koneksi hilang.
      const state = ['starting', 'online', 'reconnecting'].includes(msg.state) ? msg.state : null;
      if (!state) return;
      lastDeviceState.set(ws.deviceId, { state, ts: Date.now() });
      notifyControllers(device.user_id, { type: 'device_state', deviceId: ws.deviceId, state });
      return;
    }

    case 'command_result': {
      notifyControllers(device.user_id, {
        type: 'command_result',
        deviceId: ws.deviceId,
        command: String(msg.command || '').slice(0, 64),
        cmdId: msg.cmdId ?? null,
        success: !!msg.success,
        error: typeof msg.error === 'string' ? msg.error.slice(0, 300) : undefined,
        state: sanitizeState(msg.state),
      });
      return;
    }

    case 'log_entry': {
      notifyControllers(device.user_id, {
        type: 'log_entry',
        deviceId: ws.deviceId,
        entry: sanitizeLogEntry(msg.entry),
      });
      return;
    }

    case 'log_entries': {
      if (!Array.isArray(msg.entries)) return;
      const entries = msg.entries.slice(0, 200).map(sanitizeLogEntry).filter(Boolean);
      notifyControllers(device.user_id, { type: 'log_entries', deviceId: ws.deviceId, entries });
      return;
    }

    case 'screen_meta': {
      // { width, height, fps, bitrate, codec }
      notifyControllers(device.user_id, {
        type: 'screen_meta', deviceId: ws.deviceId,
        width: Number(msg.width) || 0, height: Number(msg.height) || 0,
        fps: Number(msg.fps) || 0, codec: String(msg.codec || 'h264').slice(0, 16),
      });
      return;
    }

    case 'screen_stopped': {
      // Server menghentikan stream dari sisinya; bersihkan penanda di controller.
      for (const set of controllerStreams.values()) set.delete(ws.deviceId);
      for (const set of controllerShots.values()) set.delete(ws.deviceId);
      notifyControllers(device.user_id, { type: 'screen_stopped', deviceId: ws.deviceId });
      return;
    }

    case 'screen_error': {
      // Gagal capture (mis. consent ditolak) — sampai ke controller sebagai snack.
      notifyControllers(device.user_id, {
        type: 'screen_error', deviceId: ws.deviceId,
        error: String(msg.error || 'Screen capture gagal').slice(0, 200),
      });
      return;
    }

    case 'camera_meta': {
      notifyControllers(device.user_id, {
        type: 'camera_meta', deviceId: ws.deviceId,
        width: Number(msg.width) || 0, height: Number(msg.height) || 0,
        fps: Number(msg.fps) || 0, codec: String(msg.codec || 'h264').slice(0, 16),
      });
      return;
    }

    case 'camera_stopped': {
      for (const set of controllerCameras.values()) set.delete(ws.deviceId);
      const wdMap = deviceCameraWatchdogs.get(ws.deviceId);
      if (wdMap) {
        for (const t of wdMap.values()) clearTimeout(t);
        deviceCameraWatchdogs.delete(ws.deviceId);
      }
      notifyControllers(device.user_id, { type: 'camera_stopped', deviceId: ws.deviceId });
      return;
    }

    case 'camera_error': {
      for (const set of controllerCameras.values()) set.delete(ws.deviceId);
      const wdMap2 = deviceCameraWatchdogs.get(ws.deviceId);
      if (wdMap2) {
        for (const t of wdMap2.values()) clearTimeout(t);
        deviceCameraWatchdogs.delete(ws.deviceId);
      }
      notifyControllers(device.user_id, {
        type: 'camera_error', deviceId: ws.deviceId,
        error: String(msg.error || 'Camera gagal').slice(0, 200),
      });
      return;
    }

    default:
      // Type tak dikenal diabaikan (jangan crash).
      return;
  }
}

function sanitizeLogEntry(entry) {
  if (!entry || typeof entry !== 'object') return null;
  const ts = typeof entry.ts === 'number' ? entry.ts : Date.now();
  const level = ['INFO', 'WARN', 'ERROR'].includes(entry.level) ? entry.level : 'INFO';
  const message = String(entry.message || '').slice(0, 500);
  return { ts, level, message };
}

function sanitizeState(state) {
  if (!state || typeof state !== 'object') return undefined;
  const out = {};
  if (typeof state.torch === 'boolean') out.torch = state.torch;
  if (typeof state.serverMode === 'boolean') out.serverMode = state.serverMode;
  return out;
}

const lastDeviceInfo = new Map(); // deviceId -> {info, ts}
const lastDeviceState = new Map(); // deviceId -> {state, ts} (starting/online/reconnecting)

// ---------------------------------------------------------------------------
// Pesan dari CONTROLLER
// ---------------------------------------------------------------------------

// Command yang diizinkan di V1 (tanpa shell, tanpa ADB, tanpa arbitrary exec).
const ALLOWED_COMMANDS = new Set([
  'TORCH_ON', 'TORCH_OFF', 'SERVER_MODE_ON', 'SERVER_MODE_OFF',
  'GET_DEVICE_INFO', 'SCREENSHOT', 'LOG_GET',
]);
// Command berat/boros yang perlu rate limit per controller.
const HEAVY_COMMANDS = new Set(['SCREENSHOT', 'GET_DEVICE_INFO']);

function handleControllerMessage(ws, msg) {
  switch (msg.type) {
    case 'ping':
      return safeSend(ws, { type: 'pong', ts: Date.now() });

    case 'device_command': {
      const { deviceId, command } = msg;
      if (typeof deviceId !== 'string' || !ALLOWED_COMMANDS.has(command)) {
        return safeSend(ws, { type: 'error', error: 'Command tidak dikenal atau device tidak valid' });
      }
      // Rate limit aksi "mahal" per controller (anti flood).
      if (HEAVY_COMMANDS.has(command)) {
        const st = ws.rlHeavy || (ws.rlHeavy = { win: 0, n: 0 });
        const now = Date.now();
        if (now - st.win > 60_000) { st.win = now; st.n = 0; }
        st.n += 1;
        if (st.n > 12) return safeSend(ws, { type: 'error', error: 'Terlalu sering, coba lagi nanti', deviceId });
      }
      const device = store.getDeviceForUser(deviceId, ws.userId);
      if (!device) return safeSend(ws, { type: 'error', error: 'Device tidak ditemukan' });

      const sockets = deviceSockets.get(deviceId);
      const online = sockets && sockets.size > 0;
      if (!online) return safeSend(ws, { type: 'error', error: 'Device offline', deviceId });

      // Teruskan ke salah satu socket device yang hidup.
      const target = sockets.values().next().value;
      safeSend(target, {
        type: 'command', command,
        cmdId: typeof msg.cmdId === 'string' ? msg.cmdId.slice(0, 64) : undefined,
      });

      // SCREENSHOT balas satu frame binary JPEG. Controller yang meminta
      // ikut ditandai agar relay binary mengirim hasilnya (15 detik).
      if (command === 'SCREENSHOT') {
        const shots = controllerShots.get(ws) || new Set();
        shots.add(deviceId);
        controllerShots.set(ws, shots);
        const timer = setTimeout(() => {
          const s = controllerShots.get(ws);
          if (s) { s.delete(deviceId); if (s.size === 0) controllerShots.delete(ws); }
        }, 15_000);
        if (typeof timer.unref === 'function') timer.unref();
      }
      return;
    }

    case 'screen_start': {
      const deviceId = msg.deviceId;
      if (typeof deviceId !== 'string') return safeSend(ws, { type: 'error', error: 'deviceId wajib' });
      const device = store.getDeviceForUser(deviceId, ws.userId);
      if (!device) return safeSend(ws, { type: 'error', error: 'Device tidak ditemukan' });

      const sockets = deviceSockets.get(deviceId);
      if (!sockets || sockets.size === 0) {
        return safeSend(ws, { type: 'error', error: 'Device offline', deviceId });
      }

      controllerStreams.get(ws)?.add(deviceId);
      const target = sockets.values().next().value;
      safeSend(target, {
        type: 'screen_start',
        width: clampInt(msg.width, 240, 1920, 1280),
        height: clampInt(msg.height, 240, 1920, 720),
        fps: clampInt(msg.fps, 1, 30, 12),
        bitrateKbps: clampInt(msg.bitrateKbps, 100, 8000, 1200),
      });
      return;
    }

    case 'screen_stop': {
      controllerStreams.get(ws)?.delete(msg.deviceId);
      controllerShots.get(ws)?.delete(msg.deviceId);
      const sockets = deviceSockets.get(msg.deviceId);
      if (sockets) for (const s of sockets) safeSend(s, { type: 'screen_stop' });
      return;
    }

    case 'camera_start': {
      const deviceId = msg.deviceId;
      if (typeof deviceId !== 'string') return safeSend(ws, { type: 'error', error: 'deviceId wajib' });
      const device = store.getDeviceForUser(deviceId, ws.userId);
      if (!device) return safeSend(ws, { type: 'error', error: 'Device tidak ditemukan' });
      const sockets = deviceSockets.get(deviceId);
      if (!sockets || sockets.size === 0) {
        return safeSend(ws, { type: 'error', error: 'Device offline', deviceId });
      }
      controllerCameras.get(ws)?.add(deviceId);
      const target = sockets.values().next().value;
      safeSend(target, {
        type: 'camera_start',
        camera: msg.camera === 'back' ? 'back' : 'front',
        width: clampInt(msg.width, 240, 1280, 640),
        height: clampInt(msg.height, 240, 1280, 480),
        fps: clampInt(msg.fps, 1, 30, 12),
        bitrateKbps: clampInt(msg.bitrateKbps, 100, 4000, 800),
      });
      // Watchdog: jika tidak ada frame kamera dalam 12 detik, tandai selesai
      // (server akan mengirim camera_stopped/error; ini jaring pengaman).
      const wdMap = deviceCameraWatchdogs.get(deviceId) || new Map();
      deviceCameraWatchdogs.set(deviceId, wdMap);
      const prev = wdMap.get(ws);
      if (prev) clearTimeout(prev);
      const t = setTimeout(() => {
        controllerCameras.get(ws)?.delete(deviceId);
        wdMap.delete(ws);
        safeSend(ws, { type: 'camera_error', deviceId, error: 'Tidak ada frame kamera (timeout)' });
      }, 12_000);
      if (typeof t.unref === 'function') t.unref();
      wdMap.set(ws, t);
      return;
    }

    case 'camera_stop': {
      controllerCameras.get(ws)?.delete(msg.deviceId);
      const wdMap = deviceCameraWatchdogs.get(msg.deviceId);
      if (wdMap) { clearTimeout(wdMap.get(ws) || 0); wdMap.delete(ws); }
      const sockets = deviceSockets.get(msg.deviceId);
      if (sockets) for (const s of sockets) safeSend(s, { type: 'camera_stop' });
      return;
    }

    case 'guard_config_update': {
      const deviceId = msg.deviceId;
      if (typeof deviceId !== 'string') return safeSend(ws, { type: 'error', error: 'deviceId wajib' });
      const device = store.getDeviceForUser(deviceId, ws.userId);
      if (!device) return safeSend(ws, { type: 'error', error: 'Device tidak ditemukan' });
      // Rate limit update config: maks 10/menit per controller.
      const rl = ws.rlGuard || (ws.rlGuard = { win: 0, n: 0 });
      const now = Date.now();
      if (now - rl.win > 60_000) { rl.win = now; rl.n = 0; }
      rl.n += 1;
      if (rl.n > 10) return safeSend(ws, { type: 'error', error: 'Terlalu sering, coba lagi nanti' });

      const res = store.setGuardConfig(deviceId, msg.config);
      if (res.error) return safeSend(ws, { type: 'error', error: res.error });
      safeSend(ws, { type: 'guard_config', deviceId, config: res.config });
      // Dorong ke device jika online (device offline akan sync saat reconnect).
      const sockets = deviceSockets.get(deviceId);
      if (sockets) for (const s of sockets) safeSend(s, { type: 'guard_config', config: res.config });
      notifyControllers(device.user_id, { type: 'guard_config_updated', deviceId });
      return;
    }

    case 'guard_config_get': {
      const deviceId = msg.deviceId;
      if (typeof deviceId !== 'string') return safeSend(ws, { type: 'error', error: 'deviceId wajib' });
      const device = store.getDeviceForUser(deviceId, ws.userId);
      if (!device) return safeSend(ws, { type: 'error', error: 'Device tidak ditemukan' });
      return safeSend(ws, { type: 'guard_config', deviceId, config: store.getGuardConfig(deviceId) });
    }

    default:
      return;
  }
}

function clampInt(v, min, max, dflt) {
  const n = Math.round(Number(v));
  if (!Number.isFinite(n)) return dflt;
  return Math.min(max, Math.max(min, n));
}

// ---------------------------------------------------------------------------
// Relay binary: device -> controller yang sedang membuka live screen
// ---------------------------------------------------------------------------

function relayBinaryFromDevice(ws, data) {
  const device = store.getDevice(ws.deviceId);
  if (!device) return;
  for (const ctrl of controllersOfUser(device.user_id)) {
    const streaming = controllerStreams.get(ctrl)?.has(ws.deviceId);
    const wantsShot = controllerShots.get(ctrl)?.has(ws.deviceId);
    const wantsCam = controllerCameras.get(ctrl)?.has(ws.deviceId);
    if (ctrl.readyState === 1 && (streaming || wantsShot || wantsCam)) {
      try { ctrl.send(data, { binary: true }); } catch { /* skip */ }
    }
    // Frame masuk berarti kamera hidup: refresh watchdog penerima ini.
    if (wantsCam) {
      const wdMap = deviceCameraWatchdogs.get(ws.deviceId);
      const t = wdMap?.get(ctrl);
      if (t) { clearTimeout(t); const nt = setTimeout(() => {
        controllerCameras.get(ctrl)?.delete(ws.deviceId);
        wdMap.delete(ctrl);
        safeSend(ctrl, { type: 'camera_error', deviceId: ws.deviceId, error: 'Stream kamera terputus (tidak ada frame)' });
      }, 12_000); if (typeof nt.unref === 'function') nt.unref(); wdMap.set(ctrl, nt); }
    }
  }
}

// ---------------------------------------------------------------------------
// Teardown & maintenance
// ---------------------------------------------------------------------------

function teardown(ws) {
  if (ws.role === 'device' && ws.deviceId) {
    const set = deviceSockets.get(ws.deviceId);
    if (set) {
      set.delete(ws);
      if (set.size === 0) {
        deviceSockets.delete(ws.deviceId);
        lastDeviceInfo.delete(ws.deviceId);
        // Semua stream dari device ini pasti berhenti (socket mati).
        for (const setS of controllerStreams.values()) setS.delete(ws.deviceId);
        for (const setC of controllerCameras.values()) setC.delete(ws.deviceId);
        for (const setSh of controllerShots.values()) setSh.delete(ws.deviceId);
        const wdMap = deviceCameraWatchdogs.get(ws.deviceId);
        if (wdMap) { for (const t of wdMap.values()) clearTimeout(t); deviceCameraWatchdogs.delete(ws.deviceId); }
        const device = store.getDevice(ws.deviceId);
        if (device) {
          store.touchSeen(ws.deviceId, 'offline');
          notifyControllers(device.user_id, { type: 'device_status', deviceId: ws.deviceId, online: false });
          logLine('info', `Device offline: ${device.device_name} (${ws.deviceId})`);
        }
      }
    }
    if (ws.sessionRowId) {
      try {
        getDb().prepare('DELETE FROM sessions WHERE id = ?').run(ws.sessionRowId);
      } catch { /* db mungkin sudah ditutup saat shutdown */ }
    }
    // Stream yang aktif otomatis berhenti karena socket mati.
  }

  if (ws.role === 'controller' && ws.userId) {
    const set = controllerSockets.get(ws.userId);
    if (set) {
      set.delete(ws);
      if (set.size === 0) controllerSockets.delete(ws.userId);
    }
  }
  controllerStreams.delete(ws);
  controllerShots.delete(ws);
  controllerCameras.delete(ws);
  // Controller yang menonton kamera hilang: watchdog device-nya tidak berguna
  // lagi; device akan berhenti sendiri lewat no-viewer watchdog di sisi APK.
  for (const wdMap of deviceCameraWatchdogs.values()) {
    const t = wdMap.get(ws);
    if (t) { clearTimeout(t); wdMap.delete(ws); }
  }
}

// Heartbeat server-side: ping semua klien, buang yang mati/idle.
setInterval(() => {
  const now = Date.now();
  for (const ws of wss.clients) {
    if (now - ws.lastTraffic > config.idleTimeoutMs) {
      try { ws.terminate(); } catch { /* noop */ }
      continue;
    }
    if (ws.isAlive === false) { try { ws.terminate(); } catch { /* noop */ } continue; }
    ws.isAlive = false;
    try { ws.ping(); } catch { /* noop */ }
  }
}, config.heartbeatIntervalMs).unref();

// ---------------------------------------------------------------------------
// Logging internal VPS (console, tidak pernah mencetak token)
// ---------------------------------------------------------------------------

function logLine(level, message) {
  if (level === 'debug' && config.logLevel !== 'debug') return;
  const ts = new Date().toISOString().slice(11, 19);
  console.log(`[${ts}] ${level.toUpperCase()} ${message}`);
}

// ---------------------------------------------------------------------------
// API publik modul
// ---------------------------------------------------------------------------

function isDeviceOnline(deviceId) {
  const set = deviceSockets.get(deviceId);
  return !!(set && set.size > 0);
}

function kickDevice(deviceId, reason) {
  const set = deviceSockets.get(deviceId);
  if (!set) return;
  for (const ws of set) {
    try { ws.close(4004, reason || 'kicked'); } catch { /* noop */ }
  }
}

function getCachedDeviceInfo(deviceId) {
  return lastDeviceInfo.get(deviceId) || null;
}

function getCachedDeviceState(deviceId) {
  return lastDeviceState.get(deviceId) || null;
}

function stats() {
  return {
    devicesOnline: [...deviceSockets.keys()].length,
    controllersOnline: [...controllerSockets.keys()].length,
  };
}

function getWsServer() {
  return { attach, isDeviceOnline, kickDevice, getCachedDeviceInfo, getCachedDeviceState, stats };
}

module.exports = { getWsServer };
