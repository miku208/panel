'use strict';

/**
 * Mode pribadi (personal use): tanpa register/pairing code.
 * Kedua APK memakai satu kunci rahasia yang sama (PRIVATE_KEY di .env VPS):
 *
 *   POST /api/private/device  { key, name? }  -> APK Server: daftar device baru,
 *                                                return { deviceId, deviceToken, deviceName }
 *   POST /api/private/session { key }         -> APK Controller: JWT milik owner,
 *                                                return { token, username }
 *
 * Kunci salah = 403. Endpoint hanya aktif jika PRIVATE_KEY di-set di .env.
 */

const crypto = require('crypto');
const express = require('express');
const config = require('../config');
const store = require('../devices/store');
const jwt = require('../auth/jwt');
const { getDb } = require('../database/db');
const { clientIp, fail } = require('./auth-middleware');
const { hit } = require('../auth/rate-limit');

const router = express.Router();

function keyOk(key) {
  if (!config.privateKey || typeof key !== 'string' || !key) return false;
  const a = Buffer.from(String(key));
  const b = Buffer.from(config.privateKey);
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function privateUserId(db) {
  let row = db.prepare('SELECT id FROM users WHERE username = ?').get('private-owner');
  if (!row) {
    const info = db.prepare("INSERT INTO users (username, password_hash) VALUES ('private-owner', 'disabled')")
      .run();
    row = { id: Number(info.lastInsertRowid) };
  }
  return row.id;
}

// POST /api/private/device { key, name? }
router.post('/device', (req, res) => {
  const rl = hit('private-device', clientIp(req), config.authRateWindowMs, config.authRateMax);
  if (!rl.allowed) return fail(res, 429, 'Terlalu banyak percobaan. Coba lagi nanti.');

  const { key, name } = req.body || {};
  if (!keyOk(key)) return fail(res, 403, 'Kunci pribadi salah');

  const deviceName = (typeof name === 'string' && name.trim()) ? name.trim().slice(0, 80) : 'HP Server';
  const device = store.createDevice(privateUserId(getDb()), deviceName);
  res.status(201).json({
    deviceId: device.id,
    deviceToken: device.token,
    deviceName,
  });
});

// POST /api/private/session { key }
router.post('/session', (req, res) => {
  const rl = hit('private-session', clientIp(req), config.authRateWindowMs, config.authRateMax);
  if (!rl.allowed) return fail(res, 429, 'Terlalu banyak percobaan. Coba lagi nanti.');

  const { key } = req.body || {};
  if (!keyOk(key)) return fail(res, 403, 'Kunci pribadi salah');

  const userId = privateUserId(getDb());
  const token = jwt.sign({ sub: 'user:' + userId, username: 'private' }, config.userTokenTtl);
  res.json({ token, username: 'private' });
});

module.exports = router;
