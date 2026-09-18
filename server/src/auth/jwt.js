'use strict';

const crypto = require('crypto');
const config = require('../config');

function b64url(buf) {
  return Buffer.from(buf).toString('base64url');
}

function sign(payload, ttlSeconds) {
  if (!config.jwtSecret) throw new Error('JWT_SECRET is not configured');
  const header = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const now = Math.floor(Date.now() / 1000);
  const body = b64url(JSON.stringify({ ...payload, iat: now, exp: now + ttlSeconds }));
  const data = `${header}.${body}`;
  const sig = crypto.createHmac('sha256', config.jwtSecret).update(data).digest('base64url');
  return `${data}.${sig}`;
}

// Return payload jika valid & belum expired, else null.
function verify(token) {
  if (!config.jwtSecret || typeof token !== 'string') return null;
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  const [header, body, sig] = parts;
  const data = `${header}.${body}`;
  const expected = crypto.createHmac('sha256', config.jwtSecret).update(data).digest('base64url');
  const a = Buffer.from(sig);
  const b = Buffer.from(expected);
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return null;

  let payload;
  try {
    payload = JSON.parse(Buffer.from(body, 'base64url').toString('utf8'));
  } catch {
    return null;
  }
  if (!payload || typeof payload.exp !== 'number') return null;
  if (payload.exp * 1000 < Date.now()) return null;
  return payload;
}

module.exports = { sign, verify };
