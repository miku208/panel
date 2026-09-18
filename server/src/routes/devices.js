'use strict';

const express = require('express');
const { requireUser, clientIp, fail } = require('./auth-middleware');
const store = require('../devices/store');
const pairing = require('../devices/pairing');
const { getWsServer } = require('../websocket/ws-server');
const { hit } = require('../auth/rate-limit');
const config = require('../config');

const router = express.Router();
router.use(requireUser);

// GET /api/devices -> daftar device milik user + status online + lifecycle state
router.get('/devices', (req, res) => {
  const devices = store.listDevices(req.userId).map((d) => ({
    ...d,
    online: getWsServer().isDeviceOnline(d.id),
    // starting/online/reconnecting sesuai laporan device terakhir (Phase 4).
    state: getWsServer().getCachedDeviceState(d.id)?.state || null,
  }));
  res.json({ devices });
});

// POST /api/pairing-code { deviceName } -> buat pairing code utk server baru
router.post('/pairing-code', (req, res) => {
  const rl = hit('pairing-code', clientIp(req), config.pairingRateWindowMs, config.pairingRateMax);
  if (!rl.allowed) return fail(res, 429, 'Terlalu banyak permintaan pairing. Coba lagi nanti.');

  const { deviceName } = req.body || {};
  if (typeof deviceName !== 'string' || deviceName.trim().length < 1 || deviceName.length > 80) {
    return fail(res, 400, 'Nama device wajib diisi (maks 80 karakter)');
  }
  const { code, expiresAt } = pairing.createPairingCode(req.userId, deviceName.trim());
  res.status(201).json({ code, expiresAt });
});

// POST /api/devices/:id/rename { deviceName }
router.post('/devices/:id/rename', (req, res) => {
  const { deviceName } = req.body || {};
  if (typeof deviceName !== 'string' || deviceName.trim().length < 1) {
    return fail(res, 400, 'Nama device wajib diisi');
  }
  if (!store.renameDevice(req.userId, req.params.id, deviceName.trim())) {
    return fail(res, 404, 'Device tidak ditemukan');
  }
  res.json({ ok: true });
});

// DELETE /api/devices/:id -> revoke/unpair device
router.delete('/devices/:id', (req, res) => {
  const device = store.getDeviceForUser(req.params.id, req.userId);
  if (!device) return fail(res, 404, 'Device tidak ditemukan');
  // Putus koneksi WS device jika sedang online.
  getWsServer().kickDevice(device.id, 'device_revoked');
  if (!store.deleteDevice(req.userId, device.id)) {
    return fail(res, 404, 'Device tidak ditemukan');
  }
  res.json({ ok: true });
});

module.exports = router;
