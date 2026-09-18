'use strict';

const crypto = require('crypto');
const { getDb } = require('../database/db');
const config = require('../config');

function generateCode() {
  // 6 digit, kelompok 3-3 agar mudah dibaca: 482 913
  const n = crypto.randomInt(0, 1_000_000);
  const s = String(n).padStart(6, '0');
  return `${s.slice(0, 3)} ${s.slice(3)}`;
}

function normalize(code) {
  return String(code || '').replace(/\D/g, '');
}

// Controller meminta pairing code baru (dari akun miliknya).
function createPairingCode(userId, deviceName) {
  const db = getDb();
  const formatted = generateCode();
  // Simpan versi digit-only agar pencocokan konsisten dgn input user.
  const code = normalize(formatted);
  const expiresAt = new Date(Date.now() + config.pairingCodeTtl * 1000).toISOString();
  db.prepare('INSERT INTO pairing_codes (code, user_id, device_name, expires_at) VALUES (?, ?, ?, ?)')
    .run(code, userId, String(deviceName || 'Server Baru').slice(0, 80), expiresAt);
  // Buang kode expired lama.
  db.prepare("DELETE FROM pairing_codes WHERE expires_at < ?").run(new Date().toISOString());
  return { code: formatted, expiresAt };
}

// Server app menukar pairing code dengan device token.
// Setiap percobaan redeem dihitung; kelebihan batas memblokir kode.
// Return: { ok, device: {id, token, name} } atau { ok:false, error }
function redeemPairingCode(rawCode) {
  const db = getDb();
  const code = normalize(rawCode);
  if (code.length !== 6) return { ok: false, error: 'Pairing code harus 6 digit' };

  const row = db.prepare('SELECT * FROM pairing_codes WHERE code = ?').get(code);
  if (!row) return { ok: false, error: 'Pairing code tidak ditemukan' };

  // Hitung percobaan ini.
  db.prepare('UPDATE pairing_codes SET attempts = attempts + 1 WHERE code = ?').run(code);
  const attempts = row.attempts + 1;

  if (new Date(row.expires_at).getTime() < Date.now()) {
    db.prepare('DELETE FROM pairing_codes WHERE code = ?').run(code);
    return { ok: false, error: 'Pairing code sudah kedaluwarsa' };
  }

  if (attempts > config.pairingMaxAttempts) {
    db.prepare('DELETE FROM pairing_codes WHERE code = ?').run(code);
    return { ok: false, error: 'Pairing code diblokir (terlalu banyak percobaan)' };
  }

  // Kode satu pakai: habis dipakai langsung dihapus.
  db.prepare('DELETE FROM pairing_codes WHERE code = ?').run(code);

  const { createDevice } = require('../devices/store');
  const device = createDevice(row.user_id, row.device_name);
  return { ok: true, device: { ...device, name: row.device_name } };
}

module.exports = { createPairingCode, redeemPairingCode };
