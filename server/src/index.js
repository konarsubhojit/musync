'use strict';

const os = require('node:os');
const path = require('node:path');

// Load `server/.env` before any module reads `process.env`. Resolved relative to
// this file so it works no matter which directory the process was started from.
// Values already present in the real environment take precedence, so deployments
// that inject config (systemd, Docker, Render, ...) are unaffected.
loadEnvFile();

const { createApp } = require('./server');
const { logger } = require('./logger');

function loadEnvFile() {
  const envPath = path.resolve(__dirname, '..', '.env');
  try {
    process.loadEnvFile(envPath);
  } catch (err) {
    // ENOENT simply means no .env file, which is normal in production where the
    // platform supplies environment variables directly.
    if (err.code !== 'ENOENT') {
      console.warn(`[musync-server] could not load ${envPath}: ${err.message}`);
    }
  }
}

const PORT = process.env.PORT ?? 3000;

// Bind to every interface by default so phones on the same Wi-Fi can reach the
// server. Set HOST=127.0.0.1 to restrict it to this machine only.
const HOST = process.env.HOST ?? '0.0.0.0';

const { httpServer } = createApp();

/**
 * Non-internal IPv4 addresses of this machine, i.e. the ones a phone on the same
 * network can actually connect to.
 *
 * @returns {string[]}
 */
function lanAddresses() {
  return Object.values(os.networkInterfaces())
    .flat()
    .filter((iface) => iface && iface.family === 'IPv4' && !iface.internal)
    .map((iface) => iface.address);
}

httpServer.listen(PORT, HOST, () => {
  logger.info('server listening', {
    host: HOST,
    port: Number(PORT),
    logLevel: logger.level,
    node: process.version,
    youtubeApiKey: process.env.YOUTUBE_API_KEY ? 'configured' : 'missing',
  });

  // Printed so the exact value can be pasted into the Android app's
  // Settings → Developer → Server URL field.
  for (const address of lanAddresses()) {
    logger.info('reachable on this network', { url: `http://${address}:${PORT}` });
  }
});

process.on('unhandledRejection', (reason) => {
  logger.error('unhandled promise rejection', { reason });
});

process.on('uncaughtException', (err) => {
  logger.error('uncaught exception', { err });
});
