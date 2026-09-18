'use strict';

const crypto = require('crypto');
const express = require('express');
const config = require('../config');
const { hashPassword, verifyPassword } = require('../auth/passwords');
const jwt = require('../auth/jwt');
const { getDb } = require('../database/db');
const { hit } = require('../auth/rate-limit');

const router = express.Router();

function clientIp(req) {
  return req.socket.remoteAddress || 'unknown';
}

function fail(res, status, message) {
  return res.status(status).json({ error: message });
}

// POST /api/auth/register { username, password }
router.post('/register', (req, res) => {
  const rl = hit('register', clientIp(req), config.authRateWindowMs, config.authRateMax);
  if (!rl.allowed) return fail(res, 429, 'Terlalu banyak percobaan. Coba lagi nanti.');

  const { username, password } = req.body || {};
  if (typeof username !== 'string' || !/^[a-zA-Z0-9_.-]{3,32}$/.test(username)) {
    return fail(res, 400, 'Username 3-32 karakter (huruf, angka, . _ -)');
  }
  if (typeof password !== 'string' || password.length < 8 || password.length > 128) {
    return fail(res, 400, 'Password minimal 8 karakter');
  }

  const db = getDb();
  const exists = db.prepare('SELECT id FROM users WHERE username = ?').get(username);
  if (exists) return fail(res, 409, 'Username sudah dipakai');

  const info = db.prepare('INSERT INTO users (username, password_hash) VALUES (?, ?)')
    .run(username, hashPassword(password));
  const token = jwt.sign({ sub: 'user:' + info.lastInsertRowid, username }, config.userTokenTtl);
  res.status(201).json({ token, username });
});

// POST /api/auth/login { username, password }
router.post('/login', (req, res) => {
  const rl = hit('login', clientIp(req), config.authRateWindowMs, config.authRateMax);
  if (!rl.allowed) return fail(res, 429, 'Terlalu banyak percobaan. Coba lagi nanti.');

  const { username, password } = req.body || {};
  if (typeof username !== 'string' || typeof password !== 'string') {
    return fail(res, 400, 'Username dan password wajib diisi');
  }

  const db = getDb();
  const user = db.prepare('SELECT * FROM users WHERE username = ?').get(username);
  // Selalu jalankan verify agar timing tidak membocorkan keberadaan username.
  const stored = user ? user.password_hash
    : 'scrypt$16384$8$1$00$00';
  const ok = verifyPassword(password, stored);
  if (!user || !ok) return fail(res, 401, 'Username atau password salah');

  const token = jwt.sign({ sub: 'user:' + user.id, username: user.username }, config.userTokenTtl);
  res.json({ token, username: user.username });
});

module.exports = router;
