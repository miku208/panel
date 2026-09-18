'use strict';

const express = require('express');
const http = require('http');
const config = require('./config');
const authRoutes = require('./routes/auth');
const deviceRoutes = require('./routes/devices');
const pairingExchangeRoutes = require('./routes/pairing-exchange');
const privateRoutes = require('./routes/private');
const { getWsServer } = require('./websocket/ws-server');

const app = express();
const server = http.createServer(app);

// Body parser dengan batas ukuran (proteksi packet besar).
app.use(express.json({ limit: '64kb' }));

// Trust proxy hanya jika di-set (saat berada di belakang nginx/caddy).
if (process.env.TRUST_PROXY === '1') app.set('trust proxy', true);

// Request log ringan.
app.use((req, res, next) => {
  const ts = new Date().toISOString().slice(11, 19);
  res.on('finish', () => {
    if (req.path.startsWith('/api/')) {
      console.log(`[${ts}] ${req.method} ${req.originalUrl} -> ${res.statusCode}`);
    }
  });
  next();
});

app.get('/health', (req, res) => {
  res.json({ ok: true, service: 'mikuremote-vps', time: new Date().toISOString() });
});

app.use('/api/auth', authRoutes);
app.use('/api/private', privateRoutes);
app.use('/api', pairingExchangeRoutes);
app.use('/api', deviceRoutes);

// 404 API
app.use('/api', (req, res) => res.status(404).json({ error: 'Endpoint tidak ditemukan' }));

// Error handler: jangan bocorkan stack di production.
app.use((err, req, res, next) => {
  console.error(`[error] ${req.method} ${req.originalUrl}:`, err.message);
  if (err.type === 'entity.too.large') {
    return res.status(413).json({ error: 'Payload terlalu besar' });
  }
  if (err.type === 'entity.parse.failed') {
    return res.status(400).json({ error: 'JSON tidak valid' });
  }
  res.status(500).json({ error: 'Kesalahan internal server' });
});

// WebSocket
getWsServer().attach(server);

if (!config.jwtSecret) {
  console.error('FATAL: JWT_SECRET belum di-set. Salin .env.example ke .env lalu isi JWT_SECRET.');
  process.exit(1);
}

server.listen(config.port, config.host, () => {
  console.log(`MikuRemote VPS listening on ${config.host}:${config.port} (ws path: /ws)`);
});

// Graceful shutdown.
for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, () => {
    console.log(`\n${sig} diterima, mematikan server...`);
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 3000).unref();
  });
}
