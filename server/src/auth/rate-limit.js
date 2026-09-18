'use strict';

// Rate limiter sederhana in-memory per (key, bucket).
const buckets = new Map();

function hit(bucket, key, windowMs, max) {
  const now = Date.now();
  const id = `${bucket}:${key}`;
  let entry = buckets.get(id);
  if (!entry || now - entry.start > windowMs) {
    entry = { start: now, count: 0 };
    buckets.set(id, entry);
  }
  entry.count += 1;
  return {
    allowed: entry.count <= max,
    remaining: Math.max(0, max - entry.count),
    retryAfterMs: entry.start + windowMs - now,
  };
}

// Bersihkan bucket kadaluarsa supaya map tidak membengkak.
setInterval(() => {
  const now = Date.now();
  for (const [id, entry] of buckets) {
    // window max 1 jam cukup aman untuk semua bucket yang dipakai
    if (now - entry.start > 60 * 60 * 1000) buckets.delete(id);
  }
}, 10 * 60 * 1000).unref();

module.exports = { hit };
