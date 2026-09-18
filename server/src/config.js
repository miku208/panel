'use strict';

const path = require('path');

function num(v, dflt) {
  const n = Number(v);
  return Number.isFinite(n) && n > 0 ? n : dflt;
}

const config = {
  port: num(process.env.PORT, 8787),
  host: process.env.HOST || '0.0.0.0',

  // File SQLite. ":memory:" boleh untuk testing.
  dbFile: process.env.DB_FILE || path.join(__dirname, '..', 'data', 'mikuremote.db'),

  // Secret untuk signing JWT. WAJIB di-set di production (lihat .env.example).
  jwtSecret: process.env.JWT_SECRET || '',

  // Kunci pribadi untuk mode personal (tanpa register/pairing code).
  // Kosong = endpoint /api/private/* dinonaktifkan.
  privateKey: process.env.PRIVATE_KEY || '',

  // Masa aktif token login (detik) dan token device (detik).
  userTokenTtl: num(process.env.USER_TOKEN_TTL, 60 * 60 * 24 * 7),   // 7 hari
  deviceTokenTtl: num(process.env.DEVICE_TOKEN_TTL, 60 * 60 * 24 * 365), // 1 tahun

  // Pairing
  pairingCodeTtl: num(process.env.PAIRING_CODE_TTL, 60 * 15), // 15 menit
  pairingMaxAttempts: num(process.env.PAIRING_MAX_ATTEMPTS, 10),

  // Batas proteksi
  maxWsMessageBytes: num(process.env.MAX_WS_MESSAGE_BYTES, 512 * 1024), // 512 KB utk control frame
  maxBinaryFrameBytes: num(process.env.MAX_BINARY_FRAME_BYTES, 2 * 1024 * 1024),
  idleTimeoutMs: num(process.env.IDLE_TIMEOUT_MS, 60_000), // koneksi tanpa traffic > 60s diputus
  heartbeatIntervalMs: num(process.env.HEARTBEAT_INTERVAL_MS, 15_000),

  // Rate limit (window ms, max percobaan per window per IP)
  authRateWindowMs: num(process.env.AUTH_RATE_WINDOW_MS, 60_000),
  authRateMax: num(process.env.AUTH_RATE_MAX, 10),
  pairingRateWindowMs: num(process.env.PAIRING_RATE_WINDOW_MS, 60_000),
  pairingRateMax: num(process.env.PAIRING_RATE_MAX, 5),

  // Logging
  logLevel: process.env.LOG_LEVEL || 'info',
};

module.exports = config;
