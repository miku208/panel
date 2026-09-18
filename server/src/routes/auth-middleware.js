'use strict';

const jwt = require('../auth/jwt');

function clientIp(req) {
  return req.socket.remoteAddress || 'unknown';
}

function fail(res, status, message) {
  return res.status(status).json({ error: message });
}

// Middleware: wajib Bearer token user yang valid. Set req.userId.
function requireUser(req, res, next) {
  const header = req.headers.authorization || '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : null;
  const payload = token && jwt.verify(token);
  if (!payload || typeof payload.sub !== 'string' || !payload.sub.startsWith('user:')) {
    return fail(res, 401, 'Tidak terautentikasi');
  }
  req.userId = Number(payload.sub.slice(5));
  if (!Number.isInteger(req.userId)) return fail(res, 401, 'Token tidak valid');
  next();
}

module.exports = { requireUser, clientIp, fail };
