'use strict';

const crypto = require('crypto');

// scrypt bawaan Node: tidak ada dependency eksternal, parameter setara
// kekuatan bcrypt cost ~13 untuk interaksi login. Format tersimpan:
// scrypt$N$r$p$<salt-hex>$<hash-hex>
const N = 16384, R = 8, P = 1, KEYLEN = 64;

function hashPassword(password) {
  const salt = crypto.randomBytes(16);
  const hash = crypto.scryptSync(String(password), salt, KEYLEN, { N, r: R, p: P });
  return ['scrypt', N, R, P, salt.toString('hex'), hash.toString('hex')].join('$');
}

function verifyPassword(password, stored) {
  try {
    const [scheme, nStr, rStr, pStr, saltHex, hashHex] = String(stored).split('$');
    if (scheme !== 'scrypt') return false;
    const salt = Buffer.from(saltHex, 'hex');
    const expected = Buffer.from(hashHex, 'hex');
    const actual = crypto.scryptSync(String(password), salt, expected.length, {
      N: Number(nStr), r: Number(rStr), p: Number(pStr),
    });
    return crypto.timingSafeEqual(actual, expected);
  } catch {
    return false;
  }
}

module.exports = { hashPassword, verifyPassword };
