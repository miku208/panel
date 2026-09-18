'use strict';

const crypto = require('crypto');
const { getDb } = require('../database/db');

function newDeviceId() {
  return 'dev_' + crypto.randomBytes(9).toString('base64url');
}

function newDeviceToken() {
  return 'mrt_' + crypto.randomBytes(32).toString('base64url');
}

function hashToken(token) {
  return crypto.createHash('sha256').update(String(token)).digest('hex');
}

function createDevice(userId, deviceName) {
  const db = getDb();
  const id = newDeviceId();
  const token = newDeviceToken();
  db.prepare(
    'INSERT INTO devices (id, user_id, device_name, device_token_hash) VALUES (?, ?, ?, ?)'
  ).run(id, userId, String(deviceName).slice(0, 80), hashToken(token));
  return { id, token };
}

function listDevices(userId) {
  const db = getDb();
  return db.prepare(
    `SELECT d.id, d.device_name, d.status, d.last_seen, d.created_at,
            (SELECT COUNT(*) FROM sessions s WHERE s.device_id = d.id) AS active_sessions
       FROM devices d
      WHERE d.user_id = ?
      ORDER BY d.created_at ASC`
  ).all(userId);
}

function getDevice(deviceId) {
  const db = getDb();
  return db.prepare('SELECT * FROM devices WHERE id = ?').get(deviceId) || null;
}

// Ambil device milik user tertentu (untuk otorisasi command).
function getDeviceForUser(deviceId, userId) {
  const db = getDb();
  return db.prepare('SELECT * FROM devices WHERE id = ? AND user_id = ?').get(deviceId, userId) || null;
}

function getDeviceByToken(token) {
  if (typeof token !== 'string' || !token) return null;
  const db = getDb();
  return db.prepare('SELECT * FROM devices WHERE device_token_hash = ?').get(hashToken(token)) || null;
}

function deleteDevice(userId, deviceId) {
  const db = getDb();
  const info = db.prepare('DELETE FROM devices WHERE id = ? AND user_id = ?').run(deviceId, userId);
  return info.changes > 0;
}

function renameDevice(userId, deviceId, newName) {
  const db = getDb();
  const info = db.prepare('UPDATE devices SET device_name = ? WHERE id = ? AND user_id = ?')
    .run(String(newName).slice(0, 80), deviceId, userId);
  return info.changes > 0;
}

// ---------------------------------------------------------------------------
// Server Guard config (per device, persisten di VPS)
// ---------------------------------------------------------------------------

const GUARD_CONFIG_DEFAULTS = {
  enabled: false,
  warningEnabled: true,
  warningTitle: 'HP INI SEDANG DIGUNAKAN SEBAGAI SERVER',
  warningMessage: 'Jangan membuka game atau aplikasi berat.\nPenggunaan HP ini dapat mengganggu layanan server.',
  warningContactName: '',
  warningContactNumber: '',
  warningButtonText: 'HUBUNGI ADMIN',
  serverName: '',
  serverLocationLabel: '',
  showBattery: true,
  showTemperature: true,
  showRAM: true,
  showStorage: true,
  showUptime: true,
  batteryLowThreshold: 20,
  storageLowThresholdGb: 2,
  ramHighThreshold: 90,
  temperatureHighThreshold: 45,
};

const GUARD_STRING_FIELDS = [
  'warningTitle', 'warningMessage', 'warningContactName',
  'warningContactNumber', 'warningButtonText', 'serverName', 'serverLocationLabel',
];
const GUARD_BOOL_FIELDS = [
  'enabled', 'warningEnabled', 'showBattery', 'showTemperature',
  'showRAM', 'showStorage', 'showUptime',
];
const GUARD_NUM_FIELDS = ['batteryLowThreshold', 'storageLowThresholdGb', 'ramHighThreshold', 'temperatureHighThreshold'];
const GUARD_NUM_LIMITS = {
  batteryLowThreshold: [0, 100],
  storageLowThresholdGb: [0, 512],
  ramHighThreshold: [0, 100],
  temperatureHighThreshold: [20, 90],
};

/** Validasi + sanitasi config dari controller. Return {config, error}. */
function sanitizeGuardConfig(input) {
  if (!input || typeof input !== 'object') return { error: 'Config tidak valid' };
  const out = { ...GUARD_CONFIG_DEFAULTS };
  for (const k of GUARD_STRING_FIELDS) {
    if (typeof input[k] === 'string') out[k] = input[k].slice(0, 500);
  }
  for (const k of GUARD_BOOL_FIELDS) {
    if (typeof input[k] === 'boolean') out[k] = input[k];
  }
  for (const k of GUARD_NUM_FIELDS) {
    if (typeof input[k] === 'number' && Number.isFinite(input[k])) {
      const [lo, hi] = GUARD_NUM_LIMITS[k];
      out[k] = Math.min(hi, Math.max(lo, Math.round(input[k])));
    }
  }
  return { config: out };
}

function getGuardConfig(deviceId) {
  const db = getDb();
  const row = db.prepare('SELECT config FROM guard_configs WHERE device_id = ?').get(deviceId);
  if (!row) return { ...GUARD_CONFIG_DEFAULTS };
  try {
    const parsed = JSON.parse(row.config);
    // Merge dengan defaults: field baru tetap punya nilai wajar.
    return { ...GUARD_CONFIG_DEFAULTS, ...parsed };
  } catch {
    return { ...GUARD_CONFIG_DEFAULTS };
  }
}

function setGuardConfig(deviceId, input) {
  const { config, error } = sanitizeGuardConfig(input);
  if (error) return { error };
  const db = getDb();
  db.prepare(
    `INSERT INTO guard_configs (device_id, config, updated_at) VALUES (?, ?, ?)
     ON CONFLICT(device_id) DO UPDATE SET config = excluded.config, updated_at = excluded.updated_at`
  ).run(deviceId, JSON.stringify(config), new Date().toISOString());
  return { config };
}

function touchSeen(deviceId, status) {
  const db = getDb();
  db.prepare('UPDATE devices SET status = ?, last_seen = ? WHERE id = ?')
    .run(status, new Date().toISOString(), deviceId);
}

module.exports = {
  createDevice, listDevices, getDevice, getDeviceForUser, getDeviceByToken,
  deleteDevice, renameDevice, touchSeen, hashToken,
  getGuardConfig, setGuardConfig, sanitizeGuardConfig, GUARD_CONFIG_DEFAULTS,
};
