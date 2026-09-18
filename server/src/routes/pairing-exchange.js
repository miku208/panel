'use strict';

// Endpoint publik untuk DEVICE (server APK): tukar pairing code dengan
// device token. Tidak butuh auth, tapi di-rate-limit per IP.

const express = require('express');
const pairing = require('../devices/pairing');
const { clientIp, fail } = require('./auth-middleware');
const { hit } = require('../auth/rate-limit');
const config = require('../config');

const router = express.Router();

router.post('/pairing-exchange', (req, res) => {
  const rl = hit('pairing-exchange', clientIp(req), config.pairingRateWindowMs, config.pairingRateMax * 4);
  if (!rl.allowed) return fail(res, 429, 'Terlalu banyak percobaan pairing. Coba lagi nanti.');

  const { code } = req.body || {};
  const result = pairing.redeemPairingCode(code);
  if (!result.ok) return fail(res, 400, result.error);

  res.json({
    deviceId: result.device.id,
    deviceToken: result.device.token,
    deviceName: result.device.name,
  });
});

module.exports = router;
