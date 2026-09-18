'use strict';

const fs = require('fs');
const path = require('path');
const { DatabaseSync } = require('node:sqlite');
const config = require('../config');

let db = null;

function getDb() {
  if (db) return db;

  if (config.dbFile !== ':memory:') {
    fs.mkdirSync(path.dirname(config.dbFile), { recursive: true });
  }

  db = new DatabaseSync(config.dbFile);
  db.exec('PRAGMA journal_mode = WAL;');
  db.exec('PRAGMA foreign_keys = ON;');

  db.exec(`
    CREATE TABLE IF NOT EXISTS users (
      id            INTEGER PRIMARY KEY AUTOINCREMENT,
      username      TEXT NOT NULL UNIQUE,
      password_hash TEXT NOT NULL,
      created_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
    );

    CREATE TABLE IF NOT EXISTS devices (
      id                TEXT PRIMARY KEY,
      user_id           INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      device_name       TEXT NOT NULL,
      device_token_hash TEXT NOT NULL,
      status            TEXT NOT NULL DEFAULT 'offline',
      last_seen         TEXT,
      created_at        TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
    );
    CREATE INDEX IF NOT EXISTS idx_devices_user ON devices(user_id);

    CREATE TABLE IF NOT EXISTS pairing_codes (
      code        TEXT PRIMARY KEY,
      user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      device_name TEXT NOT NULL,
      attempts    INTEGER NOT NULL DEFAULT 0,
      expires_at  TEXT NOT NULL,
      created_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
    );

    CREATE TABLE IF NOT EXISTS sessions (
      id             INTEGER PRIMARY KEY AUTOINCREMENT,
      device_id      TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
      type           TEXT NOT NULL,
      connected_at   TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
      last_heartbeat TEXT
    );
    CREATE INDEX IF NOT EXISTS idx_sessions_device ON sessions(device_id);

    -- Server Guard config per device (di-update dari controller, dibaca device
    -- saat online). Config persisten sehingga tidak hilang saat device offline.
    CREATE TABLE IF NOT EXISTS guard_configs (
      device_id  TEXT PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
      config     TEXT NOT NULL,
      updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
    );
  `);

  return db;
}

module.exports = { getDb };
